// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
package com.mantz_it.rfanalyzer.source;
import android.content.Context;
import com.mantz_it.rfanalyzer.network.RtlTcpConnection;
import java.util.concurrent.*;
/** Root-free CU8 source. TCP reads stay off the UI and scheduler threads. */
public final class NetworkIQSource implements IQSourceInterface {
 public static final int[] RATES={250000,1024000,1280000,1536000,1792000,1920000,2048000,2160000,2400000,2500000,2560000,2880000,3200000,7680000};
 private final String host;private final int port,gain;private final boolean agc;
 private volatile long frequency;private volatile int rate;private volatile boolean closed=false,running=false;
 private final RtlTcpConnection connection=new RtlTcpConnection();private Callback callback;
 private final ArrayBlockingQueue<byte[]> filled=new ArrayBlockingQueue<>(32),pool=new ArrayBlockingQueue<>(32);
 private final NetworkIQConverter converter=new NetworkIQConverter();
 public NetworkIQSource(String host,int port,long frequency,int rate,int gain,boolean agc){this.host=host;this.port=port;this.frequency=frequency;this.rate=rate;this.gain=gain;this.agc=agc;converter.setFrequency(frequency);converter.setSampleRate(rate);for(int i=0;i<32;i++)pool.add(new byte[getPacketSize()]);}
 public boolean open(Context context,Callback cb){callback=cb;new Thread(()->{try{connection.connect(host,port,frequency,rate,gain,agc);byte[] first=pool.take();connection.read(first);filled.put(first);if(!closed)cb.onIQSourceReady(this);}catch(Exception e){if(!closed)cb.onIQSourceError(this,host+":"+port+" — "+e.getMessage());close();}},"RTL-TCP-connect").start();return true;}
 public boolean isOpen(){return !closed&&connection.isOpen();}
 public boolean close(){closed=true;running=false;try{connection.close();}catch(Exception ignored){}filled.clear();return true;}
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
 public byte[] getPacket(int timeout){try{return filled.poll(timeout,TimeUnit.MILLISECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();return null;}}
 public void returnPacket(byte[] packet){pool.offer(packet);}
 public void startSampling(){if(running||closed)return;running=true;new Thread(()->{try{while(running&&!closed){byte[] b=pool.poll(500,TimeUnit.MILLISECONDS);if(b==null)continue;connection.read(b);while(running&&!closed&&!filled.offer(b,500,TimeUnit.MILLISECONDS)){} }}catch(Exception e){if(!closed)callback.onIQSourceError(this,"RTL-TCP: "+e.getMessage());}finally{close();}},"RTL-TCP-IQ").start();}
 public void stopSampling(){close();}
 public int fillPacketIntoSamplePacket(byte[] p,SamplePacket s){return converter.fillPacketIntoSamplePacket(p,s);}
 public int mixPacketIntoSamplePacket(byte[] p,SamplePacket s,long f){return converter.mixPacketIntoSamplePacket(p,s,f);}
}
