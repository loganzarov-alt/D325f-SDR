// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
package com.mantz_it.rfanalyzer.source;

import android.content.Context;
import android.bluetooth.BluetoothAdapter;
import android.os.Build;
import android.os.SystemClock;
import java.io.*;
import java.util.concurrent.TimeUnit;

/** One root guardian per RX session; no periodic su invocations. */
public final class BtCwController {
    private static BtCwController instance;
    public static synchronized BtCwController get(Context context) throws IOException {
        if (instance == null) instance = new BtCwController(context.getApplicationContext());
        return instance;
    }
    // Android 7/API 25 lacks the newer Process timeout/liveness methods.
    private static boolean alive(Process p) {
        try { p.exitValue(); return false; } catch (IllegalThreadStateException e) { return true; }
    }
    private static boolean waitUntilExit(Process p, long timeoutMs) throws InterruptedException {
        long end = SystemClock.elapsedRealtime() + timeoutMs;
        while (alive(p)) {
            if (SystemClock.elapsedRealtime() >= end) return false;
            Thread.sleep(20);
        }
        return true;
    }
    private final File executable;
    private final File channelFile;
    private final File powerFile;
    private static volatile int channel = 30;
    public static int getChannel() { return channel; }
    public synchronized void setChannel(int next) throws IOException {
        if (next < 0 || next > 78) throw new IOException("Канал должен быть от 0 до 78");
        if (requested) throw new IOException("Остановите приём перед изменением BT-канала");
        try (FileOutputStream out = new FileOutputStream(channelFile)) { out.write(next); }
        channel = next;
    }
    private volatile Process guardian;
    private volatile long lastPacket;
    private volatile boolean hasPackets;
    private long startedAt;
    private volatile int power = 9;
    private volatile String status = "TX idle";
    private boolean requested;
    private BtCwController(Context context) throws IOException {
        BtRxLimit.init(context);
        executable = new File(context.getDir("lg_modem", 0), "btcw-guard");
        channelFile = new File(executable.getParentFile(), "bt-channel-v124");
        if (channelFile.isFile()) {
            try (FileInputStream in = new FileInputStream(channelFile)) {
                int saved = in.read(); if (saved >= 0 && saved <= 78) channel = saved;
            }
        }
        powerFile = new File(executable.getParentFile(), "bt-power");
        if (powerFile.isFile()) {
            try (FileInputStream in = new FileInputStream(powerFile)) {
                int saved = in.read(); if (saved >= 0 && saved <= 9) power = saved;
            }
        }
        File temporary = new File(executable.getParentFile(), "btcw-guard.new");
        try (InputStream in = context.getAssets().open("btcw-guard"); FileOutputStream out = new FileOutputStream(temporary)) {
            byte[] data = new byte[8192]; int n;
            while ((n = in.read(data)) > 0) out.write(data, 0, n);
        }
        if (!temporary.renameTo(executable)) throw new IOException("Cannot replace BT guardian");
        if (!executable.setExecutable(true, false)) throw new IOException("Cannot install BT guardian");
    }
    public int getPower() { return power; }
    public synchronized void setPower(int next) throws IOException {
        int value = Math.max(0, Math.min(9, next));
        try (FileOutputStream out = new FileOutputStream(powerFile)) { out.write(value); out.getFD().sync(); }
        if (power == value) return;
        power = value;
        if (requested) { stopProcess(); startProcess(); }
    }
    public void packetReceived() { lastPacket = SystemClock.elapsedRealtime(); hasPackets = true; }
    public synchronized void configure(boolean enabled) throws IOException {
        if (!enabled) { stop(); return; }
        if (requested && guardian != null) {
            if (!alive(guardian)) throw new IOException("BT guardian stopped; restart RX. " + status);
            return;
        }
        requested = true;
        try { startProcess(); } catch (IOException e) { requested = false; throw e; }
    }
    private void startProcess() throws IOException {
        // BT Android stack must relinquish the SMD transport before CW starts.
        if (Build.VERSION.SDK_INT <= 30) {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter != null && adapter.getState() != BluetoothAdapter.STATE_OFF) {
                adapter.disable();
                long until = SystemClock.elapsedRealtime() + 6000;
                try {
                    while (adapter.getState() != BluetoothAdapter.STATE_OFF && SystemClock.elapsedRealtime() < until)
                        Thread.sleep(100);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException(e); }
                if (adapter.getState() != BluetoothAdapter.STATE_OFF)
                    throw new IOException("Turn off system Bluetooth before starting BT CW");
            }
        } else {
            throw new IOException("LG BT CW requires the supported Android 7–11 Bluetooth control path");
        }
        startedAt = SystemClock.elapsedRealtime();
        hasPackets = false;
        lastPacket = startedAt;
        final Process p = new ProcessBuilder("su", "-c", "exec " + executable.getAbsolutePath() + " guard " + channel + " " + power + " 0")
                .redirectErrorStream(true).start();
        guardian = p;
        status = "TX starting · level " + power;
        final java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicBoolean acknowledged = new java.util.concurrent.atomic.AtomicBoolean(false);
        new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line; while ((line = reader.readLine()) != null) {
                    status = line;
                    if (line.startsWith("TX RUNNING")) { acknowledged.set(true); ready.countDown(); }
                }
            } catch (IOException e) { status = "TX status: " + e.getMessage(); }
            finally { ready.countDown(); }
        }, "BT-status").start();
        new Thread(() -> {
            try {
                while (alive(p)) {
                    if (SystemClock.elapsedRealtime() - (hasPackets ? lastPacket : startedAt) < (hasPackets ? 5000 : 15000)) {
                        p.getOutputStream().write('H'); p.getOutputStream().flush();
                    }
                    Thread.sleep(300);
                }
            } catch (Exception ignored) { }
        }, "BT-RX-watch").start();
        try {
            if (!ready.await(3, TimeUnit.SECONDS) || !acknowledged.get()) {
                stopProcess(); throw new IOException("BT TX not started: " + status);
            }
        } catch (InterruptedException e) { stopProcess(); Thread.currentThread().interrupt(); throw new IOException(e); }
    }
    private void stopProcess() {
        Process p = guardian; guardian = null;
        if (p == null) return;
        try { p.getOutputStream().close(); } catch (IOException ignored) { }
        try {
            if (!waitUntilExit(p, 4000)) status = "TX stop unconfirmed; query status";
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    public synchronized void stop() { requested = false; stopProcess(); }
    public void stopAsync() {
        final Process expected = guardian;
        new Thread(() -> { synchronized (this) { if (guardian == expected) stop(); } }, "BT-stop").start();
    }
    public String queryStatus() throws IOException, InterruptedException {
        Process p = new ProcessBuilder("su", "-c", executable.getAbsolutePath() + " status").redirectErrorStream(true).start();
        if (!waitUntilExit(p, 3000)) { p.destroy(); throw new IOException("BT status timeout"); }
        StringBuilder text = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line; while ((line = r.readLine()) != null) text.append(line).append('\n');
        }
        return text.toString();
    }
}
