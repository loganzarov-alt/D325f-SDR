// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
package com.mantz_it.rfanalyzer.source;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import java.io.*;
import java.util.LinkedHashSet;
import java.util.concurrent.Executors;

/** One-shot background RAM reclamation plus a reversible CPU governor lease. */
public final class SdrFocusController {
    private static SdrFocusController instance;
    public static synchronized SdrFocusController get(Context c) {
        if(instance==null)instance=new SdrFocusController(c.getApplicationContext());
        return instance;
    }
    private final Context context;
    private final java.util.concurrent.ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private volatile Process process;
    private volatile long generation, uiBeat;
    private volatile boolean wanted;
    private volatile String status="Не активен";
    private SdrFocusController(Context c){context=c;}
    public int getMode(){
        android.content.SharedPreferences p=context.getSharedPreferences("sdr-focus",0);
        return p.getInt("mode",p.getBoolean("enabled",true)?1:0);
    }
    public void setMode(int mode){
        if(mode<0||mode>2)throw new IllegalArgumentException("mode");
        context.getSharedPreferences("sdr-focus",0).edit().putInt("mode",mode).apply();
        if(mode==0)stop();
    }
    public boolean isEnabled(){return getMode()!=0;}
    public void setEnabled(boolean enabled){setMode(enabled?1:0);}
    public String getStatus(){return status;}
    private final Runnable beat=new Runnable(){public void run(){if(wanted){uiBeat=SystemClock.elapsedRealtime();main.postDelayed(this,500);}}};
    public synchronized void start(){
        if(wanted||!isEnabled())return;wanted=true;final long epoch=++generation;final int mode=getMode();
        uiBeat=SystemClock.elapsedRealtime();main.post(beat);
        worker.execute(()->{
            try {
                if(!wanted||epoch!=generation)return;
                File dir=context.getDir("sdr_focus",0),binary=new File(dir,"sdr-focus"),config=new File(dir,"targets");
                try(InputStream in=context.getAssets().open("sdr-focus");OutputStream out=new FileOutputStream(binary)){byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);}
                if(!binary.setExecutable(true,false))throw new IOException("install");
                String memorySummary = mode==1 ? BackgroundReclaimer.reclaim(context) : "Заморозка фоновых приложений и System UI";
                status = memorySummary;
                LinkedHashSet<String> names=new LinkedHashSet<>();
                if(mode==2){
                    String keyboard=Settings.Secure.getString(context.getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD);
                    String keyboardPackage=keyboard==null?"":keyboard.split("/")[0];
                    String accessibility=Settings.Secure.getString(context.getContentResolver(),"enabled_accessibility_services");
                    for(ApplicationInfo a:context.getPackageManager().getInstalledApplications(0)){
                        String name=a.packageName;
                        if(a.uid==context.getApplicationInfo().uid||name.equals(keyboardPackage)||name.contains("supersu")||name.contains("superuser")||name.contains("magisk")||
                           (accessibility!=null&& (":"+accessibility).contains(":"+name+"/")))continue;
                        if((a.flags&ApplicationInfo.FLAG_SYSTEM)==0)names.add(name);
                    }
                    ResolveInfo home=context.getPackageManager().resolveActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),0);
                    if(home!=null&&home.activityInfo!=null)names.add(home.activityInfo.packageName);
                    names.add("com.android.systemui");names.add("com.google.android.gms");names.add("com.google.android.gsf");
                    names.remove(context.getPackageName());names.remove(keyboardPackage);
                    if(accessibility!=null)for(String service:accessibility.split(":"))names.remove(service.split("/")[0]);
                }
                try(PrintWriter out=new PrintWriter(config)){for(String name:names)if(name.matches("[A-Za-z0-9_.]+"))out.println(name);}
                final Process p;
                synchronized(this){
                    if(!wanted||epoch!=generation)return;
                    process=new ProcessBuilder("su","-c","exec "+binary.getAbsolutePath()+" "+config.getAbsolutePath()).redirectErrorStream(true).start();
                    p=process;
                }
                new Thread(()->{try(BufferedReader r=new BufferedReader(new InputStreamReader(p.getInputStream()))){String line;while((line=r.readLine())!=null)status=memorySummary + "\n" + line;}catch(IOException ignored){}},"SDR-focus-status").start();
                while(wanted&&epoch==generation){
                    if(SystemClock.elapsedRealtime()-uiBeat>2000)break;
                    try {p.exitValue();break;} catch(IllegalThreadStateException alive){}
                    p.getOutputStream().write('H');p.getOutputStream().flush();Thread.sleep(400);
                }
                p.getOutputStream().close();
                p.waitFor();
                synchronized(this){if(epoch==generation){process=null;wanted=false;main.removeCallbacks(beat);}}
            }catch(Exception e){status="Режим SDR: "+e.getMessage();synchronized(this){if(epoch==generation){wanted=false;main.removeCallbacks(beat);}}}
        });
    }
    public synchronized void stop(){
        wanted=false;++generation;main.removeCallbacks(beat);
        Process p=process;process=null;
        if(p!=null)try{p.getOutputStream().close();}catch(IOException ignored){}
    }
}
