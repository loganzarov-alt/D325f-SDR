// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
import com.mantz_it.rfanalyzer.automation.IqGap;
import java.io.*;
import java.util.Arrays;
public class TestIqGap {
    static void check(boolean b) { if(!b)throw new AssertionError(); }
    public static void main(String[] args)throws Exception {
        check(IqGap.fill(null,0,1000,2000,1000,new byte[8],()->false)==0);
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(new byte[]{10,20,30,40});
        long written=IqGap.fill(out,4,1000,1010,1000,new byte[8],()->false);
        check(written==20);out.write(new byte[]{50,60});
        byte[] data=out.toByteArray();check(data.length==22 && data[0]==10 && data[20]==50 && data[21]==60);
        for(int i=4;i<20;i++)check(data[i]==0);
        check(IqGap.fill(out,22,1000,1010,1000,new byte[8],()->false)==22);
        check(IqGap.fill(out,22,1000,2000,1000,new byte[8],()->true)==22);
        boolean failed=false;
        try { IqGap.fill(new OutputStream(){public void write(int b)throws IOException {throw new IOException("full");}},0,0,100,1000,new byte[8],()->false); }
        catch(IOException expected) { failed=true; }
        check(failed);
        System.out.println("PASS: no file before data, real-zero-real continuity, no rewind, cancellation and full-storage propagation");
    }
}
