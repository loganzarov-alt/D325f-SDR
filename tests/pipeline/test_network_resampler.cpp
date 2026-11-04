// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
#include "../../nativedsp/src/main/cpp/network_resampler.h"
#include <cassert>
#include <iostream>
int main(){
 for(int rate:{250000,1024000,1280000,2400000,2500000,2560000,3200000,7680000}) {
  NetworkResampler a(rate),b(rate);std::vector<int8_t> input(153600);for(size_t i=0;i<input.size()/2;i++){input[2*i]=std::lround(70*std::cos(2*M_PI*.01*i));input[2*i+1]=std::lround(70*std::sin(2*M_PI*.01*i));}
  std::vector<uint8_t> whole(input.size()),split(input.size());int n=a.process(input.data(),input.size()/2,whole.data(),1,false),m=0;
  for(size_t i=0;i<input.size();){int count=std::min(size_t(134),input.size()-i);m+=b.process(input.data()+i,count/2,split.data()+m,1,false);i+=count;}
  assert(n==2*rate/100);assert(n==m);assert(std::equal(whole.begin(),whole.begin()+n,split.begin()));
  // Real anti-alias filtering: compare 0.05*output-rate and 0.8*output-rate tones.
  if(rate<7680000){double powers[2]{};for(int t=0;t<2;t++){NetworkResampler f(rate);for(size_t i=0;i<input.size()/2;i++){double angle=2*M_PI*(t?.8:.05)*rate/7680000*i;input[2*i]=std::lround(70*std::cos(angle));input[2*i+1]=std::lround(70*std::sin(angle));}int z=f.process(input.data(),input.size()/2,whole.data(),1,false);for(int j=2048;j<z;j++)powers[t]+=std::pow(int(whole[j])-128,2);powers[t]/=z-2048;}assert(powers[1]<powers[0]*.01);}
 }
 NetworkResampler unity(7680000);int8_t raw[]={-128,127,0,1};uint8_t out[4];assert(unity.process(raw,2,out,1,false)==4);assert(out[0]==0&&out[1]==255&&out[2]==128&&out[3]==129);
 std::cout<<"PASS network resampling: exact rates, chunk continuity, anti-aliasing, CU8\n";
}
