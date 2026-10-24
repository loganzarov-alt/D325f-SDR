// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
#include "spectrum_math.h"
#include <cassert>
#include <chrono>
#include <cstdio>
#include <random>
#include <vector>
#include <limits>

int main() {
    float maxError=0;
    for (int i=0; i<=2000000; ++i) {
        const float p=float(std::pow(10.0,-16.0+20.0*i/2000000.0));
        const float ref=10.0f*std::log10(std::max(1e-16f,p));
        maxError=std::max(maxError,std::abs(spectrum::powerDb(p)-ref));
    }
    assert(maxError<0.00005f);
    float row[480];
    float bins[]={0,-20};
    spectrum::rasterize(bins,2,0.5,1,row,1);
    assert(std::abs(row[0]-10*std::log10(0.505))<0.00001);
    spectrum::rasterize(bins,2,-3,1,row,1); assert(row[0]==-160);
    spectrum::rasterize(bins,2,0,-1,row,1); assert(row[0]==-160);
    float invalid[]={std::numeric_limits<float>::quiet_NaN()};
    spectrum::rasterize(invalid,1,0,1,row,1); assert(row[0]==-160);
    std::vector<float> data(131072,-73.3f);
    spectrum::rasterize(data.data(),data.size(),-0.37,data.size()/480.0,row,480);
    for(float x:row) assert(std::abs(x+73.3f)<0.003f);
    volatile float sink=0;
    auto bench=[&](bool fast) {
        auto start=std::chrono::steady_clock::now();
        for(int r=0;r<100;++r)
            for(int i=0;i<131072;++i) {
                float p=1e-10f+float(i)*1e-8f;
                sink=fast?spectrum::powerDb(p):10.0f*std::log10(p);
            }
        return std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count()/100;
    };
    const double referenceMs=bench(false), fastMs=bench(true);
    std::printf("PASS: 2,000,001 power levels; max error %.8f dB; detector edges and invalid inputs; 131072-bin log reference %.3f ms vs optimized %.3f ms (host only)\n",maxError,referenceMs,fastMs);
    (void)sink;
}
