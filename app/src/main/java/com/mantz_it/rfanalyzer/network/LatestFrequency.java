// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
package com.mantz_it.rfanalyzer.network;
import java.io.*;
import java.util.concurrent.TimeUnit;
/** A bounded mailbox: settle a drag for 180 ms, update at least every 500 ms. */
public final class LatestFrequency implements Closeable {
 public interface Sink {void tune(long frequency)throws IOException;}
 public interface Failure {void failed(IOException error);}
 private final Sink sink;private final Failure failure;private final Thread worker;
 private long requested,sent,firstChange,lastChange;private boolean closed;
 public LatestFrequency(long initial,Sink sink,Failure failure){this.requested=this.sent=initial;this.sink=sink;this.failure=failure;worker=new Thread(this::run,"RTL-TCP-tune");worker.start();}
 public synchronized void request(long value){if(closed||requested==value)return;if(requested==sent)firstChange=System.nanoTime();requested=value;lastChange=System.nanoTime();notifyAll();}
 private void run(){try{while(true){long value;
  synchronized(this){
   while(!closed&&requested==sent)wait();if(closed)return;
   long deadline=Math.min(lastChange+TimeUnit.MILLISECONDS.toNanos(180),firstChange+TimeUnit.MILLISECONDS.toNanos(500));
   long remaining=deadline-System.nanoTime();if(remaining>0){TimeUnit.NANOSECONDS.timedWait(this,remaining);continue;}
   value=requested;sent=value;
  }
  sink.tune(value);
 }}catch(InterruptedException e){Thread.currentThread().interrupt();}catch(IOException e){boolean report;synchronized(this){report=!closed;}if(report)failure.failed(e);}}
 @Override public synchronized void close(){closed=true;notifyAll();worker.interrupt();}
}
