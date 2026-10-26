# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Logan Zarov
# Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
"""Actual LG source startup + real pipe: audio mode must survive startSampling."""
import os, subprocess, tempfile
from pathlib import Path
root=Path(__file__).resolve().parents[2]
stubs={
 'android/content/SharedPreferences.java': '''package android.content; public interface SharedPreferences {boolean getBoolean(String key,boolean fallback);}''',
 'android/content/Context.java': '''package android.content; public class Context {public static final int MODE_PRIVATE=0; public SharedPreferences getSharedPreferences(String n,int m){return (key,fallback)->fallback;} public java.io.File getDir(String n,int m){return new java.io.File(".");}public android.content.res.AssetManager getAssets(){return new android.content.res.AssetManager();}}''',
 'android/content/res/AssetManager.java': '''package android.content.res; public class AssetManager {public java.io.InputStream open(String n)throws java.io.IOException{return new java.io.ByteArrayInputStream(new byte[0]);}}''',
 'android/os/Process.java': '''package android.os; public class Process {public static final int THREAD_PRIORITY_DISPLAY=-4;public static void setThreadPriority(int n){}}''',
 'android/os/SystemClock.java': '''package android.os;public class SystemClock {public static long elapsedRealtime(){return System.nanoTime()/1000000;}}''',
 'android/util/Log.java': '''package android.util; public class Log {public static int i(String a,String b){return 0;}public static int w(String a,String b){return 0;}public static int w(String a,String b,Throwable t){return 0;}public static int e(String a,String b){return 0;}public static int e(String a,String b,Throwable t){return 0;}}''',
 'com/mantz_it/nativedsp/NativeDsp.java': '''package com.mantz_it.nativedsp; public class NativeDsp {
 public long createBandwidth(int n){return 1;}public void destroyBandwidth(long n){}
 public int filterBandwidth(Object... x){return 0;}public int unpackCi8(Object... x){return 0;}public int unpackCi8Conjugated(Object... x){return 0;}
 public int mixBandwidth(Object... x){return 0;}public int mixDownsampleCi8Boxcar30(Object... x){return 0;}public void conjugate(Object... x){}
 }''',
 'com/mantz_it/rfanalyzer/source/BtCwController.java': '''package com.mantz_it.rfanalyzer.source; public class BtCwController {
 public static BtCwController get(android.content.Context c){return new BtCwController();}public static int getChannel(){return 30;}
 public void stop(){}public void stopAsync(){}public void packetReceived(){}public void configure(boolean b){}
 }''',
 'com/mantz_it/rfanalyzer/source/BtRxLimit.java': '''package com.mantz_it.rfanalyzer.source; public class BtRxLimit {public static long activeHz=800000000L;public static void reload(){}}''',
 'TestCapturePolicy.java': '''import com.mantz_it.rfanalyzer.source.LgModemSource;
import java.lang.reflect.Field;import java.io.File;
public class TestCapturePolicy {
 static void set(Object s,String n,Object v)throws Exception {Field f=s.getClass().getDeclaredField(n);f.setAccessible(true);f.set(s,v);}
 static Object get(Object s,String n)throws Exception {Field f=s.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(s);}
 public static void main(String[] args)throws Exception {
  LgModemSource s=new LgModemSource(1700000000L,1,0,0,0,20,0,0);
  java.lang.reflect.Method orientation=s.getClass().getDeclaredMethod("conjugateIq",long.class);
  orientation.setAccessible(true);
  for(boolean extra:new boolean[]{false,true}) {
   set(s,"extraConjugation",extra);
   if(!orientation.invoke(s,1700000000L).equals(extra))throw new AssertionError("Direct orientation");
   if(!orientation.invoke(s,137000000L).equals(!extra))throw new AssertionError("BT orientation");
  }
  set(s,"opened",true);set(s,"factoryHeld",true);set(s,"streamer",new File(System.getenv("FAKE_STREAM")));
  s.setDemodulationContinuityMode(true);
  try {
   s.startSampling();
   if(!((Boolean)get(s,"demodulationContinuityMode")))throw new AssertionError("Startup discarded audio mode");
   long end=System.nanoTime()+3000000000L;
   while(s.getReceivedBlockCount()<100&&System.nanoTime()<end)Thread.sleep(5);
   if(s.getReceivedBlockCount()!=100)throw new AssertionError("Fake stream incomplete");
   byte[] packet=s.getPacket(100);if(packet==null)throw new AssertionError("No packet");s.returnPacket(packet);
   if(((Long)get(s,"livePacketsDropped"))!=0L)throw new AssertionError("Audio startup trimmed continuous IQ");
   s.setDemodulationContinuityMode(false);
   packet=s.getPacket(100);if(packet==null)throw new AssertionError("No visual packet");s.returnPacket(packet);
   if(((Long)get(s,"livePacketsDropped"))==0L)throw new AssertionError("Visual backlog no longer bounded");
  } finally {s.stopSampling();}
  System.out.println("PASS: actual source startup preserves audio continuity; queued IQ survives audio mode and visual mode still trims backlog");
 }
}'''
}
with tempfile.TemporaryDirectory() as directory:
 d=Path(directory);sources=[]
 for name,text in stubs.items():
  p=d/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text);sources.append(str(p))
 for name in ['LgModemSource.java','IQSourceInterface.java','SamplePacket.java','BtReceivePlan.java','LgRxRecovery.java']:
  sources.append(str(root/'app/src/main/java/com/mantz_it/rfanalyzer/source'/name))
 sources.append(str(root/'app/build/generated/source/nativeHelpers/com/mantz_it/rfanalyzer/source/NativeHelperHashes.java'))
 subprocess.run(['javac','-d',str(d/'classes'),*sources],check=True)
 su=d/'su';su.write_text('#!/bin/sh\ncase "$2" in *"setenforce "*) exit 0;; esac\nexec /bin/sh "$@"\n');su.chmod(0o700)
 fake=d/'stream';fake.write_text('#!/usr/bin/env python3\nimport os\npacket=bytes(32768)\nfor _ in range(100):\n off=0\n while off<len(packet): off+=os.write(1,packet[off:])\nwhile os.read(0,1024): pass\n');fake.chmod(0o700)
 env=dict(os.environ,PATH=str(d)+':'+os.environ['PATH'],FAKE_STREAM=str(fake))
 subprocess.run(['java','-cp',str(d/'classes'),'TestCapturePolicy'],env=env,check=True,timeout=12)
