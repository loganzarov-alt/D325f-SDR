// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
import com.mantz_it.rfanalyzer.source.BtCwController;
public class TestBtController {
 public static void main(String[] args)throws Exception {
  BtCwController c=BtCwController.get(new android.content.Context());
  try {
   if(c.getChannel()!=30)throw new AssertionError("Default channel must be 30");
   if(c.getPower()!=9)throw new AssertionError("Default power must be max");
   long begin=System.nanoTime();
   c.configure(true);
   if((System.nanoTime()-begin)/1000000<550)throw new AssertionError("CW started before Bluetooth OFF");
   if(!c.queryStatus().contains("CHANNEL=30"))throw new AssertionError("Wrong CW channel");
   try { c.setChannel(78); throw new AssertionError("Live channel change accepted"); }
   catch(java.io.IOException expected) { }
   Thread.sleep(6000);
   if(!c.queryStatus().contains("STATE=RUNNING_ACK"))throw new AssertionError("Premature startup watchdog");
   for(int i=0;i<30;i++){c.packetReceived();Thread.sleep(100);}
   if(!c.queryStatus().contains("STATE=RUNNING_ACK"))throw new AssertionError("Live RX stopped");
   Thread.sleep(8000);
   String state=c.queryStatus();
   if(!state.contains("STATE=STOPPED")||!state.contains("RX_HEARTBEAT_LOST"))throw new AssertionError(state);
   c.stop();c.setChannel(78);c.configure(true);c.packetReceived();
   if(!c.queryStatus().contains("CHANNEL=78"))throw new AssertionError("Configured channel ignored");
   c.stop();
   if(!c.queryStatus().contains("STATE=STOPPED"))throw new AssertionError("Manual stop");
   c.setPower(3);
   java.lang.reflect.Constructor<BtCwController> ctor=BtCwController.class.getDeclaredConstructor(android.content.Context.class);
   ctor.setAccessible(true);
   BtCwController restored=ctor.newInstance(new android.content.Context());
   if(restored.getPower()!=3||restored.getChannel()!=78)throw new AssertionError("TX settings not persisted");
   System.out.println("PASS: actual Java controller tolerates slow startup, sustains IQ, stops on loss and restarts cleanly");
  } finally {c.stop();}
 }
}
