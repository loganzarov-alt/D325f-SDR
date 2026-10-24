// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
package com.mantz_it.nativedsp;
public class NativeDsp {
    static { System.loadLibrary("pipeline_test"); }
    public native void performFFT(float[] input, float[] output);
    public native void performFFTAndLogMag(float[] input, float[] output);
    public native void performWindowedSplitFFT(float[] re, float[] im, float[] output);
    public native int mixDownsampleCi8Boxcar30Native(byte[] input, float[] re, float[] im,
        int start, int capacity, double frequency, double rate, float[] state);
    public native void rasterizeSpectrum(float[] bins, double first, double step, float[] row);
    public native void conjugate(float[] im, int start, int count);
    public native int unpackCi8Conjugated(byte[] input, float[] re, float[] im, int start);
    public native int unpackCi8(byte[] input, float[] re, float[] im, int start);
    public native long createBandwidth(int rate);
    public native void destroyBandwidth(long handle);
    public native int filterBandwidth(long handle, byte[] raw, float[] re, float[] im, boolean inverted);
    public native int mixBandwidth(float[] re, float[] im, int count, float[] outRe, float[] outIm, int start, double offset, int rate, float[] state);
    private static void verifyBandwidth() {
        NativeDsp dsp = new NativeDsp();
        for(int rate:new int[]{2560000,1280000}) {
            long handle=dsp.createBandwidth(rate);
            if(handle==0)throw new AssertionError("Filter allocation");
            byte[] raw=new byte[32768];float[] re=new float[16384],im=new float[16384],outRe=new float[600],outIm=new float[600];float[] state={1,0,0,0,0};
            int samples=0;
            try {
                for(int block=0;block<12;block++) {
                    for(int k=0;k<16384;k++){double phase=2*Math.PI*200000*(block*16384L+k)/7680000;raw[2*k]=(byte)Math.round(90*Math.cos(phase));raw[2*k+1]=(byte)Math.round(90*Math.sin(phase));}
                    int n=dsp.filterBandwidth(handle,raw,re,im,false);
                    int m=dsp.mixBandwidth(re,im,n,outRe,outIm,0,-200000,rate,state);samples+=m;
                    if(block>0)for(int k=0;k<m;k++) {
                        double amplitude=Math.hypot(outRe[k],outIm[k]);
                        if(Math.abs(amplitude-90./128)>0.025)throw new AssertionError("Narrow audio amplitude "+amplitude);
                        if(k>0&&Math.hypot(outRe[k]-outRe[k-1],outIm[k]-outIm[k-1])>0.02)throw new AssertionError("NCO frequency after decimation");
                    }
                }
                if(Math.abs(samples-12*16384/30)>1)throw new AssertionError("Audio sample clock changed");
            } finally {dsp.destroyBandwidth(handle);}
        }
        System.out.println("PASS: JNI bandwidth + demodulator frequency, amplitude and 256 kSps audio clock");
    }

