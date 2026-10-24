// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
package com.mantz_it.rfanalyzer.dsp;

/** Allocation-free pixel reduction. Average POWER, not logarithms or maxima. */
public final class SpectrumRaster {
    private SpectrumRaster() { }
    private static final double[] POWER = new double[801];
    static {
        for (int i = 0; i < POWER.length; i++) POWER[i] = Math.pow(10.0, (-160.0 + i * 0.25) / 10.0);
    }
    private static double power(float db) {
        if (!Float.isFinite(db)) return 1e-16;
        double at = Math.max(0.0, Math.min(800.0, (db + 160.0) * 4.0));
        int lo = (int) at;
        int hi = Math.min(800, lo + 1);
        return POWER[lo] + (at - lo) * (POWER[hi] - POWER[lo]);
    }
    public static void rasterize(float[] bins, double firstBin, double binsPerPixel, float[] output) {
        if (bins.length == 0 || !Double.isFinite(firstBin) || !Double.isFinite(binsPerPixel) || binsPerPixel <= 0) {
            java.util.Arrays.fill(output, -160f);
            return;
        }
        for (int x = 0; x < output.length; x++) {
            double left = firstBin + x * binsPerPixel;
            double right = left + binsPerPixel;
            double a = Math.max(0, left), b = Math.min(bins.length, right);
            if (b <= a) { output[x] = -160f; continue; }
            double sum = 0;
            // Exact fractional-bin overlap prevents moving/zooming the viewport
            // from changing the detector just because a bin crosses a pixel edge.
            for (int bin = (int) a; bin < b; bin++) {
                double weight = Math.min(b, bin + 1.0) - Math.max(a, bin);
                sum += weight * power(bins[bin]);
            }
            output[x] = (float) (10.0 * Math.log10(Math.max(1e-16, sum / (b - a))));
        }
    }
}
