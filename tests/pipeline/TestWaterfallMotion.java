// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
import com.mantz_it.rfanalyzer.ui.WaterfallMotion;
public class TestWaterfallMotion {
 public static void main(String[] args){
  WaterfallMotion a=new WaterfallMotion(), b=new WaterfallMotion();
  long start=1_000_000_000L; double previous=0;
  for(int frame=0;frame<600;frame++){
   long now=start+frame*10_000_000L;
   if(frame%2==0)a.append(now);
   if(frame%6==4)for(int k=2;k>=0;k--)b.append(now-k*20_000_000L);
   double shownA=(frame/2+1)-a.lag(now);
   int receivedB=(frame+2)/6*3;
   double shownB=receivedB-b.lag(now);
   if(frame>20 && Math.abs(shownA-shownB)>0.0001)throw new AssertionError("batch arrival changed scroll");
   if(shownA+1e-8<previous || shownA>frame/2+1)throw new AssertionError("invalid scroll");
   previous=shownA;
  }
  if(a.lag(start+10_000_000_000L)!=0)throw new AssertionError("does not settle");
  a.reset();if(a.lag(start+11_000_000_000L)!=0)throw new AssertionError("reset retained history");
  System.out.println("PASS: timestamp scroll is identical for individual/batched rows, monotonic and bounded by real measurements");
 }
}
