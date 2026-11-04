// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
package com.mantz_it.rfanalyzer.source;
import android.content.Context;
import com.mantz_it.rfanalyzer.network.RtlTcpConnection;
import java.util.concurrent.*;
/** Root-free CU8 source. TCP reads stay off the UI and scheduler threads. */
public final class NetworkIQSource implements IQSourceInterface {
 public static final int[] RATES={250000,1024000,1280000,1536000,1792000,1920000,2048000,2160000,2400000,2500000,2560000,2880000,3200000,7680000};
 private android.os.PowerManager.WakeLock wake;
 private android.net.wifi.WifiManager.WifiLock wifi;
 private final String host;private final int port,gain;private final boolean agc;
 private volatile long frequency;private volatile int rate;private volatile boolean closed=false,running=false;
 private final RtlTcpConnection connection=new RtlTcpConnection();private Callback callback;
 private final ArrayBlockingQueue<byte[]> filled=new ArrayBlockingQueue<>(32),pool=new ArrayBlockingQueue<>(32);
 private final NetworkIQConverter converter=new NetworkIQConverter();
 public NetworkIQSource(String host,int port,long frequency,int rate,int gain,boolean agc){this.host=host;this.port=port;this.frequency=frequency;this.rate=rate;this.gain=gain;this.agc=agc;converter.setFrequency(frequency);converter.setSampleRate(rate);for(int i=0;i<32;i++)pool.add(new byte[getPacketSize()]);}
 private volatile Thread worker;
 public boolean open(Context context,Callback cb){callback=cb;
  worker=new Thread(()->{
   try {
    acquireLocks(context);
    connection.setProgress((stage,bytes)->com.mantz_it.rfanalyzer.network.NetworkClientStatus.update(stage,bytes));
    connection.connect(host,port,frequency,rate,gain,agc);
    byte[] first=pool.take();connection.read(first);filled.put(first);
    if(!closed)cb.onIQSourceReady(this);
    // The same reader owns the socket from handshake through the entire stream.
    while(!closed){
     byte[] b=pool.poll(500,TimeUnit.MILLISECONDS);if(b==null)continue;
     connection.read(b);
     while(!closed&&!filled.offer(b,500,TimeUnit.MILLISECONDS)){}
    }
   }catch(Exception e){if(!closed){
    String message=host+":"+port+" — "+(e.getMessage()==null?e.toString():e.getMessage());
    com.mantz_it.rfanalyzer.network.NetworkClientStatus.update("ERROR: "+message,-1);
    cb.onIQSourceError(this,message);
   }}finally{close();}
  },"RTL-TCP-IQ");worker.start();return true;
 }
 private synchronized void acquireLocks(Context context){
  if(closed)return;
  android.os.PowerManager pm=(android.os.PowerManager)context.getSystemService(Context.POWER_SERVICE);
  if(pm!=null){wake=pm.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"D325f:NetworkClient");wake.acquire();}
  android.net.wifi.WifiManager wm=(android.net.wifi.WifiManager)context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
  if(wm!=null){wifi=wm.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF,"D325f:NetworkClient");wifi.acquire();}
 }
 public boolean isOpen(){return !closed&&connection.isOpen();}
 public synchronized boolean close(){closed=true;running=false;try{connection.close();}catch(Exception ignored){}
  Thread t=worker;if(t!=null&&t!=Thread.currentThread())t.interrupt();
  filled.clear();if(wake!=null&&wake.isHeld())wake.release();if(wifi!=null&&wifi.isHeld())wifi.release();return true;
 }
 public String getName(){return "RTL-TCP · "+host+":"+port;}
 public int getSampleRate(){return rate;}
 public void setSampleRate(int value){if(value==rate)return;if(running){callback.onIQSourceError(this,"Stop reception before changing network sample rate");return;}rate=value;converter.setSampleRate(value);send(2,value);}
 private void send(int cmd,long value){try{connection.command(cmd,value);}catch(Exception e){if(!closed&&callback!=null)callback.onIQSourceError(this,e.getMessage());}}
 public long getFrequency(){return frequency;}
 public void setFrequency(long value){frequency=value;converter.setFrequency(value);send(1,value);}
 public long getMinFrequency(){return 1L;}public long getMaxFrequency(){return 4294967295L;}
 public int getMinSampleRate(){return RATES[0];}public int getMaxSampleRate(){return RATES[RATES.length-1];}
 public int[] getSupportedSampleRates(){return RATES.clone();}
 public int getNextHigherOptimalSampleRate(int value){for(int r:RATES)if(r>value)return r;return getMaxSampleRate();}
 public int getNextLowerOptimalSampleRate(int value){for(int i=RATES.length-1;i>=0;i--)if(RATES[i]<value)return RATES[i];return getMinSampleRate();}
 public int getPacketSize(){return 32768;}public int getBytesPerSample(){return 2;}
 public byte[] getPacket(int timeout){try{long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(65);while(!closed&&System.nanoTime()<deadline){byte[] b=filled.poll(Math.min(200,Math.max(1,timeout)),TimeUnit.MILLISECONDS);if(b!=null)return b;}return null;}catch(InterruptedException e){Thread.currentThread().interrupt();return null;}}
 public void returnPacket(byte[] packet){pool.offer(packet);}
 public void startSampling(){if(!closed)running=true;}
 public void stopSampling(){close();}
 public int fillPacketIntoSamplePacket(byte[] p,SamplePacket s){return converter.fillPacketIntoSamplePacket(p,s);}
 public int mixPacketIntoSamplePacket(byte[] p,SamplePacket s,long f){return converter.mixPacketIntoSamplePacket(p,s,f);}
}
