// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
package com.mantz_it.rfanalyzer.source;
/** CU8 conversion with a continuous oscillator: no per-retune lookup-table allocation. */
public final class NetworkIQConverter extends IQConverter {
 private volatile long center;
 private volatile int rate;
 private double oscillatorReal=1,oscillatorImag=0;
 private int normalizationCounter;
 @Override protected void generateLookupTable(){}
 @Override protected void generateMixerLookupTable(int ignored){}
 @Override public void setFrequency(long value){center=value;}
 @Override public long getFrequency(){return center;}
 @Override public void setSampleRate(int value){if(value<=0)throw new IllegalArgumentException("Sample rate must be positive");rate=value;}
 @Override public int getSampleRate(){return rate;}
 @Override public int fillPacketIntoSamplePacket(byte[] packet,SamplePacket target){
  int start=target.size(),n=Math.min(packet.length/2,target.capacity()-start);
  float[] re=target.re(),im=target.im();
  for(int i=0;i<n;i++){re[start+i]=((packet[2*i]&255)-128)/128f;im[start+i]=((packet[2*i+1]&255)-128)/128f;}
  target.setSize(start+n);target.setSampleRate(rate);target.setFrequency(center);return n;
 }
 @Override public int mixPacketIntoSamplePacket(byte[] packet,SamplePacket target,long channel){
  final int sampleRate=rate;if(sampleRate<=0)throw new IllegalStateException("Sample rate not set");
  // Modulo keeps large drag offsets and frequencies above 2 GHz numerically bounded.
  double step=2*Math.PI*((center-channel)%sampleRate)/sampleRate;
  double stepReal=Math.cos(step),stepImag=Math.sin(step);
  double cr=oscillatorReal,ci=oscillatorImag;
  int start=target.size(),n=Math.min(packet.length/2,target.capacity()-start);
  float[] re=target.re(),im=target.im();
  for(int i=0;i<n;i++){
   double a=((packet[2*i]&255)-128)/128.0,b=((packet[2*i+1]&255)-128)/128.0;
   re[start+i]=(float)(a*cr-b*ci);im[start+i]=(float)(b*cr+a*ci);
   double next=cr*stepReal-ci*stepImag;ci=ci*stepReal+cr*stepImag;cr=next;
   if(++normalizationCounter==1024){double norm=1/Math.hypot(cr,ci);cr*=norm;ci*=norm;normalizationCounter=0;}
  }
  oscillatorReal=cr;oscillatorImag=ci;
  target.setSize(start+n);target.setSampleRate(sampleRate);target.setFrequency(channel);return n;
 }
}
