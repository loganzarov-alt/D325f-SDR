// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
#include "bandwidth.h"
#include "pffft.h"
#include <vector>
#include <chrono>
#include <cstdio>
static volatile float checksum;
int main(){const int fft=131072;
 auto* setup=pffft_new_setup(fft,PFFFT_COMPLEX);float* in=(float*)pffft_aligned_malloc(fft*2*sizeof(float));float* out=(float*)pffft_aligned_malloc(fft*2*sizeof(float));float* work=(float*)pffft_aligned_malloc(fft*2*sizeof(float));
 for(int rate:{7680000,6144000,2560000,1280000}) {
  BandwidthFilter filter(rate);std::vector<int8_t>raw(32768);for(int k=0;k<32768;k++)raw[k]=k%251-125;
  float r[16384],i[16384];int pos=0,frames=0;float cr=1,ci=0,sr=0,si=0;int phase=0,d=rate/256000;float wr=cos(2*M_PI*100000/rate),wi=sin(2*M_PI*100000/rate);
  auto begin=std::chrono::steady_clock::now();
  for(int b=0;b<469;b++) {int n=filter.process(raw.data(),16384,r,i,false);for(int k=0;k<n;k++) {
   sr+=r[k]*cr-i[k]*ci;si+=i[k]*cr+r[k]*ci;float t=cr*wr-ci*wi;ci=ci*wr+cr*wi;cr=t;if(++phase==d){checksum=sr+si;sr=si=0;phase=0;}
   if((k&1023)==1023){float norm=1/sqrt(cr*cr+ci*ci);cr*=norm;ci*=norm;}
   in[2*pos]=r[k];in[2*pos+1]=i[k];if(++pos==fft){pffft_transform_ordered(setup,in,out,work,PFFFT_FORWARD);checksum=out[42];pos=0;frames++;}
  }}
  double ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-begin).count();printf("Host DSP throughput: %d samples/s -> %.1f ms CPU / second IQ, FFT=%d\n",rate,ms,frames);
 }
 pffft_destroy_setup(setup);pffft_aligned_free(in);pffft_aligned_free(out);pffft_aligned_free(work);
}
