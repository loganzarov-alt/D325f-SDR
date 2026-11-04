// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
#pragma once
#include "bandwidth.h"
#include <vector>
#include <stdexcept>
// CU8 network output. Timing is rational; state survives arbitrary packet splits.
struct NetworkResampler {
 int rate,taps,pos=0; int64_t index=-1,next=0; int remainder=0;
 std::vector<int16_t> coeff,re,im;
 double agcGain=1;
 explicit NetworkResampler(int r):rate(r) {
  if(r<250000 || r>7680000)throw std::invalid_argument("Network rate: 250000..7680000");
  taps=r==7680000?0:std::min(512, std::max(48,((int)std::ceil(122880000.0/r)+7)/8*8));
  coeff.resize(1024*taps);re.resize(2*taps);im.resize(2*taps);
  for(int p=0;p<1024 && taps;p++) {
   double sum=0,cut=.45*r/7680000.0;std::vector<double> c(taps);
   for(int k=0;k<taps;k++) { double x=k-(taps-1)*.5+p/1024.;c[k]=(std::abs(x)<1e-9?2*cut:std::sin(2*M_PI*cut*x)/(M_PI*x))*(.54-.46*std::cos(2*M_PI*k/(taps-1)));sum+=c[k]; }
   int total=0;for(int k=0;k<taps;k++){coeff[p*taps+k]=std::lround(c[k]/sum*32768);total+=coeff[p*taps+k];}coeff[p*taps+taps/2]+=32768-total;
  }
 }
 int process(const int8_t* input,int count,uint8_t* output,double gain,bool agc) {
  if(agc && count) { double peak=1;for(int i=0;i<count*2;i++)peak=std::max(peak,double(std::abs(int(input[i]))));double target=100/peak;agcGain+=(target<agcGain?.5:.01)*(target-agcGain);gain*=std::min(16.,agcGain); }
  int n=0;
  for(int i=0;i<count;i++) {
   double a=input[2*i],b=input[2*i+1];
   if(taps) {
    if(--pos<0)pos=taps-1;
    re[pos]=re[pos+taps]=input[2*i];im[pos]=im[pos+taps]=input[2*i+1];++index;
    if(index<next)continue;
    int phase=int(int64_t(remainder)*1024/rate);
    a=BandwidthFilter::dot(re.data()+pos,coeff.data()+phase*taps,taps)/32768.;
    b=BandwidthFilter::dot(im.data()+pos,coeff.data()+phase*taps,taps)/32768.;
    int64_t step=int64_t(remainder)+7680000;next+=step/rate;remainder=step%rate;
   }
   output[n++]=uint8_t(std::clamp(std::lround(a*gain),-128L,127L)+128);
   output[n++]=uint8_t(std::clamp(std::lround(b*gain),-128L,127L)+128);
  }
  return n;
 }
};
