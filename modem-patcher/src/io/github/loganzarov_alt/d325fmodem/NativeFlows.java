// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
package io.github.loganzarov_alt.d325fmodem;

import android.content.Context;
import android.os.Build;
import java.io.*;
import java.security.MessageDigest;
import java.util.Locale;

/** Shared engine for Android and TWRP. No large Java firmware buffers. */
final class NativeFlows {
    static final String DUMPS="/data/media/0/D325f";
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
        else if(!temp.renameTo(file)){temp.delete();throw new IOException("Could not prepare "+name);}
        if(name.equals("modem-toggle")&&!file.setExecutable(true,true))throw new IOException("Could not make the native module executable");
        return file;
    }
    static String command(Context context,boolean block,String path,String mode,String...argument)throws Exception{
        boolean data=mode.equals("--dump")||mode.equals("--restore-dump");
        if(!(mode.equals("")||mode.equals("--apply")||mode.equals("--revert")||mode.equals("--toggle")||data))throw new IllegalArgumentException("operation");
        if(argument.length!=(data?1:0))throw new IllegalArgumentException("operation argument");
        File tool=install(context,"modem-toggle"),payload=install(context,"patch.payload");
        String result="exec "+ModemOps.quote(tool.getPath())+(block?" --block ":" --image ")+ModemOps.quote(path)+" --payload "+ModemOps.quote(payload.getPath())+" "+mode;
        if(data)result+=" "+ModemOps.quote(argument[0]);
        if(block&&!mode.isEmpty()&&!mode.equals("--dump"))result+=" --android-root --allow-block-write";
        return result;
    }
    static String[] dumps()throws Exception{
        ModemOps.Shell result=ModemOps.su("for file in "+DUMPS+"/modem-dump-*.bin; do [ ! -f \"$file\" ] || printf '%s\\n' \"$file\"; done");
        if(result.exit!=0)throw new PatchException(result.out);
        if(result.out.isEmpty())return new String[0];
        java.util.ArrayList<String> files=new java.util.ArrayList<>();
        for(String file:result.out.split("\\r?\\n"))if(file.startsWith(DUMPS+"/modem-dump-")&&file.endsWith(".bin"))files.add(file);
        java.util.Collections.sort(files);return files.toArray(new String[0]);
    }
    static boolean hasRecoverableDump(Context context)throws Exception{
        for(String file:dumps()){
            // Validate the image and its checksum without any write. The same
            // regular file is both source and destination of the dry run.
            ModemOps.Shell checked=ModemOps.su(command(context,false,file,"--restore-dump",file)+" --dry-run");
            if(checked.exit==0&&checked.out.contains("DRY_RUN_NO_WRITES"))return true;
        }
        return false;
    }
    static String partition(Context context)throws Exception{
        if(!Build.MODEL.toUpperCase(Locale.ROOT).contains("D325"))throw new PatchException("Only LG D325 is supported");
        ModemOps.Shell scan=ModemOps.su(assetText(context,"scan-partition.sh"));
        if(scan.exit!=0)throw new PatchException("Could not obtain root access or locate the partition:\n"+scan.out);
        return ModemOps.select(scan.out);
    }
    static String run(Context context,boolean block,String path,String mode,String...argument)throws Exception{
        ModemOps.Shell result=ModemOps.su(command(context,block,path,mode,argument));
        if(result.exit!=0)throw new PatchException(result.out.isEmpty()?"Native module error: "+result.exit:result.out);
        return result.out;
    }
    static String state(String output)throws PatchException{
        for(String line:output.split("\\r?\\n"))if(line.startsWith("RESULT "))return line.substring(7);
        for(String line:output.split("\\r?\\n"))if(line.startsWith("STATE "))return line.substring(6);
        throw new PatchException("The native module did not return a modem state");
    }
}
