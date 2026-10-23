#pragma once
#include <cmath>
#include <cstdint>
#include <cstring>
#include <algorithm>
#ifdef __ARM_NEON
#include <arm_neon.h>
#endif
// Streaming rational FIR. Q15 taps and duplicated CI8 history avoid float
// expansion of discarded samples. No allocations in the packet path.
struct BandwidthFilter {
 int rate=7680000, up=1, down=1, taps=0, pos=0;
 int64_t inputIndex=-1, nextInput=0;
 int phase=0;
 int16_t coeff[4][128]{}, re[256]{}, im[256]{};
 explicit BandwidthFilter(int r):rate(r) {
  if(r==6144000){up=4;down=5;taps=24;}
  else if(r==2560000){down=3;taps=48;}
  else if(r==1280000){down=6;taps=96;}
  if(!taps)return;
  const double bw=r==6144000?5000000:r==2560000?2000000:1000000;
  const double cutoff=(bw+r)*0.25/7680000.0;
  for(int phase=0;phase<up;phase++) {
   double c[128],sum=0;
   for(int k=0;k<taps;k++) {
    double x=k-(taps-1)*0.5+double(phase)/up;
    c[k]=(std::abs(x)<1e-9?2*cutoff:std::sin(2*M_PI*cutoff*x)/(M_PI*x))*(0.54-0.46*std::cos(2*M_PI*k/(taps-1)));
    sum+=c[k];
   }
   int total=0;for(int k=0;k<taps;k++){coeff[phase][k]=std::lround(c[k]/sum*32768);total+=coeff[phase][k];}
   coeff[phase][taps/2]+=32768-total;
  }
 }
 static int dot(const int16_t* x,const int16_t* c,int n) {
#ifdef __ARM_NEON
  int32x4_t a=vdupq_n_s32(0),b=a;
  for(int k=0;k<n;k+=8){int16x8_t xx=vld1q_s16(x+k),cc=vld1q_s16(c+k);a=vmlal_s16(a,vget_low_s16(xx),vget_low_s16(cc));b=vmlal_s16(b,vget_high_s16(xx),vget_high_s16(cc));}
  a=vaddq_s32(a,b);int32x2_t s=vadd_s32(vget_low_s32(a),vget_high_s32(a));return vget_lane_s32(s,0)+vget_lane_s32(s,1);
#else
  int sum=0;for(int k=0;k<n;k++)sum+=int(x[k])*c[k];return sum;
#endif
 }
 int process(const int8_t* input,int count,float* outRe,float* outIm,bool inverted) {
  int n=0;
  for(int i=0;i<count;i++) {
   if(!taps){outRe[n]=input[2*i]/128.f;outIm[n++]=input[2*i+1]*(inverted?-1.f:1.f)/128.f;continue;}
   if(--pos<0)pos=taps-1;re[pos]=re[pos+taps]=input[2*i];im[pos]=im[pos+taps]=input[2*i+1];++inputIndex;
   if(inputIndex>=nextInput) {
    outRe[n]=dot(re+pos,coeff[phase],taps)*(1.f/(128*32768));
    outIm[n++]=dot(im+pos,coeff[phase],taps)*(inverted?-1.f:1.f)/(128*32768);
    if(up==1)nextInput+=down;else {phase+=down;nextInput+=phase>>2;phase&=3;}
   }
  }
  return n;
 }
};
