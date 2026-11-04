// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
package com.mantz_it.rfanalyzer.network;
import java.io.*;
import java.net.*;
/** Cancellable RTL0/CU8 transport with bounded startup and fragmented-read support. */
public final class RtlTcpConnection implements Closeable {
 public interface Progress { void update(String stage, long bytes); }
 private final Socket socket=new Socket();
 private final int greetingTimeout, firstTimeout, packetTimeout;
 private volatile DataOutputStream output;
 private InputStream input;
 private boolean first=true;
 private volatile boolean ready=false;
 private long received=0;
 private Progress progress=(s,b)->{};
 public RtlTcpConnection(){this(8000,65000,10000);}
 public RtlTcpConnection(int greetingTimeout,int firstTimeout,int packetTimeout){this.greetingTimeout=greetingTimeout;this.firstTimeout=firstTimeout;this.packetTimeout=packetTimeout;}
 public void setProgress(Progress p){progress=p;}
 private void report(String s){progress.update(s,received);}
 public void connect(String host,int port,long frequency,int rate,int gain,boolean agc)throws IOException {
  if(host==null||host.trim().isEmpty()||port<1||port>65535)throw new IOException("Enter a valid server address and port");
  try {
   report("TCP: connecting to "+host.trim()+":"+port);
   socket.connect(new InetSocketAddress(host.trim(),port),8000);socket.setTcpNoDelay(true);
   input=new BufferedInputStream(socket.getInputStream(),65536);output=new DataOutputStream(socket.getOutputStream());
   report("TCP connected; waiting for RTL0 greeting");
   byte[] header=new byte[12];readExact(header,greetingTimeout,"RTL0 greeting",false);
   if(header[0]!='R'||header[1]!='T'||header[2]!='L'||header[3]!='0')throw new IOException("Not an rtl_tcp server: RTL0 greeting missing");
   report("RTL0 received; sending frequency, rate and gain");
   command(1,frequency);command(2,rate);command(3,agc?0:1);command(4,gain);command(8,agc?1:0);
   ready=true;report("Waiting for IQ from LG (up to 65 seconds)");
  } catch(IOException|RuntimeException e){close();throw e;}
 }
 public synchronized void command(int cmd,long value)throws IOException {
  DataOutputStream out=output;
  if(out!=null){out.writeByte(cmd);out.writeInt((int)value);out.flush();}
 }
 private void readExact(byte[] packet,int timeout,String stage,boolean iq)throws IOException {
  long deadline=System.nanoTime()+timeout*1000000L;int offset=0;
  while(offset<packet.length){
   long left=deadline-System.nanoTime();
   if(left<=0)throw new SocketTimeoutException(stage+": timeout after "+offset+"/"+packet.length+" bytes");
   socket.setSoTimeout((int)Math.max(1,Math.min(Integer.MAX_VALUE,(left+999999)/1000000L)));
   int n;
   try{n=input.read(packet,offset,packet.length-offset);}catch(SocketTimeoutException e){throw new SocketTimeoutException(stage+": timeout after "+offset+"/"+packet.length+" bytes");}
   if(n<0)throw new EOFException(stage+": server closed connection after "+offset+"/"+packet.length+" bytes");
   offset+=n;if(iq){received+=n;report("Receiving IQ");}
  }
 }
 public void read(byte[] packet)throws IOException {
  readExact(packet,first?firstTimeout:packetTimeout,first?"First IQ block":"IQ stream",true);first=false;
 }
 public boolean isOpen(){return ready&&!socket.isClosed();}
 @Override public void close()throws IOException {ready=false;socket.close();}
}
