// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
package com.mantz_it.rfanalyzer.automation;

/** Monotonic-time rules; independent of Android and wall-clock changes. */
public final class CapturePolicy {
    public static final long FAILURE_MS = 300_000;
    public static final long COOLING_MS = 600_000;
    private long lastPacketMs;
    private boolean received;
    public CapturePolicy(long startMs) { lastPacketMs = startMs; }
    public void packet(long nowMs) { received = true; lastPacketMs = nowMs; }
    public boolean hasReceived() { return received; }
    public long lastPacketMs() { return lastPacketMs; }
    public boolean failed(long nowMs) { return nowMs - lastPacketMs >= FAILURE_MS; }
    /** NaN means unavailable. A missing previously observed sensor cannot count as cooled. */
    public static int cooling(double[] baseline, double[] current, long elapsedMs) {
        int available = 0; boolean cooled = true;
        for (int i=0;i<baseline.length;i++) {
            if (Double.isFinite(current[i])) {
                available++;
                if (current[i] >= 70) return 2; // power off immediately
            }
            if (Double.isFinite(baseline[i]) &&
                (!Double.isFinite(current[i]) || current[i] > baseline[i]-3)) cooled=false;
        }
        if (available==0) return 3; // reboot; never restore the task after boot
        if (cooled) return 1; // disarm permanently
        return elapsedMs >= COOLING_MS ? 2 : 0;
    }
    public static boolean overlaps(long aStart,long aEnd,long bStart,long bEnd) {
        return aStart < bEnd && bStart < aEnd;
    }
}
