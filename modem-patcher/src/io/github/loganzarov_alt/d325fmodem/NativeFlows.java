package io.github.loganzarov_alt.d325fmodem;

import android.content.Context;
import android.os.Build;
import java.io.*;
import java.security.MessageDigest;
import java.util.Locale;

/** Shared engine for Android and TWRP. No large Java firmware buffers. */
final class NativeFlows {
    static String assetText(Context context,String name)throws Exception{
        try(InputStream in=context.getAssets().open(name);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n);
            return out.toString("UTF-8");
        }
    }
    static String hash(File file)throws Exception{
        MessageDigest hash=MessageDigest.getInstance("SHA-256");
        try(InputStream in=new FileInputStream(file)){
            byte[] buf=new byte[65536];int n;while((n=in.read(buf))!=-1)hash.update(buf,0,n);
        }
        StringBuilder s=new StringBuilder();for(byte b:hash.digest())s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();
    }
    static synchronized File install(Context context,String name)throws Exception{
        File file=new File(context.getFilesDir(),name),temp=new File(context.getFilesDir(),name+".tmp");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        try(InputStream in=context.getAssets().open(name);FileOutputStream out=new FileOutputStream(temp)){
            byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1){out.write(buf,0,n);digest.update(buf,0,n);}out.getFD().sync();
        }
        StringBuilder expected=new StringBuilder();for(byte b:digest.digest())expected.append(String.format(Locale.ROOT,"%02x",b&255));
        if(file.isFile()&&hash(file).equals(expected.toString()))temp.delete();
        else if(!temp.renameTo(file)){temp.delete();throw new IOException("Не удалось подготовить "+name);}
        if(name.equals("modem-toggle")&&!file.setExecutable(true,true))throw new IOException("Не удалось разрешить запуск модуля");
        return file;
    }
    static String command(Context context,boolean block,String path,String mode)throws Exception{
        if(!(mode.equals("")||mode.equals("--apply")||mode.equals("--revert")||mode.equals("--toggle")||mode.equals("--dry-run")))throw new IllegalArgumentException("mode");
        File tool=install(context,"modem-toggle"),payload=install(context,"patch.payload");
        return "exec "+ModemOps.quote(tool.getPath())+(block?" --block ":" --image ")+ModemOps.quote(path)+" --payload "+ModemOps.quote(payload.getPath())+" "+mode+(block&&!mode.isEmpty()?" --android-root --allow-block-write":"");
    }
    static String partition(Context context)throws Exception{
        if(!Build.MODEL.toUpperCase(Locale.ROOT).contains("D325"))throw new PatchException("Поддерживается только LG D325");
        ModemOps.Shell scan=ModemOps.su(assetText(context,"scan-partition.sh"));
        if(scan.exit!=0)throw new PatchException("Не удалось получить root или определить раздел:\n"+scan.out);
        return ModemOps.select(scan.out);
    }
    static String run(Context context,boolean block,String path,String mode)throws Exception{
        ModemOps.Shell result=ModemOps.su(command(context,block,path,mode));
        if(result.exit!=0)throw new PatchException(result.out.isEmpty()?"Ошибка нативного модуля: "+result.exit:result.out);
        return result.out;
    }
    static String state(String output)throws PatchException{
        for(String line:output.split("\\r?\\n"))if(line.startsWith("RESULT "))return line.substring(7);
        for(String line:output.split("\\r?\\n"))if(line.startsWith("STATE "))return line.substring(6);
        throw new PatchException("Модуль не вернул состояние модема");
    }
}
