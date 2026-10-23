package com.mantz_it.rfanalyzer.dsp;

/** Bounded SPSC display mailbox. Rows are screen-width, not FFT-width. */
public final class DisplayFrameQueue {
    private static final int CAPACITY = 6;
    private float[][] rows = new float[0][];
    private final long[] times = new long[CAPACITY];
    private int head, count, width;
    private long epoch;
    private long dropped;
    public synchronized void configure(int newWidth, long newEpoch) {
        if (width != newWidth) { width = newWidth; rows = new float[CAPACITY][width]; }
        head = count = 0; epoch = newEpoch;
    }
    public synchronized void offer(float[] row, long timestampNs, long rowEpoch) {
        if (rowEpoch != epoch || row.length != width) return;
        if (count == CAPACITY) { head = (head + 1) % CAPACITY; count--; dropped++; }
        int tail = (head + count) % CAPACITY;
        System.arraycopy(row, 0, rows[tail], 0, width);
        times[tail] = timestampNs;
        count++;
    }
    /** Copies a complete batch to reusable renderer-owned buffers; no references escape. */
    public synchronized int drain(float[][] target, long[] timestamps, long expectedEpoch) {
        if (expectedEpoch != epoch) return 0;
        int n = Math.min(count, target.length);
        for (int i=0; i<n; i++) {
            int at = (head + i) % CAPACITY;
            System.arraycopy(rows[at], 0, target[i], 0, width);
            timestamps[i] = times[at];
        }
        head = (head + n) % CAPACITY; count -= n;
        return n;
    }
    public synchronized long droppedFrames() { return dropped; }
}
