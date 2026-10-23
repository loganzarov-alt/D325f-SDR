#include "spectrum_math.h"
#include <cassert>
#include <chrono>
#include <cstdio>
#include <random>
#include <vector>

// 1.1.9 detector retained as an independent numeric/performance reference.
double referencePower(float db) {
    static const auto table=[] {
        std::array<double,801> t{};
        for(int i=0;i<=800;++i) t[i]=std::pow(10.0,(-160.0+i*0.25)/10.0);
        return t;
    }();
    if(!std::isfinite(db)) return 1e-16;
    double at=std::max(0.0,std::min(800.0,(double(db)+160.0)*4));
    int lo=int(at),hi=std::min(800,lo+1);
    return table[lo]+(at-lo)*(table[hi]-table[lo]);
}
void reference(const float* bins,int n,double first,double step,float* row,int width) {
    for(int x=0;x<width;++x) {
        double left=first+x*step,a=std::max(0.0,left),b=std::min(double(n),left+step);
        if(b<=a) { row[x]=-160; continue; }
        double sum=0;
        for(int i=int(a);i<b;++i)
            sum+=(std::min(b,i+1.0)-std::max(a,double(i)))*referencePower(bins[i]);
        row[x]=float(10*std::log10(std::max(1e-16,sum/(b-a))));
    }
}
int main() {
    std::mt19937 gen(120);
    std::uniform_real_distribution<float> levels(-180,50);
    std::vector<float> bins(131072),expected(480),actual(480);
    for(auto& x:bins) x=levels(gen);
    bins[7]=NAN; bins[33]=INFINITY;
    float error=0;
    for(double first:{-100.37,0.0,0.5,36000.123,131070.0})
        for(double step:{0.1,1.0,2.731,131072.0/480,131072.0}) {
            reference(bins.data(),bins.size(),first,step,expected.data(),480);
            spectrum::rasterize(bins.data(),bins.size(),first,step,actual.data(),480);
            for(int i=0;i<480;++i) error=std::max(error,std::abs(actual[i]-expected[i]));
        }
    assert(error<0.0001f);
    volatile float sink=0;
    auto bench=[&](bool optimized) {
        auto begin=std::chrono::steady_clock::now();
        for(int i=0;i<500;++i) {
            if(optimized) spectrum::rasterize(bins.data(),bins.size(),0,131072.0/480,actual.data(),480);
            else reference(bins.data(),bins.size(),0,131072.0/480,actual.data(),480);
            sink=actual[i%480];
        }
        return std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-begin).count()/500;
    };
    double oldMs=bench(false),newMs=bench(true);
    printf("PASS: detector max error %.8f dB; 131072-bin reduction %.3f -> %.3f ms, %.2fx (host only)\n",error,oldMs,newMs,oldMs/newMs);
    (void)sink;
}
