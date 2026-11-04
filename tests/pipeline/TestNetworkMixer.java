// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
import com.mantz_it.rfanalyzer.source.*;
public class TestNetworkMixer {
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args)throws Exception{
  NetworkIQConverter c=new NetworkIQConverter();c.setSampleRate(250000);c.setFrequency(197000000);
  byte[] constant=new byte[256];for(int i=0;i<128;i++){constant[2*i]=(byte)192;constant[2*i+1]=(byte)128;}
  SamplePacket out=new SamplePacket(128);double phase=0;
  long[] offsets={0,1,-1,125000,-125000,250000,-500000,4000000000L,-4000000000L};
  for(int turn=0;turn<2000;turn++){
   long offset=offsets[turn%offsets.length];out.setSize(0);
   check(c.mixPacketIntoSamplePacket(constant,out,197000000-offset)==128);
   double step=2*Math.PI*(offset%250000)/250000;
   for(int i=0;i<128;i++){
    check(Math.abs(out.re()[i]-.5*Math.cos(phase))<2e-5);
    check(Math.abs(out.im()[i]-.5*Math.sin(phase))<2e-5);
    phase=Math.IEEEremainder(phase+step,2*Math.PI);
   }
  }
  for(String field:new String[]{"cosineRealLookupTable","cosineImagLookupTable"}){
   var f=IQConverter.class.getDeclaredField(field);f.setAccessible(true);check(f.get(c)==null);
  }
  out.setSize(0);c.fillPacketIntoSamplePacket(new byte[]{0,127,(byte)128,(byte)255},out);
  check(out.size()==2&&out.re()[0]==-1&&out.re()[1]==0&&out.im()[1]==127/128f);
  System.out.println("PASS network mixer: 2000 retunes, signed/large offsets, phase continuity, CU8 endpoints, no mixer tables");
 }
}
