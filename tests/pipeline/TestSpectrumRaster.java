// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
import com.mantz_it.rfanalyzer.dsp.SpectrumRaster;
public class TestSpectrumRaster {
    static void near(float actual,double expected){if(!Float.isFinite(actual)||Math.abs(actual-expected)>0.015)throw new AssertionError(actual+" != "+expected);}
    public static void main(String[] args){
        float[] out=new float[1];
        SpectrumRaster.rasterize(new float[]{0,-20},0,2,out);near(out[0],10*Math.log10(0.505));
        SpectrumRaster.rasterize(new float[]{0,-20},0.5,1,out);near(out[0],10*Math.log10(0.505));
        SpectrumRaster.rasterize(new float[]{-60,-60,-60},0.3,1.6,out);near(out[0],-60);
        SpectrumRaster.rasterize(new float[]{-20},-2,1,out);near(out[0],-160);
        SpectrumRaster.rasterize(new float[]{Float.NaN},0,1,out);near(out[0],-160);
        float[] bins=new float[131072], screen=new float[480]; java.util.Arrays.fill(bins,-73.3f);
        long start=System.nanoTime();
        for(int i=0;i<200;i++)SpectrumRaster.rasterize(bins,0,bins.length/480.0,screen);
        for(float x:screen)near(x,-73.3);
        System.out.println("PASS: power mean, fractional pixel edges, flat-field invariance, invalid input; 131k raster ms/frame="+(System.nanoTime()-start)/200e6);
    }
}
