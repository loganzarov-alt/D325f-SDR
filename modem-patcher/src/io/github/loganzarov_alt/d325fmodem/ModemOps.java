// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
package io.github.loganzarov_alt.d325fmodem;

import java.io.*;
import java.util.LinkedHashSet;

/** Root shell transports text only. Firmware remains in the native process. */
final class ModemOps {
    static final class Shell {
        final int exit; final String out;
        Shell(int exit,String out){this.exit=exit;this.out=out;}
    }
    static String quote(String text){return "'"+text.replace("'","'\\''")+"'";}
    static Shell su(String script)throws PatchException{
        try {
            // Some Android su daemons return 0 even when their child failed.
            // Keep the requested command in a subshell, including exec/exit,
            // and transport its actual status in a final text record.
            String marker="D325F_STATUS_"+Long.toHexString(System.nanoTime())+"=";
            String wrapped="(\n"+script+"\n)\nd325f_rc=$?\nprintf '\\n"+marker+"%s\\n' \"$d325f_rc\"\nexit \"$d325f_rc\"\n";
            Process process=new ProcessBuilder("su").redirectErrorStream(true).start();
            try(OutputStream out=process.getOutputStream()){
                out.write(wrapped.getBytes("UTF-8"));
            }
            ByteArrayOutputStream output=new ByteArrayOutputStream();
            try(InputStream in=process.getInputStream()){
                byte[] buffer=new byte[4096];int n;
                while((n=in.read(buffer))!=-1){
                    if(output.size()+n>1048576){process.destroy();throw new IOException("Too much shell output");}
                    output.write(buffer,0,n);
                }
            }
            int transport=process.waitFor();String text=output.toString("UTF-8").trim();
            int at=text.lastIndexOf(marker);
            if(at<0)throw new PatchException("su did not confirm command completion ("+transport+"):\n"+text);
            String status=text.substring(at+marker.length()).trim();
            if(!status.matches("[0-9]{1,3}"))throw new PatchException("Invalid su status response");
            return new Shell(Integer.parseInt(status),text.substring(0,at).trim());
        } catch(InterruptedException e){Thread.currentThread().interrupt();throw new PatchException("Operation interrupted",e);}
        catch(IOException e){throw new PatchException("Could not start su. Grant root access to this application.",e);}
    }
    static String select(String text)throws PatchException{
        LinkedHashSet<String> mounts=new LinkedHashSet<>(),labels=new LinkedHashSet<>();
        boolean root=false,bad=false;
        for(String line:text.split("\\r?\\n")){
            if(line.equals("D325F_UID=0"))root=true;
            if(line.startsWith("D325F_BAD_MOUNT\t"))bad=true;
            if(!line.startsWith("D325F_CAND\t"))continue;
            String[] bits=line.split("\t",-1);
            if(bits.length!=4||!(bits[1].equals("MOUNT")||bits[1].equals("LABEL"))||!bits[2].matches("/dev/block/[A-Za-z0-9_./:+-]+")||bits[2].contains(".."))
                throw new PatchException("Invalid modem partition scan response");
            if(!bits[3].equals("131072")){if(bits[1].equals("MOUNT"))bad=true;continue;}
            (bits[1].equals("MOUNT")?mounts:labels).add(bits[2]);
        }
        if(!root)throw new PatchException("Root access is unavailable. Grant it in your su manager.");
        if(bad)throw new PatchException("The mounted firmware partition failed validation");
        LinkedHashSet<String> chosen=mounts.isEmpty()?labels:mounts;
        if(chosen.size()!=1)throw new PatchException("Could not find a unique 64 MiB modem partition");
        return chosen.iterator().next();
    }
}
