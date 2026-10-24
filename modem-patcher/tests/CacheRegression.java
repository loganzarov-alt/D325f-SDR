package io.github.loganzarov_alt.d325fmodem;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import android.widget.TextView;
import java.io.*;
import java.lang.reflect.*;

/** Runs under the installed app UID. Actual modem access is read only.
 * All transformations and write verification target one app-private regular file. */
public final class CacheRegression extends Instrumentation {
    private Context context;
    private final StringBuilder log=new StringBuilder();
    private int checks,maxC;
    public void onCreate(Bundle args){super.onCreate(args);start();}
    private void check(boolean ok,String text){if(!ok)throw new AssertionError(text);checks++;log.append("PASS ").append(text).append('\n');}
    private String su(String command)throws Exception{
        ModemOps.Shell result=ModemOps.su(command);if(result.exit!=0)throw new IOException(result.out);return result.out;
    }
    private void thermal()throws Exception{
        for(String raw:su("cat /sys/class/thermal/thermal_zone0/temp /sys/class/thermal/thermal_zone1/temp").trim().split("\\s+")){
            int value=Integer.parseInt(raw);if(value>1000)value/=1000;maxC=Math.max(maxC,value);if(value>=58)throw new IOException("THERMAL_STOP "+value);
        }
    }
    private Object field(Object object,String name)throws Exception{Field field=object.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(object);}
    private void execute()throws Exception{
        context=getTargetContext();thermal();
        ModemOps.Shell explicit=ModemOps.su("exit 23");
        check(explicit.exit==23,"actual child failure status survives the Android su daemon");
        File legacy=new File(context.getCacheDir(),"read-a.bin");
        su("rm -f "+ModemOps.quote(legacy.getPath())+"\nprintf old > "+ModemOps.quote(legacy.getPath()));
        File prepared=PrivateFiles.prepare(context.getCacheDir(),"read-a.bin");
        check(prepared.length()==0&&prepared.canRead()&&prepared.canWrite(),"root-created legacy cache recreated under app UID");
        log.append(su("ls -lZ "+ModemOps.quote(prepared.getPath()))).append('\n');
        check(prepared.delete(),"app can remove prepared cache");
        String block=NativeFlows.partition(context);log.append("READ_ONLY_PARTITION ").append(block).append('\n');
        String real=NativeFlows.run(context,true,block,"");check(NativeFlows.state(real).equals("PATCHED"),"native engine recognises the current patched modem");thermal();
        File fixture=PrivateFiles.prepare(context.getCacheDir(),"native-fixture.bin");
        try{
            su("dd if="+ModemOps.quote(block)+" of="+ModemOps.quote(fixture.getPath())+" bs=1048576 count=64 2>&1");
            check(fixture.isFile()&&fixture.length()==67108864,"all write tests target a private regular 64 MiB fixture");
            String before=NativeFlows.hash(fixture);check(before.equals("42ecd06546fad39d22428290b8284e1256ce14e1a88a2b5ef56b4c44ffaf227e"),"read-only device snapshot matches known working image");thermal();
            String result=NativeFlows.run(context,false,fixture.getPath(),"--revert");
            check(result.contains("READBACK_VERIFIED")&&NativeFlows.state(result).equals("STOCK"),"native reverse patch restores stock files without an external backup");thermal();
            check(NativeFlows.state(NativeFlows.run(context,false,fixture.getPath(),"")).equals("STOCK"),"stock fixture is recognised after restore");thermal();
            result=NativeFlows.run(context,false,fixture.getPath(),"--apply");
            check(result.contains("READBACK_VERIFIED")&&NativeFlows.state(result).equals("PATCHED"),"native apply writes and verifies the patched fixture");thermal();
            check(NativeFlows.hash(fixture).equals(before),"reverse and apply reproduce the entire starting image exactly");thermal();
            try(RandomAccessFile file=new RandomAccessFile(fixture,"rw")){file.seek(510);file.write(0);}
            String corrupt=NativeFlows.hash(fixture);boolean rejected=false;
            try{NativeFlows.run(context,false,fixture.getPath(),"--toggle");}catch(PatchException expected){rejected=true;}
            check(rejected&&NativeFlows.hash(fixture).equals(corrupt),"corrupt fixture rejected without writes");thermal();
        }finally{fixture.delete();}
        Intent launch=new Intent();launch.setClassName(context.getPackageName(),context.getPackageName()+".MainActivity");launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        final Activity activity=startActivitySync(launch);
        for(int i=0;i<160&&Boolean.TRUE.equals(field(activity,"busy"));i++){if(i%16==0)thermal();Thread.sleep(250);}
        check(!Boolean.TRUE.equals(field(activity,"busy")),"main activity startup check completes without Java heap exhaustion");
        final String[] status={""};runOnMainSync(()->{try{status[0]=((TextView)field(activity,"status")).getText().toString();}catch(Exception e){throw new RuntimeException(e);}});
        log.append("APP_STATUS ").append(status[0]).append('\n');
        check(status[0].contains(block)&&status[0].contains("пропатчен")&&!status[0].contains("не завершена"),"main activity displays detected modem and patched state");thermal();
        String finalHash=su("sha256sum "+ModemOps.quote(block));
        check(finalHash.startsWith("42ecd06546fad39d22428290b8284e1256ce14e1a88a2b5ef56b4c44ffaf227e"),"actual modem partition remains unchanged");
        log.append("REAL_MODEM_WRITES 0\n");
    }
    public void onStart(){Bundle result=new Bundle();try{execute();result.putString("stream",log+"PASS "+checks+" checks; max CPU "+maxC+" C\n");finish(-1,result);}catch(Throwable error){StringWriter out=new StringWriter();error.printStackTrace(new PrintWriter(out));result.putString("stream",log+"FAIL\n"+out);finish(0,result);}}
}
