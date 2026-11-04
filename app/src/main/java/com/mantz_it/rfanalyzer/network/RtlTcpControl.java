// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
package com.mantz_it.rfanalyzer.network;
import java.io.*;
/** Bounded latest-state command mailbox. Values use the standard rtl_tcp units. */
public final class RtlTcpControl {
 public static final int[] GAINS={0,9,14,27,37,77,87,125,144,157,166,197,207,229,254,280,297,328,338,364,372,386,402,421,434,439,445,480,496};
 private long frequency; private int rate,ppm,gain=0,ifGain=0; private boolean manual=true,agc=false; private long revision=0; private String diagnostic="";
 public RtlTcpControl(long frequency,int rate){this.frequency=frequency;this.rate=rate;}
 public static void greeting(OutputStream stream)throws IOException {DataOutputStream o=new DataOutputStream(stream);o.writeBytes("RTL0");o.writeInt(5);o.writeInt(GAINS.length);o.flush();}
 public synchronized void apply(int command,long value)throws IOException {
  switch(command) {
   case 1: if(value<1L || value>4294967295L)throw new IOException("Frequency outside LG range: "+value);frequency=value;break;
   case 2: if(value<250000 || value>7680000)throw new IOException("Sample rate outside 250000..7680000: "+value);rate=(int)value;break;
   case 3: manual=value!=0;break;
   case 4: gain=Math.max(-600,Math.min(600,(int)value));break;
   case 5: ppm=Math.max(-1000,Math.min(1000,(int)value));break;
   case 6: ifGain=Math.max(-600,Math.min(600,(short)value));break;
   case 8: agc=value!=0;break;
   case 13: if(value>=GAINS.length)throw new IOException("Invalid gain index");gain=GAINS[(int)value];break;
   default: diagnostic=String.format("Command 0x%02x: no corresponding LG hardware function",command);
  }
  revision++;
 }
 public void read(InputStream stream)throws IOException {DataInputStream in=new DataInputStream(stream);while(true)apply(in.readUnsignedByte(),Integer.toUnsignedLong(in.readInt()));}
 public synchronized Snapshot snapshot(){return new Snapshot(frequency,rate,ppm,gain+ifGain,!manual||agc,revision,diagnostic);}
 public static final class Snapshot {
  public final long frequency,revision;public final int rate,ppm,gain;public final boolean agc;public final String diagnostic;
  Snapshot(long f,int r,int p,int g,boolean a,long v,String d){frequency=f;rate=r;ppm=p;gain=g;agc=a;revision=v;diagnostic=d;}
  public long correctedFrequency(){return Math.round(frequency*(1+ppm/1000000.0));}
  public double linearGain(){return Math.pow(10,Math.max(-600,Math.min(600,gain))/200.0);}
 }
}
