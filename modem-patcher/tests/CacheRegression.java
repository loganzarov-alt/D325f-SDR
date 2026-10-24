// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
package io.github.loganzarov_alt.d325fmodem;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.widget.TextView;
import java.io.*;
import java.lang.reflect.*;

/** Optional device test. Actual partition access is read-only; fixture writes
 * and dump creation require explicit permission to run this instrumentation. */
public final class CacheRegression extends Instrumentation {
    private Context context;
    private final StringBuilder log=new StringBuilder();
    private int checks;
    public void onCreate(Bundle args){super.onCreate(args);start();}
    private void check(boolean ok,String text){if(!ok)throw new AssertionError(text);checks++;log.append("PASS ").append(text).append('\n');}
    private String su(String command)throws Exception{ModemOps.Shell result=ModemOps.su(command);if(result.exit!=0)throw new IOException(result.out);return result.out;}
    private Object field(Object object,String name)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private void execute()throws Exception{
        context=getTargetContext();check(ModemOps.su("exit 23").exit==23,"child failure status survives the su daemon");
        File legacy=new File(context.getCacheDir(),"read-a.bin");
        su("rm -f "+ModemOps.quote(legacy.getPath())+"\nprintf old > "+ModemOps.quote(legacy.getPath()));
        File prepared=PrivateFiles.prepare(context.getCacheDir(),"read-a.bin");
        check(prepared.length()==0&&prepared.canRead()&&prepared.canWrite(),"legacy root cache recreated under app UID");check(prepared.delete(),"app removes prepared cache");
        String block=NativeFlows.partition(context),initial=NativeFlows.state(NativeFlows.run(context,true,block,""));
        check(initial.equals("STOCK")||initial.equals("PATCHED"),"current supported modem recognised");
        String partitionBefore=su("sha256sum "+ModemOps.quote(block)).split("\\s+")[0];
        File fixture=PrivateFiles.prepare(context.getCacheDir(),"native-fixture.bin"),dumps=new File(context.getCacheDir(),"native-test-dumps");dumps.mkdirs();
        try{
            su("dd if="+ModemOps.quote(block)+" of="+ModemOps.quote(fixture.getPath())+" bs=1048576 count=64 2>&1");
            check(fixture.isFile()&&fixture.length()==67108864,"writes target a private regular fixture");
            String before=NativeFlows.hash(fixture);check(before.equals(partitionBefore),"fixture snapshot matches partition");
            String output=NativeFlows.run(context,false,fixture.getPath(),"--dump",dumps.getPath()),dump=null;
            for(String line:output.split("\\r?\\n"))if(line.startsWith("DUMP_PATH "))dump=line.substring(10);
            check(dump!=null&&output.contains("DUMP_VERIFIED"),"double-read dump saved with checksum");
            boolean rejected=false;try{NativeFlows.run(context,false,fixture.getPath(),"--dump",dumps.getPath());}catch(PatchException expected){rejected=true;}
            check(rejected,"existing dump is not overwritten");
            output=NativeFlows.run(context,false,fixture.getPath(),"--toggle");check(output.contains("READBACK_VERIFIED"),"toggle verifies a fresh read");
            output=NativeFlows.run(context,false,fixture.getPath(),"--restore-dump",dump);
            check(output.contains("READBACK_VERIFIED")&&NativeFlows.hash(fixture).equals(before),"dump restore reproduces complete original fixture");
            try(RandomAccessFile file=new RandomAccessFile(fixture,"rw")){file.seek(510);file.write(0);}
            check(NativeFlows.state(NativeFlows.run(context,false,fixture.getPath(),"")).equals("UNKNOWN"),"damaged FAT classified UNKNOWN");
            NativeFlows.run(context,false,fixture.getPath(),"--restore-dump",dump);check(NativeFlows.hash(fixture).equals(before),"dump restores an UNKNOWN fixture");
        }finally{fixture.delete();su("rm -rf "+ModemOps.quote(dumps.getPath()));}
        Intent launch=new Intent();launch.setClassName(context.getPackageName(),context.getPackageName()+".MainActivity");launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final Activity activity=startActivitySync(launch);
        for(int i=0;i<240&&Boolean.TRUE.equals(field(activity,"busy"));i++)Thread.sleep(250);
        check(!Boolean.TRUE.equals(field(activity,"busy")),"startup check completes");
        final String[] status={""};runOnMainSync(()->{try{status[0]=((TextView)field(activity,"status")).getText().toString();}catch(Exception e){throw new RuntimeException(e);}});
        check(status[0].contains(block)&&status[0].contains(initial)&&!status[0].contains("failed"),"English status displays current modem state");
        check(su("sha256sum "+ModemOps.quote(block)).startsWith(partitionBefore),"actual modem partition unchanged");
        log.append("REAL_MODEM_WRITES 0\n");
    }
    public void onStart(){Bundle result=new Bundle();try{execute();result.putString("stream",log+"PASS "+checks+" checks\n");finish(-1,result);}catch(Throwable error){StringWriter out=new StringWriter();error.printStackTrace(new PrintWriter(out));result.putString("stream",log+"FAIL\n"+out);finish(0,result);}}
}
