// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
package com.mantz_it.rfanalyzer.automation;
import java.io.IOException;
import java.io.OutputStream;
import java.util.function.BooleanSupplier;
/** Fill missing time with complex zero samples, preserving CI8 pair alignment. */
public final class IqGap {
    private IqGap() { }
    public static long fill(OutputStream stream,long written,long firstMs,long untilMs,int rate,
                            byte[] zeros,BooleanSupplier cancelled) throws IOException {
        if(stream==null)return written;
        if(rate<=0 || (written&1)!=0 || zeros.length<2 || (zeros.length&1)!=0)
            throw new IllegalArgumentException("CI8 timeline alignment");
        long target=Math.max(0,untilMs-firstMs)*rate/1000*2;
        while(written<target && !cancelled.getAsBoolean()) {
            int count=(int)Math.min(zeros.length,target-written);
            stream.write(zeros,0,count);written+=count;
        }
        return written;
    }
}
