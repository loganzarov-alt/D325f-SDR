// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
import com.mantz_it.rfanalyzer.network.*;
import java.net.*;
import java.io.*;
import java.util.concurrent.*;
public class TestRtlTcp {
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args)throws Exception {
  RtlTcpControl control=new RtlTcpControl(1700000000,2560000);
  ByteArrayOutputStream wire=new ByteArrayOutputStream();DataOutputStream w=new DataOutputStream(wire);
  w.writeByte(1);w.writeInt(437800000);w.writeByte(2);w.writeInt(2500000);w.writeByte(4);w.writeInt(-100);w.writeByte(5);w.writeInt(-20);w.writeByte(14);w.writeInt(1);
  InputStream fragmented=new FilterInputStream(new ByteArrayInputStream(wire.toByteArray())) { public int read(byte[] b,int off,int n)throws IOException{return super.read(b,off,Math.min(1,n));} };
  try{control.read(fragmented);}catch(EOFException expected){}
  var state=control.snapshot();check(state.rate==2500000&&state.frequency==437800000&&state.gain==-100&&state.ppm==-20);check(state.correctedFrequency()==437791244);check(!state.diagnostic.isEmpty());
  control.apply(13,28);check(control.snapshot().gain==496);
  boolean rejected=false;try{control.apply(2,0);}catch(IOException expected){rejected=true;}check(rejected);
  // Actual client and actual command parser exchange fragmented greetings/IQ on loopback.
  try(ServerSocket listener=new ServerSocket(0)) {
   ExecutorService exec=Executors.newSingleThreadExecutor();
   Future<?> server=exec.submit(()->{try{for(int iteration=0;iteration<2;iteration++)try(Socket socket=listener.accept()){
    socket.setSoTimeout(5000);
    ByteArrayOutputStream hello=new ByteArrayOutputStream();RtlTcpControl.greeting(hello);for(byte b:hello.toByteArray()){socket.getOutputStream().write(b);socket.getOutputStream().flush();}
    DataInputStream input=new DataInputStream(socket.getInputStream());RtlTcpControl commands=new RtlTcpControl(1,1280000);
    for(int i=0;i<5;i++)commands.apply(input.readUnsignedByte(),Integer.toUnsignedLong(input.readInt()));
    check(commands.snapshot().rate==2400000);check(commands.snapshot().frequency==145800000);check(commands.snapshot().agc);
    byte[] bytes={0,127,(byte)128,(byte)255,42,99};for(byte b:bytes){socket.getOutputStream().write(b);socket.getOutputStream().flush();}
   }}catch(Exception e){throw new RuntimeException(e);}});
   try{for(int i=0;i<2;i++)try(RtlTcpConnection client=new RtlTcpConnection()) {client.connect("127.0.0.1",listener.getLocalPort(),145800000,2400000,0,true);byte[] packet=new byte[6];client.read(packet);check((packet[2]&255)==128&&(packet[3]&255)==255&&packet[5]==99);}
   server.get(10,TimeUnit.SECONDS);}finally{exec.shutdownNow();}
  }
  // A silent or truncated server must fail at a bounded, named startup stage.
  for(int mode=0;mode<3;mode++) {
   final int scenario=mode;
   try(ServerSocket listener=new ServerSocket(0)) {
    ExecutorService exec=Executors.newSingleThreadExecutor();
    Future<?> server=exec.submit(()->{try(Socket socket=listener.accept()){
     socket.setSoTimeout(2000);
     if(scenario==1)RtlTcpControl.greeting(socket.getOutputStream());
     if(scenario==2){socket.getOutputStream().write(new byte[]{82,84});return;}
     while(socket.getInputStream().read()!=-1){}
    }catch(IOException e){throw new RuntimeException(e);}});
    long begin=System.nanoTime();
    try(RtlTcpConnection client=new RtlTcpConnection(150,150,150)) {
     try{client.connect("127.0.0.1",listener.getLocalPort(),145800000,250000,0,false);client.read(new byte[8]);throw new AssertionError("silent server accepted");}
     catch(IOException expected){check(expected.getMessage().contains(scenario==1?"First IQ block":"RTL0 greeting"));}
    } finally {server.get(3,TimeUnit.SECONDS);exec.shutdownNow();}
    check(System.nanoTime()-begin<TimeUnit.SECONDS.toNanos(3));
   }
  }
  // Stop closes a socket even while a greeting read is blocked.
  try(ServerSocket listener=new ServerSocket(0);RtlTcpConnection client=new RtlTcpConnection()) {
   ExecutorService exec=Executors.newSingleThreadExecutor();
   Future<?> connecting=exec.submit(()->{try{client.connect("127.0.0.1",listener.getLocalPort(),145800000,250000,0,false);throw new AssertionError("cancel failed");}catch(IOException expected){}});
   try(Socket peer=listener.accept()){client.close();connecting.get(1,TimeUnit.SECONDS);check(!client.isOpen());}
   finally{exec.shutdownNow();}
  }
  System.out.println("PASS RTL-TCP: partial frames, signed controls, rate checks, paired socket IQ, reconnect, startup deadlines, truncation, cancellation");
 }
}
