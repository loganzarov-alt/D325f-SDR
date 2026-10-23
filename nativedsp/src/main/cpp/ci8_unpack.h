#pragma once
#include <cstdint>
#if defined(__ARM_NEON)
#include <arm_neon.h>
#endif

// Exact signed CI8 / 128 conversion. This is only the FFT acquisition path;
// the continuous audio mixer and its decimation state are independent.
inline void unpackCi8(const int8_t* input, float* re, float* im, int count) {
    int i=0;
#if defined(__ARM_NEON)
    const float32x4_t scale=vdupq_n_f32(1.0f/128.0f);
    for (; i+7<count; i+=8) {
        const int8x8x2_t iq=vld2_s8(input+2*i);
        const int16x8_t r=vmovl_s8(iq.val[0]), q=vmovl_s8(iq.val[1]);
        vst1q_f32(re+i,vmulq_f32(vcvtq_f32_s32(vmovl_s16(vget_low_s16(r))),scale));
        vst1q_f32(re+i+4,vmulq_f32(vcvtq_f32_s32(vmovl_s16(vget_high_s16(r))),scale));
        vst1q_f32(im+i,vmulq_f32(vcvtq_f32_s32(vmovl_s16(vget_low_s16(q))),scale));
        vst1q_f32(im+i+4,vmulq_f32(vcvtq_f32_s32(vmovl_s16(vget_high_s16(q))),scale));
    }
#endif
    for (; i<count; ++i) { re[i]=input[2*i]*(1.0f/128.0f); im[i]=input[2*i+1]*(1.0f/128.0f); }
}