    private static void verifyUnpack() {
        NativeDsp dsp=new NativeDsp();
        byte[] raw=new byte[32769];
        for(int i=0;i<raw.length;i++) raw[i]=(byte)i;
        for(int capacity:new int[]{1,7,2049,16384,131072}) {
            float[] re=new float[capacity], im=new float[capacity];
            java.util.Arrays.fill(re,42); java.util.Arrays.fill(im,43);
            int offset=Math.min(3,capacity);
            int n=dsp.unpackCi8(raw,re,im,offset);
            if(n!=Math.min(raw.length/2,capacity-offset)) throw new AssertionError("CI8 length");
            for(int i=0;i<capacity;i++) {
                float r=i<offset||i>=offset+n?42:raw[2*(i-offset)]/128f;
                float q=i<offset||i>=offset+n?43:raw[2*(i-offset)+1]/128f;
                if(re[i]!=r||im[i]!=q) throw new AssertionError("CI8 value or overwritten tail");
            }
        }
        float[] er=new float[16384],ei=new float[16384],ar=new float[16384],ai=new float[16384];
        dsp.unpackCi8(raw,er,ei,0);dsp.unpackCi8Conjugated(raw,ar,ai,0);
        for(int i=0;i<ar.length;i++)if(er[i]!=ar[i]||ei[i]!=-ai[i])throw new AssertionError("Fused conjugate");
        System.out.println("PASS: exact signed CI8 conversion, chunk edges, partial destinations and untouched tails");
    }
    private static void verifyFused() {
        NativeDsp dsp = new NativeDsp();
        java.util.Random random = new java.util.Random(112);
        for(int n : new int[]{64,8192,131072,256,131072}) {
            float[] re=new float[n], im=new float[n], interleaved=new float[2*n];
            double gain=0;
            for(int i=0;i<n;i++) {
                re[i]=(float)(random.nextDouble()*2-1);
                im[i]=(float)(random.nextDouble()*2-1);
                double phase=2*Math.PI*i/(n-1);
                float w=(float)(0.42-0.5*Math.cos(phase)+0.08*Math.cos(2*phase));
                gain+=w;
                interleaved[2*i]=re[i]*w; interleaved[2*i+1]=im[i]*w;
            }
            float[] expected=new float[n], actual=new float[n];
            dsp.performFFTAndLogMag(interleaved,expected);
            dsp.performWindowedSplitFFT(re,im,actual);
            float correction=(float)(20*Math.log10(gain/n));
            for(int i=0;i<n;i++) if(Math.abs(actual[i]-(expected[i]-correction))>0.003)
                throw new AssertionError("fused FFT changed spectrum: N="+n+" bin="+i);
        }
        // Native display reduction must match the existing Java detector at every zoom.
        float[] bins=new float[131072], refRow=new float[480], nativeRow=new float[480];
        for(int i=0;i<bins.length;i++) bins[i]=-160+random.nextFloat()*200;
        bins[7]=Float.NaN; bins[33]=Float.POSITIVE_INFINITY;
        for(double first : new double[]{-100.37,0,0.5,36000.123,131070})
            for(double step : new double[]{0.1,1,2.731,131072.0/480}) {
                com.mantz_it.rfanalyzer.dsp.SpectrumRaster.rasterize(bins,first,step,refRow);
                dsp.rasterizeSpectrum(bins,first,step,nativeRow);
                for(int i=0;i<480;i++) if(Math.abs(refRow[i]-nativeRow[i])>0.0001)
                    throw new AssertionError("native raster changed detector " + i);
            }
        System.out.println("PASS: native raster matches Java detector across viewport shifts and zooms");
        // High-side conversion must preserve the original audio mixer exactly:
        // conj(mix(x,+d)) == mix(conj(x),-d), including signed CI8 edges.
        byte[] physical=new byte[32768], logical=new byte[32768];
        for(int i=0;i<physical.length;i+=2) {
            physical[i]=(byte)(random.nextInt(255)-127);
            physical[i+1]=(byte)(random.nextInt(255)-127);
            logical[i]=physical[i]; logical[i+1]=(byte)-physical[i+1];
        }
        float[] pr=new float[600],pi=new float[600],lr=new float[600],li=new float[600];
        int pn=dsp.mixDownsampleCi8Boxcar30Native(physical,pr,pi,0,600,123456,7680000,new float[]{1,0,0,0,0});
        int ln=dsp.mixDownsampleCi8Boxcar30Native(logical,lr,li,0,600,-123456,7680000,new float[]{1,0,0,0,0});
        dsp.conjugate(pi,0,pn);
        if(pn!=ln)throw new AssertionError("BT sample count");
        for(int i=0;i<pn;i++)if(Math.abs(pr[i]-lr[i])>0.0001||Math.abs(pi[i]-li[i])>0.0001)throw new AssertionError("BT demod mirror");
        float[] edge={42,-1,-128/128f,127/128f,43};dsp.conjugate(edge,1,3);
        if(edge[0]!=42||edge[1]!=1||edge[2]!=1||edge[3]!=-127/128f||edge[4]!=43)throw new AssertionError("Conjugate bounds");
        System.out.println("PASS: BT high-side IQ and demodulation preserve sideband orientation");
        // Arbitrary packet boundaries must preserve mixer phase and partial decimation sums.
        byte[] raw=new byte[32768*3]; random.nextBytes(raw);
        float[] wholeR=new float[2000],wholeI=new float[2000],splitR=new float[2000],splitI=new float[2000];
        float[] a={1,0,0,0,0},b={1,0,0,0,0};
        int count=dsp.mixDownsampleCi8Boxcar30Native(raw,wholeR,wholeI,0,2000,123456,7680000,a);
        int splitCount=0;
        for(int offset=0;offset<raw.length;offset+=32768) {
            byte[] part=java.util.Arrays.copyOfRange(raw,offset,offset+32768);
            splitCount+=dsp.mixDownsampleCi8Boxcar30Native(part,splitR,splitI,splitCount,2000-splitCount,123456,7680000,b);
        }
        if(count!=splitCount) throw new AssertionError("mixer sample loss");
        for(int i=0;i<count;i++) if(Math.abs(wholeR[i]-splitR[i])>0.002 || Math.abs(wholeI[i]-splitI[i])>0.002)
            throw new AssertionError("mixer discontinuity "+i);
        System.out.println("PASS: fused native FFT matches reference through 131072 bins; mixer retains packet-boundary continuity");
    }
    public static void main(String[] args) throws Exception {
        verifyBandwidth();
        verifyUnpack();
        verifyFused();
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Runnable task = () -> {
            try {
                NativeDsp dsp = new NativeDsp();
                for (int repeat=0; repeat<40; repeat++) {
                    for (int n : new int[]{64,256,8192,32768}) {
                        float[] iq=new float[2*n], db=new float[n];
                        dsp.performFFT(iq,new float[2*n]); // same-size FFT -> log FFT used to leave outputMag null
                        dsp.performFFTAndLogMag(iq,db);
                        for(float d:db) if(!Float.isFinite(d)) throw new AssertionError("silence not finite");
                        for (double amp : new double[]{1.0,0.5,0.1}) {
                            for(int i=0;i<n;i++) {iq[2*i]=(float)(amp*Math.cos(2*Math.PI*7*i/n));iq[2*i+1]=(float)(amp*Math.sin(2*Math.PI*7*i/n));}
                            dsp.performFFTAndLogMag(iq,db);
                            double expected=20*Math.log10(amp);
                            if(Math.abs(db[n/2+7]-expected)>0.002) throw new AssertionError("dB scale: "+db[n/2+7]+" expected "+expected);
                        }
                    }
                }
            } catch(Throwable e) {failure.set(e);}
        };
        Thread[] workers=new Thread[4];
        for(int i=0;i<4;i++){workers[i]=new Thread(task);workers[i].start();}
        for(Thread t:workers)t.join();
        if(failure.get()!=null) throw new AssertionError(failure.get());
        System.out.println("PASS: actual JNI/PFFFT tone scale, silence, shared FFT buffers, concurrent resizing");
    }
}
