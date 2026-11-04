// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
package com.mantz_it.rfanalyzer.network;
import java.io.*;
import java.net.*;
/** Android-independent transport used by the Pixel source and socket integration tests. */
public final class RtlTcpConnection implements Closeable {
 private final Socket socket=new Socket();private DataInputStream input;private DataOutputStream output;
 public void connect(String host,int port,long frequency,int rate,int gain,boolean agc)throws IOException {
  socket.connect(new InetSocketAddress(host,port),8000);socket.setTcpNoDelay(true);socket.setSoTimeout(60000);
  input=new DataInputStream(new BufferedInputStream(socket.getInputStream(),65536));output=new DataOutputStream(socket.getOutputStream());
  if(input.readInt()!=0x52544c30)throw new IOException("Not an rtl_tcp server (RTL0 greeting missing)");
  input.readInt();input.readInt();
  command(1,frequency);command(2,rate);command(3,agc?0:1);command(4,gain);command(8,agc?1:0);
 }
 public synchronized void command(int cmd,long value)throws IOException {if(output!=null){output.writeByte(cmd);output.writeInt((int)value);output.flush();}}
 public void read(byte[] packet)throws IOException {input.readFully(packet);}
 public boolean isOpen(){return socket.isConnected()&&!socket.isClosed();}
 @Override public void close()throws IOException {socket.close();}
}
