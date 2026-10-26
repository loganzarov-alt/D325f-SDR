// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
package com.mantz_it.rfanalyzer.source;

import com.mantz_it.nativedsp.NativeDsp;
import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Autonomous signed-CI8 source for the resident D3FQ/FTM902 LG D325 modem patch. */
public final class LgModemSource implements IQSourceInterface {
    private static final String TAG = "LgModemSource";
    private static final String FACTORY_SHA = NativeHelperHashes.FACTORY;
    private static final String ONLINE_SHA = NativeHelperHashes.ONLINE;
    private static final String STREAM_SHA = NativeHelperHashes.STREAM;
    public static final int SAMPLE_RATE = 7_680_000;
    /** The UI may zoom out to the complete 7.68 MSps capture window. */
    public static final int DISPLAY_SAMPLE_RATE = SAMPLE_RATE;
    /** Directly measured PLL-lock limits of this LG D325. */
    public static final long MIN_FREQUENCY = 1L;
    public static final long MAX_FREQUENCY = 4_915_100_000L;
    public static final int PROFILE_AUTO = 0;
    public static final int TUNE_MODE_EXACT = 0;
    public static final int TUNE_MODE_UARFCN = 1;
    public static final int UARFCN_MIN = 0;
    public static final int UARFCN_MAX = 65535;
    public static final int RF_BAND_B1 = 1;
    public static final int RF_BAND_B8 = 8;
    /** 2.13 ms of CI8: large enough to avoid per-block GC, small enough for low latency. */
    private static final int PACKET_BYTES = 32768;

    public static final String IQ_TEST_PREFS = "iq_orientation_test";
    public static final String IQ_TEST_EXTRA = "extra_conjugation";
    private android.content.SharedPreferences iqTestPreferences;
    private boolean extraConjugation = true;
    private boolean conjugateIq(long rf) {
        return (rf < BtRxLimit.activeHz) ^ extraConjugation;
    }
    private volatile long frequency;
    private volatile int processingRate = SAMPLE_RATE;
    private long bandwidthHandle;
    private long bandwidthFrequency = Long.MIN_VALUE;
    private final float[] bandwidthRe = new float[PACKET_BYTES / 2];
    private final float[] bandwidthIm = new float[PACKET_BYTES / 2];
    private int bandwidthCount;
    public static String bandwidthLabel(long rate) {
        if (rate == 2560000) return "2 МГц · 2,56 MS/s";
        if (rate == 1280000) return "1 МГц · 1,28 MS/s";
        return "5 МГц · 7,68 MS/s (штатная)";
    }
    // Called by Scheduler exactly once per raw packet, shared by FFT and audio.
    public void prepareBandwidth(byte[] packet) {
        if (processingRate == SAMPLE_RATE) return;
        if (bandwidthHandle == 0 || bandwidthFrequency != frequency) {
            releaseBandwidth();
            bandwidthHandle = nativeDsp.createBandwidth(processingRate);
            bandwidthFrequency = frequency;
        }
        bandwidthCount = nativeDsp.filterBandwidth(bandwidthHandle, packet, bandwidthRe, bandwidthIm, conjugateIq(frequency));
    }
    public void releaseBandwidth() {
        if (bandwidthHandle != 0) nativeDsp.destroyBandwidth(bandwidthHandle);
        bandwidthHandle = 0;
    }

    private volatile int profileId;
    private volatile int rfBand;
    private volatile int tuneMode;
    private volatile int uarfcn;
    private volatile int pllMultiplier;
    private volatile int lnaState;
    private volatile int dvgaOffset;
    private volatile boolean opened;
    private boolean factoryHeld;
    private volatile boolean sampling;
    private Callback callback;
    private File factory;
    private File online;
    private File streamer;
    private Process process;
    private InputStream reader;
    private BufferedReader statusReader;
    private Thread statusThread;
    private Thread streamPumpThread;
    private volatile OutputStream control;
    private final ArrayBlockingQueue<byte[]> packetPool = new ArrayBlockingQueue<>(512);
    /**
     * The native helper must be drained continuously at 15.36 MB/s.  Spectrum/UI
     * work must never apply back-pressure to its stdout pipe, otherwise the
     * helper's 64 MiB stage ring eventually fills and the stream terminates.
     * Keep only a short live queue; when Android falls behind we discard the
     * oldest live packet and keep the newest RF data.
     */
    private final ArrayBlockingQueue<byte[]> readyPackets = new ArrayBlockingQueue<>(512);
    private volatile boolean streamPumpEnded;
    private volatile long livePacketsDropped;
    /** When raw IQ recording is active, never discard a live Java packet. */
    private volatile boolean losslessConsumerMode;
    /** FM/AM/SSB audio also needs sample continuity; packet drops become clicks. */
    private volatile boolean demodulationContinuityMode;
    private volatile long blocks;
    public long getReceivedBlockCount() { return blocks; }
    private BtCwController bt;
    private long changedBlocks;
    private byte[] previousBlock;
    private double mixerPhase;
    private long demodChannel = Long.MIN_VALUE;
    private long demodSourceFrequency = Long.MIN_VALUE;
    private double oscillatorRe = 1.0, oscillatorIm = 0.0;
    private double oscillatorStepRe = 1.0, oscillatorStepIm = 0.0;
    private int demodInputSkip;
    private final NativeDsp nativeDsp = new NativeDsp();
    // {NCO re, NCO im, boxcar sum re, boxcar sum im, boxcar count}
    private final float[] demodNativeState = new float[] {1f, 0f, 0f, 0f, 0f};
    private final Object retuneLock = new Object();
    private long pendingRetune = -1;
    private long retuneSession;
    private boolean retuneWorkerRunning;

