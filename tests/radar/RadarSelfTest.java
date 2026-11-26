// SPDX-License-Identifier: GPL-3.0-or-later
package com.mantz_it.rfanalyzer.radar;
import java.util.*;
public class RadarSelfTest {
    static void check(boolean x,String what){if(!x)throw new AssertionError(what);}
    static byte[] hex(String s){byte[] b=new byte[s.length()/2];for(int i=0;i<b.length;i++)b[i]=(byte)Integer.parseInt(s.substring(i*2,i*2+2),16);return b;}
    static void set(byte[] b,int at,int n,int v){for(int j=0;j<n;j++)if(((v>>>(n-j-1))&1)!=0)b[(at+j)/8]|=1<<(7-(at+j)%8);}
    static byte[] ppm(byte[] frame,double frac){byte[] iq=new byte[2*2500];Random rand=new Random(59);for(int i=0;i<iq.length;i++)iq[i]=(byte)(rand.nextInt(7)-3);for(int i=0;i<2500;i++){double t=(i+0.5-541-frac)/7.68;if(t<0||t>=120)continue;boolean high;if(t<8)high=t<.5||(t>=1&&t<1.5)||(t>=3.5&&t<4)||(t>=4.5&&t<5);else{int bit=(int)(t-8);high=(RadarDecoder.bits(frame,bit,1)==1)==((t-8-bit)<.5);}if(high){iq[2*i]+=64;iq[2*i+1]+=35;}}return iq;}
    static void chunk(RadarDecoder.Decoder d,byte[] raw){for(int i=0;i<raw.length;){int n=Math.min(1022,raw.length-i);d.feed(Arrays.copyOfRange(raw,i,i+n),n);i+=n;}}
    static List<Integer> hdlc(byte[] p,boolean corrupt){List<Integer> out=new ArrayList<>();for(int i=0;i<24;i++)out.add(i&1);flag(out);int c=RadarDecoder.fcs(p,p.length);int ones=0;for(int i=0;i<p.length+2;i++){int v=i<p.length?p[i]&255:i==p.length?c&255:c>>>8;if(corrupt&&i==3)v^=1;for(int j=0;j<8;j++){int b=(v>>>j)&1;out.add(b);if(b==1){if(++ones==5){out.add(0);ones=0;}}else ones=0;}}flag(out);return out;}
    static void flag(List<Integer> l){for(int i=0;i<8;i++)l.add((0x7e>>>i)&1);}
    // Synthetic continuous-phase Gaussian-filtered FSK, 800 CI8 samples/symbol.
    static byte[] gmsk(List<Integer> bits,int offset){int pad=24,n=(bits.size()+pad*2)*800;double[] levels=new double[bits.size()+pad*2];int sign=1;for(int i=0;i<levels.length;i++){if(i>=pad&&i<pad+bits.size()){if(bits.get(i-pad)==0)sign=-sign;}else sign=(i&1)==0?1:-1;levels[i]=sign;}
        byte[] raw=new byte[n*2];double phase=0;Random random=new Random(27);double sigma=Math.sqrt(Math.log(2))/(2*Math.PI*.4);for(int i=0;i<n;i++){double t=(i+.5)/800.0;int center=(int)t;double wsum=0,fsum=0;for(int j=Math.max(0,center-2);j<Math.min(levels.length,center+3);j++){// approximate Gaussian-smoothed rectangular pulse by integrated weights
            double w=0;for(int k=0;k<5;k++){double u=(j+(k+.5)/5-t)/sigma;w+=Math.exp(-.5*u*u);}wsum+=w;fsum+=w*levels[j];}
            phase+=2*Math.PI*(offset+2400*fsum/wsum)/RadarDecoder.RATE;raw[i*2]=(byte)Math.round(50*Math.cos(phase)+random.nextInt(5)-2);raw[i*2+1]=(byte)Math.round(50*Math.sin(phase)+random.nextInt(5)-2);}
        return raw;}
    public static void main(String[] args){byte[] even=hex("8D40621D58C382D690C8AC2863A7"),odd=hex("8D40621D58C386435CC412692AD6");check(RadarDecoder.crc24(even)==0&&RadarDecoder.crc24(odd)==0,"CRC24 reference");
        for(double f:new double[]{0,.25,.5,.75}){List<byte[]> frames=new ArrayList<>();RadarDecoder.Adsb dec=new RadarDecoder.Adsb((b,c)->frames.add(b));chunk(dec,ppm(even,f));check(frames.size()==1&&Arrays.equals(even,frames.get(0)),"ADSB split and offset "+f);}
        byte[] invalid=even.clone();invalid[6]^=1;List<byte[]> out=new ArrayList<>();chunk(new RadarDecoder.Adsb((b,c)->out.add(b)),ppm(invalid,0));check(out.isEmpty(),"CRC rejection");
        RadarDecoder.Tracker aircraft=new RadarDecoder.Tracker();aircraft.update(odd,' ',1000);aircraft.update(even,' ',2000);RadarDecoder.Target plane=aircraft.snapshot(2000).get(0);check(Math.abs(plane.lat-52.257202)<.0001&&Math.abs(plane.lon-3.919373)<.0001&&plane.altitude==38000,"CPR and altitude");
        check(RadarDecoder.fcs("123456789".getBytes(),9)==0x906e,"CRC X25 check vector");byte[] ais=new byte[21];set(ais,0,6,1);set(ais,8,30,123456789);set(ais,38,4,0);set(ais,50,10,124);set(ais,61,28,-12212345);set(ais,89,27,30123456);set(ais,116,12,1530);
        List<byte[]> packets=new ArrayList<>();RadarDecoder.Hdlc h=new RadarDecoder.Hdlc((b,c)->packets.add(b),'A');for(int v:hdlc(ais,false))h.bit(v);check(packets.size()==1&&Arrays.equals(ais,packets.get(0)),"HDLC FCS and destuff");for(int v:hdlc(ais,true))h.bit(v);check(packets.size()==1,"HDLC CRC rejection");
        RadarDecoder.Tracker ships=new RadarDecoder.Tracker();ships.update(ais,'A',1000);RadarDecoder.Target ship=ships.snapshot(1000).get(0);check(ship.id.equals("123456789")&&Math.abs(ship.lon-(-12212345/600000.0))<1e-6&&ship.speed==12.4&&ship.course==153,"AIS fields");
        byte[] a=gmsk(hdlc(ais,false),-25000),b=gmsk(hdlc(ais,false),25000);List<String> decoded=new ArrayList<>();RadarDecoder.Ais decoder=new RadarDecoder.Ais((f,c)->{if(Arrays.equals(f,ais))decoded.add(String.valueOf(c));});chunk(decoder,a);check(decoded.equals(Arrays.asList("A")),"AIS A GMSK: "+decoded);decoder.reset();chunk(decoder,b);check(decoded.equals(Arrays.asList("A","B")),"AIS B GMSK: "+decoded);
        byte[] mix=new byte[a.length];for(int i=0;i<mix.length;i++)mix[i]=(byte)((a[i]+b[i])/2);decoded.clear();decoder.reset();chunk(decoder,mix);check(decoded.size()==2&&decoded.contains("A")&&decoded.contains("B"),"AIS concurrent A+B: "+decoded);
        System.out.println("PASS: ADS-B CRC, IQ pulse demodulation at 4 phases, block boundaries, CRC rejection, CPR/altitude; AIS FCS, HDLC, CRC rejection, fields, synthetic GMSK A/B including concurrent A+B.");
    }
}
