#include "bandwidth.h"
#include <vector>
#include <cstdio>
#include <cassert>
#include <chrono>
static double tone(int rate,double hz) {
 BandwidthFilter f(rate);std::vector<int8_t>x(32768);float r[16384],q[16384];double energy=0;int samples=0;
 for(int b=0;b<12;b++) {for(int k=0;k<16384;k++){double a=2*M_PI*hz*(b*16384+k)/7680000.;x[2*k]=std::lround(90*cos(a));x[2*k+1]=std::lround(90*sin(a));}int n=f.process(x.data(),16384,r,q,false);if(b>0)for(int k=0;k<n;k++){energy+=r[k]*r[k]+q[k]*q[k];samples++;}}
 return 10*log10(energy/samples);
}
int main(){
 for(int rate:{6144000,2560000,1280000}){
  double bw=rate==6144000?5000000:rate==2560000?2000000:1000000;
  double center=tone(rate,100000),edge=tone(rate,bw*.48),stop=tone(rate,rate*.5+(rate-bw)*.25);
  printf("rate=%d pass-edge=%.2f dB stop=%.2f dB\n",rate,edge-center,stop-center);
  assert(edge-center>-2.0);assert(stop-center<-25.0);
  std::vector<int8_t> input(32768);for(int k=0;k<32768;k++)input[k]=k%251-125;
  float ar[16384],ai[16384],br[16384],bi[16384];BandwidthFilter a(rate),b(rate);
  int n=a.process(input.data(),16384,ar,ai,false);int n1=b.process(input.data(),317,br,bi,false);int n2=b.process(input.data()+634,16384-317,br+n1,bi+n1,false);
  assert(n==n1+n2);for(int k=0;k<n;k++){assert(ar[k]==br[k]);assert(ai[k]==bi[k]);}
  BandwidthFilter inverted(rate);inverted.process(input.data(),16384,br,bi,true);for(int k=0;k<n;k++){assert(ar[k]==br[k]);assert(ai[k]==-bi[k]);}
  int total=0;auto start=std::chrono::steady_clock::now();for(int i=0;i<469;i++)total+=a.process(input.data(),16384,ar,ai,false);
  double ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count();printf("1 second IQ: filter %.2f ms, downstream samples %d (native full 7684096)\n",ms,total);
 }
 puts("PASS: passband, alias rejection, packet-boundary continuity, BT inversion and sample reduction");
}
