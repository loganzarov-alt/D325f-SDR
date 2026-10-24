// SPDX-License-Identifier: GPL-3.0-or-later
// Based on RF Analyzer by Dennis Mantz and contributors (https://github.com/demantz/RFAnalyzer),
// licensed GPL-2.0-or-later; this modified version is distributed under GPL-3.0-or-later.
// D325f SDR changes, Logan Zarov, 2026-10-23. See docs/CHANGES.md.
#include <jni.h>
#include <string>
#include <cmath>
#include <mutex>
#include <algorithm>
#include <android/log.h>
#include "pffft.h"
#include "spectrum_math.h"
#include "ci8_unpack.h"
#include "ci8_mixer.h"

#include <android/log.h>

#define LOG_TAG "PFFFTCheck"  // Replace with your desired tag
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static int fftSize = -1;
static PFFFT_Setup* setup = nullptr;
static float* scratch = nullptr;
static float* input = nullptr;
static float* output = nullptr;
static float* outputMag = nullptr;
static std::mutex fftMutex;
static float* blackman = nullptr;
static float coherentCorrection = 0.0f;

static void releaseFft() {
    pffft_aligned_free(blackman); blackman = nullptr;
    if (setup) pffft_destroy_setup(setup);
    pffft_aligned_free(scratch); pffft_aligned_free(input);
    pffft_aligned_free(output); pffft_aligned_free(outputMag);
    setup = nullptr; scratch = input = output = outputMag = nullptr; fftSize = -1;
}
static bool prepareFft(int length) {
    if (length == fftSize && setup && outputMag) return true;
    releaseFft();
    const int n = length / 2;
    if (length % 2 || n < 16 || (n & (n - 1)) != 0) return false;
    setup = pffft_new_setup(n, PFFFT_COMPLEX);
    scratch = (float*)pffft_aligned_malloc(length * sizeof(float));
    input = (float*)pffft_aligned_malloc(length * sizeof(float));
    output = (float*)pffft_aligned_malloc(length * sizeof(float));
    outputMag = (float*)pffft_aligned_malloc(n * sizeof(float));
    if (!setup || !scratch || !input || !output || !outputMag) { releaseFft(); return false; }
    fftSize = length;
    return true;
}
static void fftError(JNIEnv* env) {
    env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Invalid FFT size or unavailable FFT buffers");
}

