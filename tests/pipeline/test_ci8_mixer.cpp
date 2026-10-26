// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
#include "ci8_mixer.h"
#include <vector>
#include <random>
#include <cstdio>
#include <cstdlib>
#include <chrono>
int main() {
 // Positive and negative RF offsets must both tune to DC after conjugation.
 for (bool inverted : {false, true}) for (double hz : {-37000., 53000.}) {
  const int n=16380; std::vector<int8_t> tone(2*n);
  for(int k=0;k<n;k++) {
   double a=6.28318530717958647692*hz*k/7680000;
   tone[2*k]=(int8_t)std::lround(100*cos(a));
   tone[2*k+1]=(int8_t)std::lround((inverted?-100:100)*sin(a));
  }
  double mixHz=inverted?hz:-hz;
  Ci8Mixer30 m(6.28318530717958647692*mixHz/7680000);
  float state[5]={1,0,0,0,0}; std::vector<float> re(n/30),im(n/30);
  int out=m.process(tone.data(),n,re.data(),im.data(),n/30,state);
  if(out!=n/30)abort();
  for(int k=0;k<out;k++) {
   if(inverted) im[k]=-im[k];
   if(std::abs(re[k]-100./128)>0.004 || std::abs(im[k])>0.004)abort();
  }
 }
 puts("PASS: inverted/non-inverted CI8, positive/negative channels tune to DC");
 std::mt19937 rng(131);
 std::vector<int8_t> raw(2*16384*50); for(auto& x:raw)x=(int8_t)rng();
 for(double hz:{0.,1.,-123456.,200000.,-3800000.}) {
  const double step=6.28318530717958647692*hz/7680000;
  Ci8Mixer30 mixer(step); float state[5]={1,0,0,0,0};
  std::vector<float> re(raw.size()/60+1),im(re.size()); int produced=0;
  int pos=0; while(pos<(int)raw.size()/2) {
   int n=std::min(1+(int)(rng()%2048),(int)raw.size()/2-pos);
   produced+=mixer.process(raw.data()+2*pos,n,re.data()+produced,im.data()+produced,(int)re.size()-produced,state);
   // Match JNI normalization at call boundaries.
   float scale=1/std::hypot(state[0],state[1]);state[0]*=scale;state[1]*=scale;
   pos+=n;
  }
  if(produced!=(int)raw.size()/60 || (int)state[4]!=(int)(raw.size()/2%30))abort();
  double maxError=0;
  for(int p=0;p<produced;++p) {
   double r=0,q=0;for(int k=0;k<30;++k) {int n=p*30+k;double c=cos(step*n),s=sin(step*n);r+=raw[2*n]*c-raw[2*n+1]*s;q+=raw[2*n+1]*c+raw[2*n]*s;}
   maxError=std::max(maxError,std::hypot(re[p]-r/3840,im[p]-q/3840));
  }
  printf("mixer offset=%.0f max complex error=%g outputs=%d\n",hz,maxError,produced);
  if(maxError>0.003)abort();
 }
 // Streaming RF-size blocks; indicative host timing, not a phone benchmark.
 Ci8Mixer30 mixer(0.101);float s[5]={1,0,0,0,0},r[600],i[600];
 auto start=std::chrono::steady_clock::now();
 for(int k=0;k<2000;++k)mixer.process(raw.data(),16384,r,i,600,s);
 auto ns=std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now()-start).count();
 printf("PASS: all IQ samples, output clock, signed offsets and arbitrary packet boundaries; host %.1f MSps checksum=%g\n",32768e6/ns,r[0]);
}
