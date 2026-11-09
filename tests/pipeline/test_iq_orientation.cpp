// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
#include "../../nativedsp/src/main/cpp/ci8_inversion.h"
#include <vector>
#include <cmath>
#include <cassert>
#include <iostream>
static double phaseDirection(const std::vector<int8_t>& b) {
    double sum=0;
    for(size_t i=2;i<b.size();i+=2) sum+=b[i-2]*b[i+1]-b[i-1]*b[i];
    return sum;
}
int main() {
    for(int sign:{-1,1}) {
        std::vector<int8_t> original(8192);
        for(size_t i=0;i<original.size()/2;i++) {
            double phase=sign*2*3.141592653589793*103*i/4096;
            original[2*i]=std::lround(100*cos(phase));original[2*i+1]=std::lround(100*sin(phase));
        }
        assert(phaseDirection(original)*sign>0);
        for(bool bt:{false,true})for(bool invert:{false,true}) {
            auto b=original;
            if(bt^invert)invertCi8Spectrum(b.data(),b.size());
            assert(phaseDirection(b)*sign*((bt^invert)?-1:1)>0);
            if(bt^invert)invertCi8Spectrum(b.data(),b.size());
            assert(b==original);
        }
    }
    int8_t limits[]={-128,127,0,0};invertCi8Spectrum(limits,4);
    assert(limits[0]==127 && limits[1]==-128 && limits[2]==0 && limits[3]==0);
    std::cout<<"PASS: known positive/negative offsets, direct/BT toggles, zero gaps, -128 exact and involution\n";
}