    public LgModemSource(long initialFrequency, int initialRfBand, int initialTuneMode, int initialUarfcn,
                         int initialProfileId, int initialPllMultiplier, int initialLnaState, int initialDvgaOffset) {
        rfBand = validRfBand(initialRfBand) ? initialRfBand : RF_BAND_B8;
        tuneMode = TUNE_MODE_EXACT;
        uarfcn = clamp(initialUarfcn, UARFCN_MIN, UARFCN_MAX);
        frequency = tuneMode == TUNE_MODE_UARFCN
                ? frequencyForUarfcn(rfBand, uarfcn)
                : clampFrequency(initialFrequency > 0 ? initialFrequency : 1_700_000_000L);
        profileId = validProfile(initialProfileId) ? initialProfileId : PROFILE_AUTO;
        pllMultiplier = validMultiplier(initialPllMultiplier) ? initialPllMultiplier : 20;
        lnaState = clamp(initialLnaState, 0, 5);
        dvgaOffset = clamp(initialDvgaOffset, -512, 511);
        // Allocate the hot-path CI8 buffers once.  At ~469 packets/s even a short
        // allocation burst is needless GC pressure on this old 1 GB device.
        for (int i = 0; i < 512; i++) packetPool.offer(new byte[PACKET_BYTES]);
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static boolean validMultiplier(int value) {
        return value == 10 || value == 20 || value == 40;
    }

    private static boolean validRfBand(int value) { return value == RF_BAND_B1 || value == RF_BAND_B8; }

    private static boolean validTuneMode(int value) {
        return value == TUNE_MODE_EXACT || value == TUNE_MODE_UARFCN;
    }

    /**
     * RF center produced by the modem v10 stock UARFCN conversion path.  The
     * modem calculation is 32-bit, so emulate its unsigned wrap exactly for the
     * full 16-bit control field documented by the project.
     */
    public static long frequencyForUarfcn(int band, int requestedUarfcn) {
        long channel = clamp(requestedUarfcn, UARFCN_MIN, UARFCN_MAX);
        long raw = (band == RF_BAND_B8 ? 385_000_000L : 0L) + channel * 200_000L;
        return raw & 0xffff_ffffL;
    }

    private static boolean validProfile(int value) {
        return value == PROFILE_AUTO || (value >= 2 && value <= 10);
    }

    public static long receiverFrequency(long rf) { return BtReceivePlan.receiverFrequency(rf, BtCwController.getChannel()); }

    private static long clampFrequency(long value) {
        return Math.max(MIN_FREQUENCY, Math.min(MAX_FREQUENCY, value));
    }

    private static final class PllSelection {
        final int profile;
        final int multiplier;

        PllSelection(int profile, int multiplier) {
            this.profile = profile;
            this.multiplier = multiplier;
        }
    }

    /**
     * Selects only combinations covered by the measured lock ranges. The middle
     * range uses multiplier 20, with multiplier 40 below it and multiplier 10
     * above it. Adjacent profiles overlap, so every frequency between the two
     * directly measured absolute limits has a valid selection.
     */
    private static PllSelection automaticPll(long requestedFrequency) {
        long hz = clampFrequency(requestedFrequency);
        if (hz <=   307_900_000L) return new PllSelection(10, 40); // 4000
        if (hz <=   352_050_000L) return new PllSelection(9,  40); // 3500
        if (hz <    387_300_000L) return new PllSelection(8,  40); // 3000
        if (hz <    442_600_000L) return new PllSelection(10, 20); // 4000
        if (hz <    516_300_000L) return new PllSelection(9,  20); // 3500
        if (hz <    774_300_000L) return new PllSelection(8,  20); // 3000
        if (hz <    884_900_000L) return new PllSelection(7,  20); // 2000
        if (hz <  1_032_300_000L) return new PllSelection(6,  20); // 1750
        if (hz <  1_548_200_000L) return new PllSelection(5,  20); // 1500
        if (hz <= 2_457_500_000L) return new PllSelection(4,  20); // 1000
        if (hz <= 3_297_800_000L) return new PllSelection(5,  10); // 1500
        if (hz <  3_538_600_000L) return new PllSelection(4,  10); // 1000
        return new PllSelection(3, 10);                            // 875
    }

    private static String profileName(int profile) {
        switch (profile) {
            case 2: return "750";
            case 3: return "875";
            case 4: return "1000";
            case 5: return "1500";
            case 6: return "1750";
            case 7: return "2000";
            case 8: return "3000";
            case 9: return "3500";
            case 10: return "4000";
            default: return "Auto";
        }
    }

    private static long[] measuredRange(int profile, int multiplier) {
        long low;
        long high;
        switch (profile) {
            case 2: low = 2_063_900_000L; high = 2_457_500_000L; break;
            case 3: low = 1_769_300_000L; high = 2_457_500_000L; break;
            case 4: low = 1_548_200_000L; high = 2_457_500_000L; break;
            case 5: low = 1_032_300_000L; high = 1_648_900_000L; break;
            case 6: low =   884_900_000L; high = 1_412_900_000L; break;
            case 7: low =   774_300_000L; high = 1_233_700_000L; break;
            case 8: low =   516_300_000L; high =   821_800_000L; break;
            case 9: low =   442_600_000L; high =   704_100_000L; break;
            case 10: low =  387_300_000L; high =   615_800_000L; break;
            default: return null;
        }
        if (multiplier == 10) {
            low *= 2;
            high *= 2;
        } else if (multiplier == 40) {
            low /= 2;
            high /= 2;
        } else if (multiplier != 20) {
            return null;
        }
        // Direct 50/100 kHz walks refined the two absolute endpoints.
        if (profile == 10 && multiplier == 40) low = 193_650_000L;
        if (profile == 3 && multiplier == 10) high = MAX_FREQUENCY;
        return new long[] { low, high };
    }

    private static String mhz(long hz) {
        return String.format(Locale.US, "%.2f", hz / 1_000_000.0);
    }

    /** Same limits as tuner validation, not a separate documentation copy. */
    public static String[][] receptionProfiles() {
        String[][] rows = new String[9][4];
        for (int p = 2; p <= 10; p++) {
            rows[p-2][0] = profileName(p);
            int[] multipliers = {10, 20, 40};
            for (int m = 0; m < 3; m++) {
                long[] range = measuredRange(p, multipliers[m]);
                rows[p-2][m+1] = mhz(range[0]) + "–" + mhz(range[1]);
            }
        }
        return rows;
    }

    public static String describeAutomaticPll(long frequency) {
        PllSelection selection = automaticPll(frequency);
        return "Auto now: profile " + profileName(selection.profile) +
                " ×" + selection.multiplier;
    }

    public static String describeManualPll(int profile, int multiplier, long frequency) {
        long[] range = measuredRange(profile, multiplier);
        if (range == null) return "Select a measured manual profile";
        String text = "Manual profile " + profileName(profile) + " ×" + multiplier +
                ": " + mhz(range[0]) + "–" + mhz(range[1]) + " MHz";
        if (frequency < range[0] || frequency > range[1])
            text += " · current frequency is outside its measured lock range";
        return text;
    }

    private void error(String message) {
        LgRxRecovery.failed(this, message);
        if (bt != null) bt.stopAsync();
        Log.e(TAG, message);
        Callback cb = callback;
        if (cb != null) cb.onIQSourceError(this, message);
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format("%02x", b & 0xff));
        return out.toString();
    }

