// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
import com.mantz_it.rfanalyzer.network.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
public class TestLatestFrequency {
 public static void main(String[] args)throws Exception{
  AtomicInteger calls=new AtomicInteger();AtomicLong last=new AtomicLong();CountDownLatch arrived=new CountDownLatch(1);
  try(LatestFrequency t=new LatestFrequency(100,v->{calls.incrementAndGet();last.set(v);arrived.countDown();},e->{throw new AssertionError(e);})){for(int i=101;i<=500;i++)t.request(i);
   if(!arrived.await(2,TimeUnit.SECONDS)||last.get()!=500||calls.get()!=1)throw new AssertionError("drag not coalesced");
  }
  CountDownLatch blocked=new CountDownLatch(1),release=new CountDownLatch(1),finalTune=new CountDownLatch(1);
  try(LatestFrequency t=new LatestFrequency(1,v->{if(v==2){blocked.countDown();try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}}if(v==999)finalTune.countDown();},e->{throw new AssertionError(e);})){t.request(2);if(!blocked.await(2,TimeUnit.SECONDS))throw new AssertionError();
   long begin=System.nanoTime();for(int i=3;i<=999;i++)t.request(i);if(System.nanoTime()-begin>TimeUnit.SECONDS.toNanos(1))throw new AssertionError("UI blocked by socket");
   release.countDown();if(!finalTune.await(2,TimeUnit.SECONDS))throw new AssertionError("last frequency lost");
  }finally{release.countDown();}
  System.out.println("PASS tuning: burst coalescing, bounded mailbox, nonblocking requests, final frequency delivered");
 }
}
