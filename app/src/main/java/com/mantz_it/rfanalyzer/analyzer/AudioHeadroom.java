package com.mantz_it.rfanalyzer.analyzer;

/** Audio owns the clock. FFT yields CPU when the real playback reserve is low. */
public final class AudioHeadroom {
    private static volatile AudioHeadroom current;
    private final int sampleRate;
    private long written, previousHead, wrap;
    private volatile long updatedNs;
    private volatile boolean constrained = true;

    public AudioHeadroom(int rate) { sampleRate = rate; }
    public void begin() { updatedNs = System.nanoTime(); current = this; }
    public void wrote(int frames) { written += frames; }
    public void update(int rawHead) {
        long head = rawHead & 0xffffffffL;
        if (head < previousHead) wrap += 0x100000000L;
        previousHead = head;
        long reserve = Math.max(0L, written - (wrap + head));
        long millis = reserve * 1000 / sampleRate;
        // Hysteresis avoids switching the FFT budget at every audio packet.
        if (millis < 60) constrained = true;
        else if (millis >= 140) constrained = false;
        updatedNs = System.nanoTime();
    }
    public void end() { if (current == this) current = null; }
    public static double fftCpuBudget() {
        AudioHeadroom state = current;
        return state == null || System.nanoTime() - state.updatedNs > 1_000_000_000L || state.constrained ? 0.08 : 0.35;
    }
}