    private File installAsset(Context context, String name, String expectedSha) throws Exception {
        File directory = context.getDir("lg_modem", Context.MODE_PRIVATE);
        File temporary = new File(directory, name + ".new");
        File target = new File(directory, name);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = context.getAssets().open(name);
             FileOutputStream out = new FileOutputStream(temporary, false)) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = in.read(buffer)) != -1) {
                out.write(buffer, 0, count);
                digest.update(buffer, 0, count);
            }
            out.getFD().sync();
        }
        String actualSha = hex(digest.digest());
        if (!expectedSha.equals(actualSha)) {
            temporary.delete();
            throw new IOException(name + " is damaged (SHA-256 " + actualSha + ")");
        }
        if (target.exists() && !target.delete()) throw new IOException("cannot replace " + name);
        if (!temporary.renameTo(target)) throw new IOException("cannot install " + name);
        if (!target.setReadable(true, false) || !target.setExecutable(true, false))
            throw new IOException("cannot make " + name + " executable");
        return target;
    }

    private String runRoot(File executable) throws IOException, InterruptedException {
        return runRootCommand(executable.getAbsolutePath());
    }

    private String runRootCommand(String command) throws IOException, InterruptedException {
        Process p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
        p.getOutputStream().close();
        StringBuilder result = new StringBuilder();
        try (BufferedReader input = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = input.readLine()) != null && result.length() < 16384)
                result.append(line).append('\n');
        }
        int status = p.waitFor();
        if (status != 0) throw new IOException("root command rc=" + status + ": " + result);
        return result.toString();
    }

    @Override public boolean open(final Context context, final Callback cb) {
        callback = cb;
        iqTestPreferences = context.getSharedPreferences(IQ_TEST_PREFS, Context.MODE_PRIVATE);
        new Thread(() -> {
            try {
                bt = BtCwController.get(context);
                factory = installAsset(context, "lg_factory", FACTORY_SHA);
                online = installAsset(context, "lg_online", ONLINE_SHA);
                streamer = installAsset(context, "lg_stream", STREAM_SHA);
                opened = true;
                Log.i(TAG, "standalone continuous SMEM CI8 helper installed and verified in " + streamer.getParent()
                        + "; D3FQ and PLL will be verified by the modem handshake");
                cb.onIQSourceReady(this);
            } catch (Exception e) {
                opened = false;
                error("LG modem init: " + e.getMessage());
            }
        }, "LG-modem-open").start();
        return true;
    }

    @Override public boolean isOpen() { return opened; }

    @Override public synchronized boolean close() {
        stopSampling();
        if (factoryHeld) restoreOnline();
        try { runRootCommand("start mpdecision 2>/dev/null; true"); } catch (Exception ignored) {}
        opened = false;
        return true;
    }

    @Override public String getName() {
        return "LG D325 modem · B" + rfBand + " · " +
                (tuneMode == TUNE_MODE_UARFCN ? ("UARFCN " + uarfcn) : "exact Hz") +
                " · CI8 · PLL lock";
    }
    @Override public int getSampleRate() { return processingRate; }
    @Override public void setSampleRate(int rate) {
        if (sampling) return;
        processingRate = (rate == 2560000 || rate == 1280000) ? rate : SAMPLE_RATE;
    }
    @Override public long getFrequency() { return frequency; }

    /**
     * Recording mode trades display latency for continuity.  In this mode the stdout
     * drain applies back-pressure instead of dropping the oldest Java live packet.
     */
    public void setLosslessConsumerMode(boolean enabled) {
        losslessConsumerMode = enabled;
        Log.i(TAG, "lossless consumer mode=" + enabled + " ready=" + readyPackets.size());
    }

    public void setDemodulationContinuityMode(boolean enabled) {
        demodulationContinuityMode = enabled;
        Log.i(TAG, "demodulation continuity mode=" + enabled + " ready=" + readyPackets.size());
    }

    private boolean consumerDiscontinuity;
    private boolean liveWindowInProgress;
    public void setLiveWindowInProgress(boolean value) { liveWindowInProgress = value; }
    public boolean consumeDiscontinuity() {
        boolean value = consumerDiscontinuity;
        consumerDiscontinuity = false;
        return value;
    }

    private boolean requiresContinuousDelivery() {
        return losslessConsumerMode || demodulationContinuityMode;
    }

    @Override public synchronized void setFrequency(long next) {
        if (tuneMode == TUNE_MODE_UARFCN) {
            frequency = frequencyForUarfcn(rfBand, uarfcn);
            return;
        }
        long bounded = clampFrequency(next);
        frequency = bounded;
        if (sampling && control != null) scheduleRetune(bounded);
    }

    public synchronized void setTuneMode(int value) {
        tuneMode = TUNE_MODE_EXACT;
        if (tuneMode == TUNE_MODE_UARFCN) frequency = frequencyForUarfcn(rfBand, uarfcn);
        if (sampling && control != null) scheduleRetune(frequency);
    }

    public synchronized void setUarfcn(int value) {
        uarfcn = clamp(value, UARFCN_MIN, UARFCN_MAX);
        if (tuneMode == TUNE_MODE_UARFCN) {
            frequency = frequencyForUarfcn(rfBand, uarfcn);
            if (sampling && control != null) scheduleRetune(frequency);
        }
    }

    public synchronized void setProfileId(int value) {
        if (!validProfile(value)) return;
        profileId = value;
        if (tuneMode == TUNE_MODE_EXACT && sampling && control != null) scheduleRetune(frequency);
    }

    public synchronized void setRfBand(int value) {
        if (!validRfBand(value)) return;
        rfBand = value;
        if (tuneMode == TUNE_MODE_UARFCN) frequency = frequencyForUarfcn(rfBand, uarfcn);
        if (sampling && control != null) scheduleRetune(frequency);
    }

    public synchronized void setPllMultiplier(int value) {
        if (!validMultiplier(value)) return;
        pllMultiplier = value;
        if (tuneMode == TUNE_MODE_EXACT && sampling && control != null) scheduleRetune(frequency);
    }

    public synchronized void setLnaState(int value) {
        lnaState = clamp(value, 0, 5);
        if (sampling && control != null) scheduleRetune(frequency);
    }

    public synchronized void setDvgaOffset(int value) {
        dvgaOffset = clamp(value, -512, 511);
        if (sampling && control != null) scheduleRetune(frequency);
    }

    /** Keep drag-to-tune responsive without queuing every MotionEvent in the modem. */
    private void scheduleRetune(long next) {
        final long session;
        synchronized (retuneLock) {
            pendingRetune = next;
            session = retuneSession;
            if (retuneWorkerRunning) return;
            retuneWorkerRunning = true;
        }
        new Thread(() -> drainRetunes(session), "LG-modem-retune").start();
    }

    private void drainRetunes(long session) {
        while (true) {
            final long next;
            final OutputStream output;
            synchronized (retuneLock) {
                if (session != retuneSession || !sampling || pendingRetune < 1 || control == null) {
                    if (session == retuneSession) retuneWorkerRunning = false;
                    return;
                }
                next = pendingRetune;
                pendingRetune = -1;
                output = control;
            }
            try {
                synchronized (retuneLock) {
                    if (session != retuneSession || !sampling) return;
                    synchronized (output) {
                        writeConfig(output, next);
                        output.flush();
                    }
                }
                Log.i(TAG, tuneMode == TUNE_MODE_UARFCN
                        ? ("retune UARFCN B" + rfBand + "/" + uarfcn + " -> " + frequency + " Hz")
                        : ("retune exact_hz=" + next));
            } catch (IOException e) {
                if (sampling) error("retune failed: " + e.getMessage());
                synchronized (retuneLock) {
                    if (session == retuneSession) retuneWorkerRunning = false;
                }
                return;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                synchronized (retuneLock) {
                    if (session == retuneSession) retuneWorkerRunning = false;
                }
                return;
            }
        }
    }

    @Override public long getMaxFrequency() { return MAX_FREQUENCY; }
    @Override public long getMinFrequency() { return MIN_FREQUENCY; }
    @Override public int getMaxSampleRate() { return SAMPLE_RATE; }
    @Override public int getMinSampleRate() { return 1280000; }
    @Override public int getNextHigherOptimalSampleRate(int ignored) { return SAMPLE_RATE; }
    @Override public int getNextLowerOptimalSampleRate(int ignored) { return SAMPLE_RATE; }
    @Override public int[] getSupportedSampleRates() { return new int[] {1280000, 2560000, SAMPLE_RATE}; }
    @Override public int getPacketSize() { return PACKET_BYTES; }
    @Override public int getBytesPerSample() { return 2; }

    private static void writeLong(OutputStream output, long value) throws IOException {
        for (int byteIndex = 0; byteIndex < 8; byteIndex++)
            output.write((int) (value >>> (8 * byteIndex)) & 0xff);
    }

    private static void writeShort(OutputStream output, int value) throws IOException {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
    }

    private static void writeInt(OutputStream output, int value) throws IOException {
        for (int byteIndex = 0; byteIndex < 4; byteIndex++)
            output.write((value >>> (8 * byteIndex)) & 0xff);
    }

    private void writeConfig(OutputStream output, long nextFrequency) throws IOException {
        final boolean useUarfcn = tuneMode == TUNE_MODE_UARFCN;
        long configuredFrequency = receiverFrequency(nextFrequency);
        if (bt != null) bt.configure(nextFrequency < BtRxLimit.activeHz);
        int selectedProfile = profileId;
        int selectedMultiplier = pllMultiplier;
        if (BtReceivePlan.force1000x20(nextFrequency, configuredFrequency)) {
            selectedProfile = 4;
            selectedMultiplier = 20;
        } else if (!useUarfcn && selectedProfile == PROFILE_AUTO) {
            PllSelection automatic = automaticPll(configuredFrequency);
            selectedProfile = automatic.profile;
            selectedMultiplier = automatic.multiplier;
        }
        // LFC2 = the old 20-byte LFC1 record plus forced-UARFCN and mode.  Direct
        // mode retains all existing profile/multiplier semantics.
        writeLong(output, configuredFrequency);
        writeInt(output, 0x3243464c); // "LFC2"
        output.write(selectedProfile);
        output.write(selectedMultiplier);
        output.write(lnaState);
        output.write(rfBand);
        writeShort(output, dvgaOffset);
        writeShort(output, 0x4c47);
        writeShort(output, uarfcn);
        output.write(useUarfcn ? TUNE_MODE_UARFCN : TUNE_MODE_EXACT);
        output.write(0);
        if (useUarfcn) {
            Log.i(TAG, "config forced_uarfcn=B" + rfBand + "/" + uarfcn +
                    " expected_hz=" + configuredFrequency + " lna=" + lnaState + " dvga=" + dvgaOffset);
        } else {
            Log.i(TAG, "config exact_hz=" + configuredFrequency + " profile=" +
                    profileName(selectedProfile) + " multiplier=" + selectedMultiplier +
                    " band=B" + rfBand + " lna=" + lnaState + " dvga=" + dvgaOffset);
        }
    }

    private void recyclePacket(byte[] packet) {
        if (packet != null && packet.length == PACKET_BYTES) packetPool.offer(packet);
    }

    private void clearReadyPackets() {
        byte[] packet;
        while ((packet = readyPackets.poll()) != null) recyclePacket(packet);
    }

    /**
     * Dedicated stdout drain.  This thread does no FFT, demodulation or drawing.
     * Its only job is to keep the native 15.36 MB/s pipe moving.  If the rest of
     * the app is late, stale live packets are dropped here instead of allowing
     * the modem/native stage ring to fill and kill the capture.
     */
    private void pumpStream() {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY);
        } catch (Throwable ignored) { }
        InputStream input = reader;
        byte[] packet = null;
        try {
            while (sampling && input != null) {
                packet = packetPool.poll();
                if (packet == null) packet = new byte[PACKET_BYTES];
                int offset = 0;
                while (sampling && offset < packet.length) {
                    int count = input.read(packet, offset, packet.length - offset);
                    if (count < 0) break;
                    if (count == 0) continue;
                    offset += count;
                }
                if (!sampling) break;
                if (offset != packet.length) break;

                if (++blocks == 1) LgRxRecovery.received(this);
                if (bt != null) bt.packetReceived();
                if (blocks == 1 || blocks % 500 == 0)
                    Log.i(TAG, "continuous CI8 packets=" + blocks + " live_dropped=" + livePacketsDropped +
                            " ready=" + readyPackets.size() + " audio_continuity=" + demodulationContinuityMode);

                if (requiresContinuousDelivery()) {
                    // Recording and demodulation both represent continuous time.  Packet
                    // replacement is fine for a visual spectrum but becomes a phase jump
                    // (click / mechanical buzz) in FM audio.  Back-pressure here and let
                    // the native stage ring absorb short Android scheduling stalls.
                    boolean delivered = false;
                    while (sampling && requiresContinuousDelivery()) {
                        if (readyPackets.offer(packet, 250, TimeUnit.MILLISECONDS)) {
                            delivered = true;
                            break;
                        }
                    }
                    // Ownership is decided by offer(), never by a later mode read.
                    // A mode switch after enqueue must not enqueue the same array twice.
                    if (delivered) {
                        packet = null;
                        continue;
                    }
                    if (!sampling) break;
                    // Mode may have been disabled while waiting; fall through to the
                    // low-latency latest-packet-wins visual path.
                }

                // Normal live view: never block the native stdout drain on UI/DSP.
                while (!readyPackets.offer(packet)) {
                    byte[] stale = readyPackets.poll();
                    if (stale == null) break;
                    recyclePacket(stale);
                    livePacketsDropped++;
                }
                packet = null; // ownership transferred to readyPackets
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (sampling) Log.w(TAG, "LG stream pump interrupted", e);
        } catch (IOException e) {
            if (sampling) Log.e(TAG, "LG stream pump read failed", e);
        } finally {
            recyclePacket(packet);
            if (input != reader) return;
            streamPumpEnded = true;
            if (sampling) {
                Process ended = process;
                int status = -1;
                if (ended != null) {
                    try { status = ended.exitValue(); }
                    catch (IllegalThreadStateException ignored) { }
                }
                error("LG modem stream ended (exit code " + status + ")");
            }
        }
    }

    @Override public synchronized void startSampling() {
        extraConjugation = iqTestPreferences == null || iqTestPreferences.getBoolean(IQ_TEST_EXTRA, true);
        releaseBandwidth();
        resetNativeDemodState();
        if (sampling || !opened) return;
        BtRxLimit.reload();
        blocks = 0; // A failed new handshake must not inherit healthy-session counters.
        try {
            LgRxRecovery.beforeStart(this, online, factory);
            if (!factoryHeld) {
                String reply = runRoot(factory);
                if (!reply.contains("response=0204000000000001010002"))
                    throw new IOException("FACTORY mode was not confirmed");
                factoryHeld = true;
            }
            runRootCommand("stop mpdecision 2>/dev/null; echo 1 > /sys/devices/system/cpu/cpu1/online 2>/dev/null; setenforce 0");
            process = new ProcessBuilder("su", "-c", streamer.getAbsolutePath())
                    .redirectErrorStream(false).start();
            control = process.getOutputStream();
            reader = new BufferedInputStream(process.getInputStream(), 262144);
            statusReader = new BufferedReader(new InputStreamReader(process.getErrorStream()), 8192);
            writeConfig(control, frequency);
            control.flush();
            blocks = 0;
            changedBlocks = 0;
            previousBlock = null;
            mixerPhase = 0.0;
            livePacketsDropped = 0;
            // Modes are configured before Scheduler.startSampling(). Preserve them:
            // resetting here silently re-enabled the display-only 32 -> 8 trim policy.
            Log.i(TAG, "capture policy: audio_continuity=" + demodulationContinuityMode +
                    " lossless=" + losslessConsumerMode);
            streamPumpEnded = false;
            clearReadyPackets();
            sampling = true;
            statusThread = new Thread(this::readStatus, "LG-modem-status");
            statusThread.start();
            streamPumpThread = new Thread(this::pumpStream, "LG-modem-CI8-drain");
            streamPumpThread.start();
            synchronized (retuneLock) {
                pendingRetune = -1;
                retuneSession++;
                retuneWorkerRunning = false;
            }
        } catch (Exception e) {
            sampling = false;
            error("cannot start LG capture: " + e.getMessage());
            restoreOnline();
            try { runRootCommand("start mpdecision 2>/dev/null; setenforce 1"); }
            catch (Exception restoreError) { Log.e(TAG, "cannot restore SELinux enforcing", restoreError); }
        }
    }

    @Override public synchronized void stopSampling() {
        boolean hadSession = sampling || process != null || control != null;
        sampling = false;
        losslessConsumerMode = false;
        demodulationContinuityMode = false;
        if (!hadSession) { if (bt != null) bt.stop(); return; }
        synchronized (retuneLock) {
            pendingRetune = -1;
            retuneSession++;
            retuneWorkerRunning = false;
        }
        if (bt != null) bt.stop();
        if (control != null) try { control.close(); } catch (IOException ignored) { }
        if (process != null) {
            long until = android.os.SystemClock.elapsedRealtime() + 2000;
            while (android.os.SystemClock.elapsedRealtime() < until) {
                try { process.exitValue(); break; }
                catch (IllegalThreadStateException e) {
                    try { Thread.sleep(20); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); break; }
                }
            }
            process.destroy();
        }
        Thread pump = streamPumpThread;
        streamPumpThread = null;
        if (pump != null && pump != Thread.currentThread()) {
            try { pump.join(1000); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        clearReadyPackets();
        control = null;
        reader = null;
        statusReader = null;
        process = null;
        Log.i(TAG, "capture stopped blocks=" + blocks + " live_dropped=" + livePacketsDropped +
                " changed_after_first=" + changedBlocks);
        // Play/Stop ends RX only. Keep the confirmed FTM session until close().
        try { runRootCommand("start mpdecision 2>/dev/null; setenforce 1"); }
        catch (Exception e) { Log.e(TAG, "cannot restore SELinux enforcing", e); }
    }

    private void restoreOnline() {
        if (bt != null) bt.stop();
        if (online == null) return;
        String lastReply = "";
        Exception lastError = null;
        for (int attempt = 1; attempt <= 5; attempt++) {
            try {
                lastReply = runRoot(online);
                if (lastReply.contains("response=0204000000000001010000")) {
                    factoryHeld = false;
                    Log.i(TAG, "ONLINE mode confirmed on attempt " + attempt);
                    return;
                }
            } catch (Exception e) {
                lastError = e;
            }
            if (attempt < 5) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    Log.e(TAG, "ONLINE restore interrupted", e);
                    return;
                }
            }
        }
        if (lastError != null) Log.e(TAG, "ONLINE restore failed after 5 attempts", lastError);
        else Log.e(TAG, "ONLINE mode was not confirmed after 5 attempts: " + lastReply);
    }

    private void readStatus() {
        BufferedReader input = statusReader;
        if (input == null) return;
        try {
            String line;
            while ((line = input.readLine()) != null) {
                if (input != statusReader) return;
                Log.i(TAG, "native: " + line);
                if ("STAGE_ERROR 54".equals(line) && sampling)
                    error("LG SMEM producer stopped (ring overrun or invalid geometry); restart RX");
                if (line.startsWith("READY ")) {
                    String[] fields = line.split(" ");
                    if (fields.length >= 6) {
                        long low = Long.parseLong(fields[1]);
                        long high = Long.parseLong(fields[2]);
                        long physicalFrequency = (high << 32) | (low & 0xffffffffL);
                        int pllStatus = Integer.parseInt(fields[4]);
                        if (pllStatus != 2 && sampling)
                            Log.w(TAG, "RX PLL did not lock at " + frequency + " Hz");
                    }
                }
                if ((line.startsWith("DROPPED ") || line.startsWith("MISSED ") ||
                        line.startsWith("DROPPED_LIVE ") || line.startsWith("MISSED_LIVE ")) &&
                        !line.endsWith(" 0") && sampling)
                    Log.w(TAG, "modem CI8 continuity warning: " + line);
            }
        } catch (Exception e) {
            if (sampling && input == statusReader) error("LG modem status: " + e.getMessage());
        }
    }

    @Override public byte[] getPacket(int timeout) {
        if (!sampling && readyPackets.isEmpty()) return null;
        // Scheduler treats null as end-of-source.  A single 1 s scheduling/retune
        // hiccup must therefore not tear the whole LG session down.  Keep waiting
        // for up to five seconds while the dedicated drain thread is still alive;
        // real EOF/pump failure is still reported immediately.
        final long waitBudgetMs = Math.max(4_000L, Math.max(1, timeout));
        final long deadline = android.os.SystemClock.elapsedRealtime() + waitBudgetMs;
        try {
            // Do not spend seconds filtering stale IQ or permanently disable FFT.
            // Recording remains lossless. Live overload discards old queued blocks.
            // Audio needs continuity, unlike display-only IQ. Absorb short CPU stalls
            // instead of cutting the audio timeline whenever 68 ms accumulates.
            int trimAt = demodulationContinuityMode ? 384 : 32;
            int keep = demodulationContinuityMode ? 256 : 8;
            if (!requiresContinuousDelivery() && !liveWindowInProgress && readyPackets.size() > trimAt) {
                while (readyPackets.size() > keep) {
                    byte[] old = readyPackets.poll();
                    if (old == null) break;
                    packetPool.offer(old);
                    livePacketsDropped++;
                }
                releaseBandwidth();
                resetNativeDemodState();
                consumerDiscontinuity = true;
            }
            while (sampling && !streamPumpEnded) {
                long remaining = deadline - android.os.SystemClock.elapsedRealtime();
                if (remaining <= 0) {
                    Log.e(TAG, "LG CI8 stream starved for " + waitBudgetMs + " ms while pump remained alive");
                    error("LG CI8 stream stalled; reconnecting RX");
                    return null;
                }
                byte[] packet = readyPackets.poll(Math.min(250L, remaining), TimeUnit.MILLISECONDS);
                if (packet != null) return packet;
            }
            return readyPackets.poll();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    @Override public void returnPacket(byte[] packet) {
        recyclePacket(packet);
    }

    /** Emergency reset for a PLL error that survives returning the modem online. */
    public static String fullModemReset() throws IOException, InterruptedException {
        Process reset = new ProcessBuilder("su", "-c", "echo reset > /dev/reset_modem")
                .redirectErrorStream(true).start();
        reset.getOutputStream().close();
        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(reset.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) output.append(line).append('\n');
        }
        int status = reset.waitFor();
        if (status != 0) throw new IOException("modem reset rc=" + status + ": " + output);
        return output.toString();
    }

    private int convert(byte[] packet, SamplePacket target, long channel, boolean mix) {
        int count = Math.min(packet.length / 2, target.capacity() - target.size());
        int start = target.size();
        float[] real = target.re();
        float[] imaginary = target.im();
        double step = mix ? 2.0 * Math.PI * (frequency - channel) / SAMPLE_RATE : 0.0;
        double phase = mix ? mixerPhase : 0.0;
        for (int i = 0; i < count; i++) {
            int offset = i * 2;
            float ir = packet[offset] / 128.0f;
            float qr = packet[offset + 1] / 128.0f * (conjugateIq(frequency) ? -1f : 1f);
            if (mix) {
                double angle = phase + step * i;
                double cosine = Math.cos(angle);
                double sine = Math.sin(angle);
                real[start + i] = (float) (ir * cosine - qr * sine);
                imaginary[start + i] = (float) (qr * cosine + ir * sine);
            } else {
                real[start + i] = ir;
                imaginary[start + i] = qr;
            }
        }
        if (mix) {
            mixerPhase = (phase + step * count) % (2.0 * Math.PI);
            if (mixerPhase < 0.0) mixerPhase += 2.0 * Math.PI;
        }
        target.setSize(start + count);
        target.setSampleRate(SAMPLE_RATE);
        target.setFrequency(mix ? channel : frequency);
        return count;
    }

    @Override public int fillPacketIntoSamplePacket(byte[] packet, SamplePacket target) {
        final int start = target.size();
        if (processingRate != SAMPLE_RATE) {
            int count = Math.min(bandwidthCount, target.capacity() - start);
            System.arraycopy(bandwidthRe, 0, target.re(), start, count);
            System.arraycopy(bandwidthIm, 0, target.im(), start, count);
            target.setSize(start + count); target.setSampleRate(processingRate); target.setFrequency(frequency);
            return count;
        }
        final int count = conjugateIq(frequency)
                ? nativeDsp.unpackCi8Conjugated(packet, target.re(), target.im(), start)
                : nativeDsp.unpackCi8(packet, target.re(), target.im(), start);
        target.setSize(start + count);
        target.setSampleRate(SAMPLE_RATE);
        target.setFrequency(frequency);
        return count;
    }

    private void resetNativeDemodState() {
        demodNativeState[0] = 1f;
        demodNativeState[1] = 0f;
        demodNativeState[2] = 0f;
        demodNativeState[3] = 0f;
        demodNativeState[4] = 0f;
    }

    @Override public int mixPacketIntoSamplePacket(byte[] packet, SamplePacket target, long channel) {
        // The demodulation marker is independent of the RF tuner. Translate the selected
        // channel to DC at the full 7.68 MSps rate in native code, then anti-alias and
        // decimate by 30.  This preserves the whole spectrum view and avoids the aliasing
        // produced by the old 1-in-30 Java picker.
        final long sourceFrequency = frequency;
        if (channel != demodChannel || sourceFrequency != demodSourceFrequency) {
            demodChannel = channel;
            demodSourceFrequency = sourceFrequency;
            resetNativeDemodState();
            target.setSize(0);
        }

        final int start = target.size();
        final int room = target.capacity() - start;
        if (room <= 0) return 0;
        if (processingRate != SAMPLE_RATE) {
            final int produced = nativeDsp.mixBandwidth(bandwidthRe, bandwidthIm, bandwidthCount,
                    target.re(), target.im(), start, (double)(sourceFrequency - channel), processingRate, demodNativeState);
            target.setSize(start + produced); target.setSampleRate(256_000); target.setFrequency(channel);
            return produced;
        }
        final boolean conjugated = conjugateIq(sourceFrequency);
        final int produced = nativeDsp.mixDownsampleCi8Boxcar30(
                packet, target.re(), target.im(), start, room,
                (double)(conjugated ? channel - sourceFrequency : sourceFrequency - channel), (double)SAMPLE_RATE, demodNativeState);
        if (conjugated) nativeDsp.conjugate(target.im(), start, produced);
        target.setSize(start + produced);
        target.setSampleRate(256_000);
        target.setFrequency(channel);
        return produced;
    }

}
