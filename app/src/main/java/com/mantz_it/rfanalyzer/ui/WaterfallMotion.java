package com.mantz_it.rfanalyzer.ui;

/** Timestamp-based playout of real rows, independent of their arrival in batches. */
public final class WaterfallMotion {
    private static final int CAPACITY = 256;
    private static final long DELAY_NS = 120_000_000L;
    private final long[] times = new long[CAPACITY];
    private long received;
    private double shown;
    public void reset() { received=0; shown=0; }
    public boolean isAnimating() { return received-shown>0.001; }
    public void append(long measuredNs) {
        if (received>0) measuredNs=Math.max(measuredNs,times[(int)((received-1)%CAPACITY)]+1);
        times[(int)(received%CAPACITY)]=measuredNs;
        received++;
    }
    public double lag(long now) {
        if (received==0) return 0;
        final long target=now-DELAY_NS;
        final long first=Math.max(0,received-CAPACITY);
        double next=first;
        for(long i=first;i<received;i++) {
            long at=times[(int)(i%CAPACITY)];
            if(at>target) {
                if(i>first) {
                    long previous=times[(int)((i-1)%CAPACITY)];
                    next=i+(target-previous)/(double)(at-previous);
                }
                break;
            }
            next=i+1;
        }
        shown=Math.max(shown,Math.min(received,next));
        return received-shown;
    }
}