static jint unpackJavaCi8(JNIEnv* env, jbyteArray raw, jfloatArray re, jfloatArray im, jint start, bool inverted) {
    const int capacity=std::min(env->GetArrayLength(re),env->GetArrayLength(im));
    if (start<0 || start>capacity) { fftError(env); return 0; }
    const int count=std::min(env->GetArrayLength(raw)/2,capacity-start);
    // Fixed bounded chunks: no per-packet allocation, no copy of the complete
    // 131072-element destination arrays for each 16384-sample source packet.
    jbyte bytes[4096]; float real[2048], imaginary[2048];
    for (int offset=0; offset<count; offset+=2048) {
        const int size=std::min(2048,count-offset);
        env->GetByteArrayRegion(raw,offset*2,size*2,bytes);
        unpackCi8(reinterpret_cast<const int8_t*>(bytes),real,imaginary,size);
        if(inverted) for(int i=0;i<size;i++) imaginary[i]=-imaginary[i];
        env->SetFloatArrayRegion(re,start+offset,size,real);
        env->SetFloatArrayRegion(im,start+offset,size,imaginary);
    }
    return count;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_nativedsp_NativeDsp_unpackCi8(JNIEnv* env, jobject, jbyteArray raw, jfloatArray re, jfloatArray im, jint start) {
    return unpackJavaCi8(env,raw,re,im,start,false);
}
extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_nativedsp_NativeDsp_unpackCi8Conjugated(JNIEnv* env, jobject, jbyteArray raw, jfloatArray re, jfloatArray im, jint start) {
    return unpackJavaCi8(env,raw,re,im,start,true);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mantz_1it_nativedsp_NativeDsp_performFFT(
        JNIEnv* env, jobject /* this */, jfloatArray inputArray, jfloatArray outputArray) {

    std::lock_guard<std::mutex> guard(fftMutex);
    // Get array length
    jsize length = env->GetArrayLength(inputArray);

    if (!prepareFft(length) || env->GetArrayLength(outputArray) < length) {
        fftError(env); return;
    }

    env->GetFloatArrayRegion(inputArray, 0, length, input);

    // Perform FFT
    pffft_transform_ordered(setup, input, output, scratch, PFFFT_FORWARD);
    //pffft_transform(setup, input, output, scratch, PFFFT_FORWARD);

    env->SetFloatArrayRegion(outputArray, 0, length, output);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mantz_1it_nativedsp_NativeDsp_performFFTAndLogMag(
        JNIEnv* env, jobject /* this */, jfloatArray inputArray, jfloatArray outputArray) {
    float realPower;
    float imagPower;
    int targetIndex;
    int outputLength;

    std::lock_guard<std::mutex> guard(fftMutex);
    // Get array length
    jsize length = env->GetArrayLength(inputArray);
    outputLength = length / 2;

    if (!prepareFft(length) || env->GetArrayLength(outputArray) < outputLength) {
        fftError(env); return;
    }

    env->GetFloatArrayRegion(inputArray, 0, length, input);

    // Perform FFT
    pffft_transform_ordered(setup, input, output, scratch, PFFFT_FORWARD);

    // Calculate the logarithmic magnitude:
    for(int i = 0; i<outputLength; i++) {
        realPower = output[2*i] / (float)outputLength;
        realPower *= realPower;
        imagPower = output[2*i+1] / (float)outputLength;
        imagPower *= imagPower;
        targetIndex = (i + outputLength/2) % outputLength;
        outputMag[targetIndex] = 10.0f * std::log10(std::max(1e-16f, realPower + imagPower));
    }
    env->SetFloatArrayRegion(outputArray, 0, outputLength, outputMag);
}


/*
 * LG D325 channel-select front end.
 * Multiply CI8 by exp(+j*2*pi*(source-channel)*n/Fs), integrate 30 samples,
 * then emit one complex sample.  The boxcar is deliberately inexpensive and has
 * its first null at 256 kHz for Fs=7.68 MHz, suppressing the worst aliases before
 * the Java demodulator.  state = {oscRe, oscIm, sumRe, sumIm, count}.
 */
extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_nativedsp_NativeDsp_mixDownsampleCi8Boxcar30Native(
        JNIEnv* env, jobject /* this */, jbyteArray inputArray,
        jfloatArray outputReArray, jfloatArray outputImArray,
        jint outputStart, jint outputCapacity,
        jdouble frequencyOffsetHz, jdouble sampleRateHz,
        jfloatArray stateArray) {
    if (!inputArray || !outputReArray || !outputImArray || !stateArray ||
        outputCapacity <= 0 || sampleRateHz <= 0.0) return 0;

    const jsize inputBytes = env->GetArrayLength(inputArray);
    const jsize reLength = env->GetArrayLength(outputReArray);
    const jsize imLength = env->GetArrayLength(outputImArray);
    const jsize stateLength = env->GetArrayLength(stateArray);
    if (stateLength < 5 || outputStart < 0 || outputStart >= reLength || outputStart >= imLength)
        return 0;

    int room = outputCapacity;
    if (room > reLength - outputStart) room = reLength - outputStart;
    if (room > imLength - outputStart) room = imLength - outputStart;
    if (room <= 0) return 0;

    jbyte in[4096];
    jfloat outRe[70], outIm[70];
    jfloat state[5];
    env->GetFloatArrayRegion(stateArray, 0, 5, state);

    float oscRe = state[0];
    float oscIm = state[1];
    if (!std::isfinite(oscRe) || !std::isfinite(oscIm) || (oscRe == 0.0f && oscIm == 0.0f)) {
        oscRe = 1.0f;
        oscIm = 0.0f;
    }
    state[0] = oscRe; state[1] = oscIm;
    if (state[4] < 0 || state[4] >= 30) state[2] = state[3] = state[4] = 0;
    const Ci8Mixer30 mixer(6.28318530717958647692 * frequencyOffsetHz / sampleRateHz);
    int produced = 0;
    const int complexSamples = inputBytes / 2;
    for (int base = 0; base < complexSamples && produced < room; base += 2048) {
        const int chunk = std::min(2048, complexSamples - base);
        env->GetByteArrayRegion(inputArray, 2*base, 2*chunk, in);
        const int emitted = mixer.process(reinterpret_cast<const int8_t*>(in), chunk,
                outRe, outIm, std::min(70, room-produced), state);
        env->SetFloatArrayRegion(outputReArray, outputStart+produced, emitted, outRe);
        env->SetFloatArrayRegion(outputImArray, outputStart+produced, emitted, outIm);
        produced += emitted;
    }
    oscRe = state[0]; oscIm = state[1];

    // Keep long-run NCO amplitude stable without an expensive sqrt per sample.
    const float mag2 = oscRe * oscRe + oscIm * oscIm;
    if (mag2 > 0.5f && mag2 < 2.0f) {
        const float inv = 1.0f / std::sqrt(mag2);
        oscRe *= inv;
        oscIm *= inv;
    } else {
        oscRe = 1.0f;
        oscIm = 0.0f;
    }

    state[0] = oscRe;
    state[1] = oscIm;
    env->SetFloatArrayRegion(stateArray, 0, 5, state);

    return produced;
}

// Full-resolution FFT: the same Blackman window and coherent-gain calibration,
// without a Kotlin interleaving loop, intermediate JVM buffer or correction pass.
extern "C" JNIEXPORT void JNICALL
Java_com_mantz_1it_nativedsp_NativeDsp_performWindowedSplitFFT(
        JNIEnv* env, jobject, jfloatArray re, jfloatArray im, jfloatArray magnitude) {
    std::lock_guard<std::mutex> guard(fftMutex);
    const int n = env->GetArrayLength(re);
    if (env->GetArrayLength(im) != n || env->GetArrayLength(magnitude) != n || !prepareFft(2*n)) {
        fftError(env); return;
    }
    if (!blackman) {
        blackman = (float*)pffft_aligned_malloc(n * sizeof(float));
        if (!blackman) { fftError(env); return; }
        double sum = 0.0;
        for (int i=0; i<n; ++i) {
            const double phase = 6.28318530717958647692 * i / (n-1);
            blackman[i] = (float)(0.42 - 0.5*std::cos(phase) + 0.08*std::cos(2*phase));
            sum += blackman[i];
        }
        coherentCorrection = (float)(20.0 * std::log10(sum / n));
    }
    // output is scratch storage until the transform overwrites it.
    env->GetFloatArrayRegion(re, 0, n, output);
    env->GetFloatArrayRegion(im, 0, n, output+n);
    for (int i=0; i<n; ++i) {
        input[2*i] = output[i]*blackman[i];
        input[2*i+1] = output[n+i]*blackman[i];
    }
    pffft_transform_ordered(setup, input, output, scratch, PFFFT_FORWARD);
    const float inverseN = 1.0f/n;
    for (int i=0; i<n; ++i) {
        const float r=output[2*i]*inverseN, q=output[2*i+1]*inverseN;
        outputMag[(i+n/2) & (n-1)] = spectrum::powerDb(std::max(1e-16f,r*r+q*q))-coherentCorrection;
    }
    env->SetFloatArrayRegion(magnitude, 0, n, outputMag);
}

// Compact display rows are calculated outside the FFT lock. No audio/helper changes.
extern "C" JNIEXPORT void JNICALL
Java_com_mantz_1it_nativedsp_NativeDsp_rasterizeSpectrum(
        JNIEnv* env, jobject, jfloatArray bins, jdouble first, jdouble step, jfloatArray row) {
    const int n = env->GetArrayLength(bins), width = env->GetArrayLength(row);
    jfloat* data = env->GetFloatArrayElements(bins, nullptr);
    if (!data) return;
    jfloat* dest = env->GetFloatArrayElements(row, nullptr);
    if (!dest) { env->ReleaseFloatArrayElements(bins, data, JNI_ABORT); return; }
    spectrum::rasterize(data, n, first, step, dest, width);
    env->ReleaseFloatArrayElements(bins, data, JNI_ABORT);
    env->ReleaseFloatArrayElements(row, dest, 0);
}

extern "C" JNIEXPORT void JNICALL
Java_com_mantz_1it_nativedsp_NativeDsp_conjugate(JNIEnv* env, jobject, jfloatArray imaginary, jint start, jint count) {
    if(start<0 || count<0 || start>env->GetArrayLength(imaginary)-count) return;
    float values[2048];
    for(int at=0;at<count;at+=2048) {
        int n=std::min(2048,count-at);
        env->GetFloatArrayRegion(imaginary,start+at,n,values);
        for(int i=0;i<n;i++) values[i]=-values[i];
        env->SetFloatArrayRegion(imaginary,start+at,n,values);
    }
}

#include "bandwidth.h"
extern "C" JNIEXPORT jlong JNICALL
Java_com_mantz_1it_nativedsp_NativeDsp_createBandwidth(JNIEnv*,jobject,jint rate) {
 if(rate!=6144000&&rate!=2560000&&rate!=1280000)return 0;
 return reinterpret_cast<jlong>(new BandwidthFilter(rate));
}
extern "C" JNIEXPORT void JNICALL
Java_com_mantz_1it_nativedsp_NativeDsp_destroyBandwidth(JNIEnv*,jobject,jlong handle) {delete reinterpret_cast<BandwidthFilter*>(handle);}
extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_nativedsp_NativeDsp_filterBandwidth(JNIEnv* e,jobject,jlong handle,jbyteArray raw,jfloatArray re,jfloatArray im,jboolean inverted) {
 auto* f=reinterpret_cast<BandwidthFilter*>(handle);if(!f)return 0;
 const int count=e->GetArrayLength(raw)/2;
 if(e->GetArrayLength(re)<count||e->GetArrayLength(im)<count)return 0;
 jbyte bytes[4096]; float real[2048],imag[2048]; int total=0;
 for(int at=0;at<count;at+=2048){
  int size=std::min(2048,count-at);
  e->GetByteArrayRegion(raw,at*2,size*2,bytes);
  int n=f->process(reinterpret_cast<int8_t*>(bytes),size,real,imag,inverted);
  e->SetFloatArrayRegion(re,total,n,real);e->SetFloatArrayRegion(im,total,n,imag);
  total+=n;
 }
 return total;
}
extern "C" JNIEXPORT jint JNICALL
Java_com_mantz_1it_nativedsp_NativeDsp_mixBandwidth(JNIEnv* e,jobject,jfloatArray re,jfloatArray im,jint count,jfloatArray outRe,jfloatArray outIm,jint start,jdouble offset,jint rate,jfloatArray stateArray) {
 if(rate<=0||rate%256000||count<0||count>e->GetArrayLength(re)||count>e->GetArrayLength(im)||start<0)return 0;
 const int room=std::min(e->GetArrayLength(outRe),e->GetArrayLength(outIm))-start;
 if(room<=0||e->GetArrayLength(stateArray)<5)return 0;
 float state[5];e->GetFloatArrayRegion(stateArray,0,5,state);
 float* r=e->GetFloatArrayElements(re,nullptr);float* q=e->GetFloatArrayElements(im,nullptr);
 float* rr=e->GetFloatArrayElements(outRe,nullptr);float* qq=e->GetFloatArrayElements(outIm,nullptr);
 if(!r||!q||!rr||!qq){if(r)e->ReleaseFloatArrayElements(re,r,JNI_ABORT);if(q)e->ReleaseFloatArrayElements(im,q,JNI_ABORT);if(rr)e->ReleaseFloatArrayElements(outRe,rr,0);if(qq)e->ReleaseFloatArrayElements(outIm,qq,0);return 0;}
 float cr=state[0],ci=state[1],sr=state[2],si=state[3];int pending=int(state[4]),n=0,d=rate/256000;
 float wr=std::cos(2*M_PI*offset/rate),wi=std::sin(2*M_PI*offset/rate);
 for(int k=0;k<count;k++) {
  sr+=r[k]*cr-q[k]*ci;si+=q[k]*cr+r[k]*ci;
  float next=cr*wr-ci*wi;ci=ci*wr+cr*wi;cr=next;
  if(++pending==d){if(n<room){rr[start+n]=sr/d;qq[start+n]=si/d;++n;}pending=0;sr=si=0;}
  if((k&1023)==1023){float norm=1.f/std::sqrt(cr*cr+ci*ci);cr*=norm;ci*=norm;}
 }
 state[0]=cr;state[1]=ci;state[2]=sr;state[3]=si;state[4]=pending;e->SetFloatArrayRegion(stateArray,0,5,state);
 e->ReleaseFloatArrayElements(re,r,JNI_ABORT);e->ReleaseFloatArrayElements(im,q,JNI_ABORT);e->ReleaseFloatArrayElements(outRe,rr,0);e->ReleaseFloatArrayElements(outIm,qq,0);return n;
}
