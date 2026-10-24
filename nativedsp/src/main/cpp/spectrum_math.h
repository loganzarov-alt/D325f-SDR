// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
#pragma once
#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <cstring>

namespace spectrum {
// Interpolated mantissa table. Maximum interpolation error < 0.000002 dB;
// float rounding dominates. Retains full FFT length, window and calibration.
inline float powerDb(float power) {
    static const auto logMantissa = [] {
        std::array<float, 1025> table{};
        for (int i=0; i<=1024; ++i) table[i] = float(10.0*std::log10(1.0+i/1024.0));
        return table;
    }();
    if (!(power > 1e-16f)) power = 1e-16f;
    if (!std::isfinite(power)) return 10.0f*std::log10(power);
    uint32_t bits; std::memcpy(&bits, &power, sizeof(bits));
    const int exponent = int((bits >> 23) & 255) - 127;
    const uint32_t mantissa = bits & 0x7fffff;
    const int index = int(mantissa >> 13);
    const float fraction = float(mantissa & 8191) * (1.0f/8192.0f);
    return exponent*3.010299956639812f + logMantissa[index]
        + fraction*(logMantissa[index+1]-logMantissa[index]);
}

// Match the Java detector, including its 0.25 dB power interpolation,
// fractional edges, finite checks and -160 dB floor. No max/peak substitution.
inline const std::array<float, 801>& powerTable() {
    static const auto table = [] {
        std::array<float, 801> result{};
        for (int i=0; i<=800; ++i) result[i] = std::pow(10.0, (-160.0+i*0.25)/10.0);
        return result;
    }();
    return table;
}
inline float power(float db, const std::array<float, 801>& table) {
    if (!std::isfinite(db)) return 1e-16;
    const float at = std::max(0.0f, std::min(800.0f, (db+160.0f)*4.0f));
    const int lo = int(at), hi = std::min(800, lo+1);
    return table[lo]+(at-lo)*(table[hi]-table[lo]);
}
inline void rasterize(const float* bins, int n, double first, double step, float* row, int width) {
    if (!n || !std::isfinite(first) || !std::isfinite(step) || step<=0) {
        std::fill(row, row+width, -160.0f); return;
    }
    const auto& table = powerTable();
    for (int x=0; x<width; ++x) {
        const double left = first+x*step;
        const double a = std::max(0.0, left), b = std::min(double(n), left+step);
        if (b<=a) { row[x]=-160.0f; continue; }
        // Only the two edge bins need fractional weights. Keep geometry in
        // double, but avoid double conversions/min/max for every interior bin
        // on ARMv7. Four accumulators shorten the dependency chain.
        int i=int(a);
        double sum=0;
        if (a>i) { sum=(std::min(b,i+1.0)-a)*power(bins[i], table); ++i; }
        const int end=int(b);
        float s0=0, s1=0, s2=0, s3=0;
        for (; i+3<end; i+=4) {
            s0+=power(bins[i], table); s1+=power(bins[i+1], table);
            s2+=power(bins[i+2], table); s3+=power(bins[i+3], table);
        }
        sum += double(s0)+s1+s2+s3;
        for (; i<end; ++i) sum+=power(bins[i], table);
        if (i<b) sum+=(b-i)*power(bins[i], table);
        row[x] = float(10.0*std::log10(std::max(1e-16, sum/(b-a))));
    }
}
}
