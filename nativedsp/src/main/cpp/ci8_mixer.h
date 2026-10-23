#pragma once
#include <algorithm>
#include <cmath>
#include <cstdint>
#ifdef __ARM_NEON
#include <arm_neon.h>
#endif

// Factor exp(j*w*(base+k)) into a block phase and 30 fixed coefficients.
// Every input sample contributes to the same length-30 boxcar as before.
// The oscillator recurrence runs once per output, not once per RF sample.
struct Ci8Mixer30 {
    float cr[31], ci[31];
    explicit Ci8Mixer30(double step) {
        for (int k=0;k<=30;++k) { cr[k]=std::cos(step*k); ci[k]=std::sin(step*k); }
    }
    int process(const int8_t* in, int samples, float* re, float* im, int room, float* s) const {
        int p=0, pos=0, count=(int)s[4];
        float phaseR=s[0], phaseI=s[1], sumR=s[2], sumI=s[3];
        while(pos<samples && p<room) {
            const int n=std::min(30-count,samples-pos);
            float r=0, q=0;
            int k=0;
#ifdef __ARM_NEON
            float32x4_t vr=vdupq_n_f32(0), vi=vr;
            for (;k+8<=n;k+=8) {
                const int8x8x2_t raw=vld2_s8(in+2*(pos+k));
                const int16x8_t ir=vmovl_s8(raw.val[0]), iq=vmovl_s8(raw.val[1]);
                const float32x4_t ar=vcvtq_f32_s32(vmovl_s16(vget_low_s16(ir)));
                const float32x4_t ai=vcvtq_f32_s32(vmovl_s16(vget_low_s16(iq)));
                const float32x4_t br=vcvtq_f32_s32(vmovl_s16(vget_high_s16(ir)));
                const float32x4_t bi=vcvtq_f32_s32(vmovl_s16(vget_high_s16(iq)));
                const float32x4_t c=vld1q_f32(cr+k), d=vld1q_f32(ci+k);
                const float32x4_t e=vld1q_f32(cr+k+4), f=vld1q_f32(ci+k+4);
                vr=vaddq_f32(vr,vsubq_f32(vmulq_f32(ar,c),vmulq_f32(ai,d)));
                vi=vaddq_f32(vi,vaddq_f32(vmulq_f32(ai,c),vmulq_f32(ar,d)));
                vr=vaddq_f32(vr,vsubq_f32(vmulq_f32(br,e),vmulq_f32(bi,f)));
                vi=vaddq_f32(vi,vaddq_f32(vmulq_f32(bi,e),vmulq_f32(br,f)));
            }
            float32x2_t rr=vadd_f32(vget_low_f32(vr),vget_high_f32(vr));
            float32x2_t ii=vadd_f32(vget_low_f32(vi),vget_high_f32(vi));
            r=vget_lane_f32(vpadd_f32(rr,rr),0); q=vget_lane_f32(vpadd_f32(ii,ii),0);
#endif
            for(;k<n;++k) {
                const float a=in[2*(pos+k)], b=in[2*(pos+k)+1];
                r+=a*cr[k]-b*ci[k]; q+=b*cr[k]+a*ci[k];
            }
            sumR+=(r*phaseR-q*phaseI)*(1.f/128);
            sumI+=(q*phaseR+r*phaseI)*(1.f/128);
            const float next=phaseR*cr[n]-phaseI*ci[n];
            phaseI=phaseI*cr[n]+phaseR*ci[n]; phaseR=next;
            pos+=n; count+=n;
            if(count==30) { re[p]=sumR/30; im[p]=sumI/30; ++p; count=0; sumR=sumI=0; }
        }
        s[0]=phaseR; s[1]=phaseI; s[2]=sumR; s[3]=sumI; s[4]=(float)count;
        return p;
    }
};
