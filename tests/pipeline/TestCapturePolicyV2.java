// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
import com.mantz_it.rfanalyzer.automation.CapturePolicy;
public class TestCapturePolicyV2 {
    static void check(boolean b) { if(!b)throw new AssertionError(); }
    public static void main(String[] args) {
        CapturePolicy p=new CapturePolicy(1000);
        check(!p.hasReceived());check(!p.failed(300999));check(p.failed(301000));
        p.packet(300999);check(p.hasReceived());check(!p.failed(600998));check(p.failed(600999));
        double n=Double.NaN;
        check(CapturePolicy.cooling(new double[]{40,60},new double[]{37,57},1000)==1);
        check(CapturePolicy.cooling(new double[]{40,60},new double[]{37,59},599999)==0);
        check(CapturePolicy.cooling(new double[]{40,60},new double[]{37,59},600000)==2);
        check(CapturePolicy.cooling(new double[]{40,60},new double[]{40,70},0)==2);
        check(CapturePolicy.cooling(new double[]{40,n},new double[]{37,n},1000)==1);
        check(CapturePolicy.cooling(new double[]{40,60},new double[]{37,n},1000)==0);
        check(CapturePolicy.cooling(new double[]{40,60},new double[]{n,n},1000)==3);
        check(CapturePolicy.overlaps(10,20,19,30));check(!CapturePolicy.overlaps(10,20,20,30));
        System.out.println("PASS v2: outage timer reset, cooling deadlines, sensor loss and overlapping tasks");
    }
}
