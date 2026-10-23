"""Exercise the real AudioSink against stalled and failed Android writes."""
import subprocess
import tempfile
from pathlib import Path

root = Path(__file__).resolve().parents[2]
stubs = {
    'android/os/Process.java': '''package android.os; public class Process { public static final int THREAD_PRIORITY_URGENT_AUDIO=-19; public static void setThreadPriority(int n){} }''',
    'android/os/SystemClock.java': '''package android.os; public class SystemClock { public static long elapsedRealtime(){return System.nanoTime()/1000000;} }''',
    'android/util/Log.java': '''package android.util; public class Log { public static int d(String a,String b){return 0;} public static int i(String a,String b){return 0;} public static int e(String a,String b){return 0;} public static int e(String a,String b,Throwable t){return 0;} }''',
    'android/media/AudioManager.java': 'package android.media; public class AudioManager {}',
    'android/media/AudioAttributes.java': '''package android.media; public class AudioAttributes { public static final int USAGE_MEDIA=1,CONTENT_TYPE_MUSIC=2; public static class Builder { public Builder setUsage(int n){return this;} public Builder setContentType(int n){return this;} public AudioAttributes build(){return new AudioAttributes();} } }''',
    'android/media/AudioFormat.java': '''package android.media; public class AudioFormat { public static final int CHANNEL_OUT_MONO=1,ENCODING_PCM_FLOAT=2; public static class Builder { public Builder setEncoding(int n){return this;} public Builder setSampleRate(int n){return this;} public Builder setChannelMask(int n){return this;} public AudioFormat build(){return new AudioFormat();} } }''',
    'android/media/AudioTrack.java': '''package android.media; public class AudioTrack {
      public static final int MODE_STREAM=1,WRITE_NON_BLOCKING=1,WRITE_BLOCKING=0;
      public static volatile int mode=0,written=0,released=0,playedAt=-1,underruns=0,pauses=0,plays=0;
      public static float[] captured=new float[32768];
      public static int getMinBufferSize(int a,int b,int c){return 256;}
      public static class Builder { public Builder setAudioAttributes(AudioAttributes a){return this;} public Builder setAudioFormat(AudioFormat f){return this;} public Builder setBufferSizeInBytes(int n){return this;} public Builder setTransferMode(int n){return this;} public AudioTrack build(){return new AudioTrack();} }
      public int getPlaybackHeadPosition(){return Math.max(0,written-4800);} public int getUnderrunCount(){return underruns;} public void pause(){pauses++;} public void play(){playedAt=written;plays++;} public void stop(){} public void release(){released++;}
      public int write(float[] b,int o,int n,int flag){if(flag!=WRITE_NON_BLOCKING)throw new AssertionError("Blocking write"); if(mode==0)return 0; if(mode<0)return -6; int count=Math.min(n,7);for(int i=0;i<count;i++)if(written+i<captured.length)captured[written+i]=b[o+i];written+=count;return count;}
    }''',
    'com/mantz_it/rfanalyzer/source/SamplePacket.java': '''package com.mantz_it.rfanalyzer.source; public class SamplePacket { private float[] a;private int size,rate=32000;public SamplePacket(int n){a=new float[n];} public float[] re(){return a;}public int size(){return size;}public void setSize(int n){size=n;}public int getSampleRate(){return rate;} }''',
    'com/mantz_it/rfanalyzer/dsp/FirFilter.java': '''package com.mantz_it.rfanalyzer.dsp; import com.mantz_it.rfanalyzer.source.SamplePacket; public class FirFilter {public static FirFilter createLowPass(int a,int b,int c,float d,float e,int f){return new FirFilter();}public int getNumberOfTaps(){return 1;}public int filterReal(SamplePacket a,SamplePacket b,int o,int n){return n;} }''',
    'TestAudioSink.java': '''import com.mantz_it.rfanalyzer.analyzer.AudioSink;import com.mantz_it.rfanalyzer.source.SamplePacket;import android.media.AudioTrack;
    public class TestAudioSink {
      static AudioSink start() throws Exception {AudioSink s=new AudioSink(64,32000);s.start();SamplePacket p=s.getPacketBuffer(500);p.setSize(64);s.enqueuePacket(p);return s;}
      public static void main(String[] a)throws Exception {
        AudioTrack.mode=0;AudioSink stalled=start();stalled.join(3500);if(stalled.isAlive())throw new AssertionError("Audio stall not bounded");
        AudioTrack.mode=-1;AudioSink failed=start();failed.join(1000);if(failed.isAlive())throw new AssertionError("Dead track not released");
        AudioTrack.mode=1;AudioSink recovered=start();long end=System.nanoTime()+1000000000L;while(AudioTrack.written<64&&System.nanoTime()<end)Thread.sleep(5);recovered.stopSink();recovered.join(1500);if(AudioTrack.written!=64||recovered.isAlive())throw new AssertionError("Partial writes/replacement");
        AudioTrack.mode=0;AudioSink stopping=start();Thread.sleep(50);stopping.stopSink();stopping.join(500);if(stopping.isAlive())throw new AssertionError("Stop during stall");
        if(AudioTrack.released!=4)throw new AssertionError("AudioTrack leak");
        AudioTrack.mode=1;AudioTrack.written=0;AudioTrack.playedAt=-1;
        AudioSink primed=new AudioSink(512,32000);primed.start();
        Thread.sleep(10);if(AudioTrack.playedAt!=-1)throw new AssertionError("Played empty buffer");
        for(int i=0;i<12;i++){SamplePacket p=primed.getPacketBuffer(500);if(p==null)throw new AssertionError("Buffer ownership");p.setSize(512);primed.enqueuePacket(p);}
        end=System.nanoTime()+1000000000L;while(AudioTrack.playedAt<0&&System.nanoTime()<end)Thread.sleep(2);
        if(AudioTrack.playedAt<4800||AudioTrack.playedAt>4806)throw new AssertionError("Jitter reserve: "+AudioTrack.playedAt);
        primed.stopSink();primed.join(1000);if(primed.isAlive()||AudioTrack.released!=5)throw new AssertionError("Priming shutdown");
        AudioTrack.written=0;AudioTrack.playedAt=-1;AudioSink shortBurst=start();
        end=System.nanoTime()+500000000L;while(AudioTrack.playedAt<0&&System.nanoTime()<end)Thread.sleep(2);
        if(AudioTrack.playedAt!=64)throw new AssertionError("Short burst stranded");shortBurst.stopSink();shortBurst.join(1000);
        if(shortBurst.isAlive()||AudioTrack.released!=6)throw new AssertionError("Short burst cleanup");
        AudioTrack.written=0;AudioTrack.playedAt=-1;AudioTrack.plays=0;AudioTrack.pauses=0;
        AudioSink continuous=new AudioSink(512,32000);continuous.start();
        for(int k=0;k<40;k++){
          SamplePacket p=continuous.getPacketBuffer(500);if(p==null)throw new AssertionError("Lost audio buffer");
          p.setSize(512);java.util.Arrays.fill(p.re(),0.5f);AudioTrack.underruns=k;continuous.enqueuePacket(p);
          Thread.sleep(2);
        }
        end=System.nanoTime()+1000000000L;while(AudioTrack.written<20480&&System.nanoTime()<end)Thread.sleep(2);
        continuous.stopSink();continuous.join(1000);
        if(continuous.isAlive()||AudioTrack.written!=20480||AudioTrack.plays!=1||AudioTrack.pauses!=0)throw new AssertionError("Underruns caused playback stop/restart");
        for(int i=0;i<20480;i++)if(AudioTrack.captured[i]!=0.5f)throw new AssertionError("Artificial attenuation/gap");
        System.out.println("PASS: repeated underruns do not pause/restart playback or alter samples");
        System.out.println("PASS: audio stall, dead track, partial writes, priming reserve, short burst and prompt stop");
      }
    }'''
}
with tempfile.TemporaryDirectory() as folder:
    folder = Path(folder)
    sources = []
    for name, content in stubs.items():
        path = folder / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)
        sources.append(str(path))
    sources.append(str(root/'app/src/main/java/com/mantz_it/rfanalyzer/analyzer/AudioSink.java'))
    sources.append(str(root/'app/src/main/java/com/mantz_it/rfanalyzer/analyzer/AudioHeadroom.java'))
    subprocess.run(['javac','-d',str(folder),*sources],check=True)
    subprocess.run(['java','-cp',str(folder),'TestAudioSink'],check=True,timeout=10)
