// SPDX-License-Identifier: GPL-3.0-or-later
package com.mantz_it.rfanalyzer.radar;

import java.util.*;

/** Streaming, dependency-free CI8 decoders for the 7.68 MS/s LG modem. */
public final class RadarDecoder {
    public static final int RATE = 7680000;
    public interface Listener { void frame(byte[] frame, char channel); }
    public interface Decoder { void feed(byte[] iq, int len); void reset(); }
    static int bits(byte[] a, int from, int count) {
        int n=0; for(int i=from;i<from+count;i++) n=(n<<1)|((a[i>>>3]>>>(7-(i&7)))&1); return n;
    }
    static int signed(byte[] a,int from,int count) { int n=bits(a,from,count); return (n<<(32-count))>>(32-count); }
    static String text6(byte[] a,int from,int count) {
        StringBuilder s=new StringBuilder(); for(int i=0;i<count;i++){int x=bits(a,from+6*i,6); s.append((char)(x<32?x+64:x));} return s.toString().replace('@',' ').trim();
    }
    static String hex(byte[] a) {StringBuilder s=new StringBuilder(); for(byte b:a)s.append(String.format(Locale.US,"%02X",b&255));return s.toString();}
    public static int crc24(byte[] a) {
        int c=0; for(int i=0;i<a.length*8;i++){int top=(c>>>23)^bits(a,i,1);c=(c<<1)&0xffffff;if(top!=0)c^=0xfff409;}return c;
    }
    public static final class Adsb implements Decoder {
        private static final int FRAME=922; // ceil(120us * 7.68)
        private static final int[] EDGES=new int[241];
        static { for(int i=0;i<EDGES.length;i++)EDGES[i]=(int)Math.round(i*3.84); }
        private final Listener sink;
        private int[] mag=new int[FRAME+16384], prefix=new int[mag.length+1];
        private int carry=0;
        public Adsb(Listener sink){this.sink=sink;}
        public void reset(){carry=0;}
        private int e(int start,int half){return prefix[start+EDGES[half+1]]-prefix[start+EDGES[half]];}
        public void feed(byte[] iq,int len){
            if(len<0 || len>iq.length || (len&1)!=0)throw new IllegalArgumentException("CI8 length");
            int n=carry+len/2; if(n>mag.length){mag=Arrays.copyOf(mag,n);prefix=new int[n+1];}
            for(int i=0,k=carry;i<len;i+=2,k++){int r=iq[i],q=iq[i+1];mag[k]=r*r+q*q;}
            prefix[0]=0;for(int i=0;i<n;i++)prefix[i+1]=prefix[i]+mag[i];
            int i=0; byte[] frame=new byte[14];
            for(;i+FRAME<=n;i++){
                int a=e(i,0),b=e(i,2),c=e(i,7),d=e(i,9);
                int min=Math.min(Math.min(a,b),Math.min(c,d));
                if(min<16 || min*2<=e(i,1)*3 || min*2<=e(i,3)*3 || min*2<=e(i,8)*3)continue;
                int off=e(i,4)+e(i,5)+e(i,6)+e(i,10)+e(i,11)+e(i,12)+e(i,13)+e(i,14)+e(i,15);
                if((a+b+c+d)*9<off*8)continue;
                Arrays.fill(frame,(byte)0);
                for(int bit=0;bit<112;bit++){
                    int j=16+2*bit; // compare MEAN energy for unequal rounded chip widths
                    int left=e(i,j)*(EDGES[j+2]-EDGES[j+1]);
                    int right=e(i,j+1)*(EDGES[j+1]-EDGES[j]);
                    if(left>right)frame[bit>>>3]|=1<<(7-(bit&7));
                    if(bit==4 && (frame[0]&0xf8)!=0x88 && (frame[0]&0xf8)!=0x90)break;
                }
                int df=(frame[0]&255)>>>3;
                if((df==17||df==18)&&crc24(frame)==0){sink.frame(frame.clone(),' ');i+=FRAME-1;}
            }
            carry=n-i;System.arraycopy(mag,i,mag,0,carry);
        }
    }
    /** CRC-16/X.25, bytes in normal order, bits transmitted least significant first. */
    public static int fcs(byte[] a,int len){int crc=0xffff;for(int i=0;i<len;i++){crc^=a[i]&255;for(int j=0;j<8;j++)crc=(crc>>>1)^((crc&1)!=0?0x8408:0);}return crc^0xffff;}
    static final class Hdlc {
        final Listener sink; final char ch; int shift, seen, count; boolean active;
        final byte[] raw=new byte[4096]; final byte[] data=new byte[512];
        Hdlc(Listener s,char c){sink=s;ch=c;}
        void reset(){shift=seen=count=0;active=false;}
        void bit(int b){
            shift=((shift<<1)|b)&255;if(seen<8)seen++;
            if(active){if(count==raw.length){active=false;count=0;}else raw[count++]=(byte)b;}
            if(seen==8&&shift==0x7e){if(active&&count>8)finish(count-8);active=true;count=0;}
        }
        void finish(int n){
            int ones=0,k=0,acc=0;
            for(int i=0;i<n;i++){
                int b=raw[i];if(b==0&&ones==5){ones=0;continue;}if(b==1){if(++ones>5)return;}else ones=0;
                acc|=b<<(k&7);k++;if((k&7)==0){if(k/8>data.length)return;data[k/8-1]=(byte)acc;acc=0;}
            }
            if((k&7)!=0||k<56)return;int bytes=k/8,payload=bytes-2;
            if(fcs(data,payload)!=((data[payload]&255)|((data[payload+1]&255)<<8)))return;
            int type=bits(data,0,6);if(type<1||type>27)return;
            sink.frame(Arrays.copyOf(data,payload),ch);
        }
    }
    /** Two channels: 7.68M -> 192k (boxcar), DDC +/-25k, FIR -> 48k, discriminator/NRZI/HDLC. */
    public static final class Ais implements Decoder {
        final Channel a,b; int sumI,sumQ,count;
        public Ais(Listener s){this(s,0);}
        public Ais(Listener s,int offsetHz){
            // offsetHz is measured carrier offset relative to nominal channel center.
            a=new Channel(s,'A',-25000+offsetHz);b=new Channel(s,'B',25000+offsetHz);
        }
        public void reset(){sumI=sumQ=count=0;a.reset();b.reset();}
        public void feed(byte[] iq,int len){
            if(len<0||len>iq.length||(len&1)!=0)throw new IllegalArgumentException("CI8 length");
            for(int i=0;i<len;i+=2){sumI+=iq[i];sumQ+=iq[i+1];if(++count==40){a.sample(sumI,sumQ);b.sample(sumI,sumQ);sumI=sumQ=count=0;}}
        }
    }
    static final class Channel {
        static final int TAPS=63;
        static final float[] FIR=new float[TAPS];
        static {double total=0;for(int i=0;i<TAPS;i++){int x=i-TAPS/2;double w=.54-.46*Math.cos(2*Math.PI*i/(TAPS-1));double v=(x==0?2*14000.0/192000:Math.sin(2*Math.PI*14000*x/192000)/(Math.PI*x))*w;FIR[i]=(float)v;total+=v;}for(int i=0;i<TAPS;i++)FIR[i]/=total;}
        final Hdlc[] lanes=new Hdlc[5]; final boolean[] prev=new boolean[5],have=new boolean[5];
        final float[] re=new float[TAPS*2],im=new float[TAPS*2],disc=new float[5];
        final float stepR,stepI; float oscR=1,oscI,priorR,priorI; int pos,dec,dcpos,tick; float dsum; long sampleNo,lastFrameAt=-10000; byte[] lastFrame;
        Channel(Listener out,char ch,int hz){double w=-2*Math.PI*hz/192000.0;stepR=(float)Math.cos(w);stepI=(float)Math.sin(w);
            Listener dedup=(f,c)->{if(lastFrame==null||!Arrays.equals(f,lastFrame)||sampleNo-lastFrameAt>4800){lastFrame=f;lastFrameAt=sampleNo;out.frame(f,c);}};
            for(int i=0;i<5;i++)lanes[i]=new Hdlc(dedup,ch);
        }
        void reset(){Arrays.fill(re,0);Arrays.fill(im,0);Arrays.fill(disc,0);Arrays.fill(have,false);for(Hdlc h:lanes)h.reset();pos=dec=dcpos=tick=0;oscR=1;oscI=priorR=priorI=dsum=0;sampleNo=0;lastFrame=null;lastFrameAt=-10000;}
        void sample(float r,float q){
            float x=r*oscR-q*oscI,y=r*oscI+q*oscR;
            float next=oscR*stepR-oscI*stepI;oscI=oscR*stepI+oscI*stepR;oscR=next;
            if((++tick&4095)==0){float norm=(float)Math.sqrt(oscR*oscR+oscI*oscI);oscR/=norm;oscI/=norm;}
            re[pos]=re[pos+TAPS]=x;im[pos]=im[pos+TAPS]=y;if(++pos==TAPS)pos=0;
            if(++dec!=4)return;dec=0;float fr=0,fi=0;
            for(int j=0;j<TAPS;j++){fr+=re[pos+j]*FIR[j];fi+=im[pos+j]*FIR[j];}
            // Quadrature frequency discriminator. atan2 avoids amplitude-dependent thresholds.
            float z=(float)Math.atan2(priorR*fi-priorI*fr,priorR*fr+priorI*fi);priorR=fr;priorI=fi;
            dsum+=z-disc[dcpos];disc[dcpos]=z;int lane=dcpos;if(++dcpos==5)dcpos=0;sampleNo++;
            boolean sign=dsum>=0;if(have[lane])lanes[lane].bit(sign==prev[lane]?1:0);prev[lane]=sign;have[lane]=true;
        }
    }
    public static final class Target {
        public final String id; public String name=""; public double lat=Double.NaN,lon=Double.NaN,speed=Double.NaN,course=Double.NaN,altitude=Double.NaN;
        public long last,positionTime,count;public char channel; public int type; public String status="";
        double[] cprLat=new double[2],cprLon=new double[2];long[] cprTime=new long[2];
        Target(String id){this.id=id;}
        Target copy(){Target t=new Target(id);t.name=name;t.lat=lat;t.lon=lon;t.speed=speed;t.course=course;t.altitude=altitude;t.last=last;t.positionTime=positionTime;t.count=count;t.channel=channel;t.type=type;t.status=status;return t;}
    }
    public static final class Tracker {
        final Map<String,Target> targets=new HashMap<>(); public long frames;
        public synchronized void clear(){targets.clear();frames=0;}
        public synchronized long frameCount(){return frames;}
        public synchronized List<Target> snapshot(long now){targets.values().removeIf(t->now-t.last>900000);List<Target> copy=new ArrayList<>();for(Target t:targets.values())copy.add(t.copy());copy.sort((a,b)->Long.compare(b.last,a.last));return copy;}
        public synchronized void update(byte[] f,char ch,long now){
            if(ch==' '){if(f.length!=14||crc24(f)!=0)return;int df=bits(f,0,5);if(df!=17&&df!=18)return;
                // DF18 addresses may be non-ICAO; keep a separate namespace per CF.
                String id=String.format(Locale.US,"%06X",bits(f,8,24));if(df==18)id="DF18/"+bits(f,5,3)+"/"+id;
                Target t=targets.computeIfAbsent(id,Target::new);touch(t,ch,now);int tc=bits(f,32,5);t.type=tc;
                if(tc>=1&&tc<=4)t.name=text6(f,40,8);
                if(tc>=9&&tc<=18){int alt=bits(f,40,12);if((alt&16)!=0)t.altitude=25*((alt&15)|((alt&0xfe0)>>1))-1000;
                    int odd=bits(f,53,1);t.cprLat[odd]=bits(f,54,17)/131072.0;t.cprLon[odd]=bits(f,71,17)/131072.0;t.cprTime[odd]=now;position(t,odd,now);}
                if(tc==19){int subtype=bits(f,37,3);if(subtype==1||subtype==2){int ew=bits(f,46,10),ns=bits(f,57,10);if(ew!=0&&ns!=0){double vx=(ew-1)*(bits(f,45,1)==1?-1:1),vy=(ns-1)*(bits(f,56,1)==1?-1:1);t.speed=Math.hypot(vx,vy)*(subtype==2?4:1);t.course=(Math.toDegrees(Math.atan2(vx,vy))+360)%360;}}}
                if(tc==28&&bits(f,37,3)==1){int em=bits(f,40,3);t.status=em==0?"":"Emergency code "+em;}
            }else{if(f.length<5)return;int type=bits(f,0,6),mmsi=bits(f,8,30);if(type<1||type>27||mmsi==0)return;
                Target t=targets.computeIfAbsent(String.format(Locale.US,"%09d",mmsi),Target::new);touch(t,ch,now);t.type=type;
                if((type==1||type==2||type==3)&&f.length*8>=168){t.status="Nav status "+bits(f,38,4);aisPos(t,f,61,89,50,116,now);}
                else if((type==18||type==19)&&f.length*8>=168){aisPos(t,f,57,85,46,112,now);if(type==19&&f.length*8>=263)t.name=text6(f,143,20);}
                else if(type==5&&f.length*8>=232)t.name=text6(f,112,20);
                else if(type==24&&f.length*8>=160&&bits(f,38,2)==0)t.name=text6(f,40,20);
            }
        }
        private void touch(Target t,char ch,long now){frames++;t.count++;t.last=now;t.channel=ch;}
        private static void aisPos(Target t,byte[] f,int lon,int lat,int sog,int cog,long now){double x=signed(f,lon,28)/600000.0,y=signed(f,lat,27)/600000.0;if(Math.abs(x)<=180&&Math.abs(y)<=90){t.lon=x;t.lat=y;t.positionTime=now;}int s=bits(f,sog,10),c=bits(f,cog,12);t.speed=s<1023?s/10.0:Double.NaN;t.course=c<3600?c/10.0:Double.NaN;}
        static int nl(double lat){lat=Math.abs(lat);if(lat>=87)return lat==87?2:1;double c=Math.cos(Math.toRadians(lat));double x=1-(1-Math.cos(Math.PI/30))/(c*c);return Math.max(1,(int)Math.floor(2*Math.PI/Math.acos(Math.max(-1,Math.min(1,x)))));}
        static void position(Target t,int odd,long now){if(t.cprTime[0]==0||t.cprTime[1]==0||Math.abs(t.cprTime[0]-t.cprTime[1])>10000)return;
            int j=(int)Math.floor(59*t.cprLat[0]-60*t.cprLat[1]+.5);double a=6*(Math.floorMod(j,60)+t.cprLat[0]),b=360.0/59*(Math.floorMod(j,59)+t.cprLat[1]);if(a>=270)a-=360;if(b>=270)b-=360;if(Math.abs(a)>90||Math.abs(b)>90||nl(a)!=nl(b))return;
            double lat=odd==0?a:b;int zones=nl(lat),n=Math.max(1,zones-odd),m=(int)Math.floor(t.cprLon[0]*(zones-1)-t.cprLon[1]*zones+.5);double lon=360.0/n*(Math.floorMod(m,n)+t.cprLon[odd]);if(lon>=180)lon-=360;
            // Reject implausible jumps when a previous position is fresh.
            if(!Double.isNaN(t.lat)&&now-t.positionTime<60000){double dlat=lat-t.lat,dlon=Math.IEEEremainder(lon-t.lon,360);double km=111*Math.hypot(dlat,dlon*Math.cos(Math.toRadians(lat)));if(km>15+(now-t.positionTime)*.0005)return;}
            t.lat=lat;t.lon=lon;t.positionTime=now;
        }
    }
}
