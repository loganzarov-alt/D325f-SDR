// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
package io.github.loganzarov_alt.d325fmodem;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private volatile boolean busy;
    private volatile String currentState="UNKNOWN";
    private TextView status,log;
    private ScrollView logScroll;
    private Button patchButton,restoreButton,refreshButton,rebootButton,dumpButton,restoreDumpButton;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private interface Job{void run()throws Exception;}
    protected void onCreate(Bundle saved){
        super.onCreate(saved);
        LinearLayout box=new LinearLayout(this);box.setOrientation(1);int pad=dp(16);box.setPadding(pad,pad,pad,pad);
        TextView title=new TextView(this);title.setText("LG D325 Modem Patcher");title.setTextSize(20);title.setTypeface(Typeface.DEFAULT_BOLD);box.addView(title);
        status=new TextView(this);status.setPadding(0,dp(8),0,dp(8));status.setText("Checking…");box.addView(status);
        refreshButton=button("Refresh",v->run(()->refreshStatus()));
        dumpButton=button("Save dump",v->run(()->saveDump()));
        patchButton=button("Apply patch",v->prepareChange(true));
        restoreButton=button("Restore stock",v->prepareChange(false));
        restoreDumpButton=button("Restore from dump",v->chooseDump());restoreDumpButton.setVisibility(View.GONE);
        Button licenses=button("Licenses",v->run(()->showLicenses()));
        rebootButton=button("Reboot phone",v->new AlertDialog.Builder(this).setMessage("Reboot the phone now?").setPositiveButton("Reboot",(d,w)->run(()->{ModemOps.Shell result=ModemOps.su("sync\nreboot");if(result.exit!=0)throw new PatchException(result.out);})).setNegativeButton("Cancel",null).show());
        rebootButton.setVisibility(View.GONE);
        row(box,refreshButton,dumpButton);row(box,patchButton,restoreButton);row(box,restoreDumpButton,licenses);box.addView(rebootButton);
        log=new TextView(this);log.setTextSize(12);log.setTypeface(Typeface.MONOSPACE);log.setTextIsSelectable(true);
        logScroll=new ScrollView(this);logScroll.addView(log);box.addView(logScroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(box);
        run(()->refreshStatus());
    }
    private void row(LinearLayout box,Button a,Button b){LinearLayout row=new LinearLayout(this);row.addView(a,new LinearLayout.LayoutParams(0,-2,1));row.addView(b,new LinearLayout.LayoutParams(0,-2,1));box.addView(row);}
    private Button button(String text,View.OnClickListener action){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setOnClickListener(action);return b;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void logLine(String text){runOnUiThread(()->{log.append(text+"\n");logScroll.post(()->logScroll.fullScroll(View.FOCUS_DOWN));});}
    private boolean known(){return currentState.equals("STOCK")||currentState.equals("PATCHED");}
    private void setBusy(boolean value){busy=value;runOnUiThread(()->{
        refreshButton.setEnabled(!value);dumpButton.setEnabled(!value);patchButton.setEnabled(!value&&known());restoreButton.setEnabled(!value&&known());restoreDumpButton.setEnabled(!value);rebootButton.setEnabled(!value);
        if(value)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    });}
    private void run(Job job){if(busy)return;setBusy(true);worker.execute(()->{try{job.run();}catch(Exception e){logLine("Error: "+e.getMessage());}finally{setBusy(false);}});}
    private void refreshStatus()throws Exception{
        String text="Phone: "+Build.MANUFACTURER+" "+Build.MODEL;
        try{
            String block=NativeFlows.partition(this);currentState=NativeFlows.state(NativeFlows.run(this,true,block,""));
            text+="\nBaseband: "+PrivateFiles.baseband()+"\nPartition: "+block+"\nModem: "+currentState+"\nSave a dump first and copy it to a PC.";
        }catch(Exception e){currentState="UNKNOWN";text+="\nCheck failed: "+e.getMessage();throw e;}
        finally{final String message=text;runOnUiThread(()->{status.setText(message);restoreDumpButton.setVisibility(known()?View.GONE:View.VISIBLE);});}
    }
    private void prepareChange(boolean patch){
        run(()->{boolean hasDump=NativeFlows.hasRecoverableDump(this);runOnUiThread(()->{
            if(hasDump){confirmStart(patch);return;}
            new AlertDialog.Builder(this).setTitle("Save a dump first")
                .setMessage("No recoverable dump was found in Internal storage/D325f. Save a dump first and copy it to a PC. It can recover an interrupted write.")
                .setPositiveButton("Save dump",(d,w)->run(()->{saveDump();runOnUiThread(()->confirmStart(patch));}))
                .setNeutralButton("Continue without dump",(d,w)->confirmStart(patch)).setNegativeButton("Cancel",null).show();
        });});
    }
    private void confirmStart(boolean patch){
        String text=patch?"Verify the modem files and write the changed sectors?":"Restore the stock versions of the six modem files?";
        new AlertDialog.Builder(this).setTitle(patch?"Apply patch?":"Restore stock?").setMessage(text+"\n\nDo not power off the phone during writing.").setPositiveButton("Continue",(d,w)->run(()->change(patch))).setNegativeButton("Cancel",null).show();
    }
    private void saveDump()throws Exception{
        logLine("Reading the partition twice and saving a dump…");String block=NativeFlows.partition(this);
        String output=NativeFlows.run(this,true,block,"--dump",NativeFlows.DUMPS);logLine(output);
        logLine("Dump saved in Internal storage/D325f. Copy the .bin and .sha256 files to a PC.");
    }
    private void chooseDump(){
        run(()->{String[] paths=NativeFlows.dumps();
            if(paths.length==0){runOnUiThread(()->new AlertDialog.Builder(this).setMessage("Copy a verified modem-dump-*.bin file to Internal storage/D325f first.").setPositiveButton("OK",null).show());return;}
            String[] names=new String[paths.length];for(int i=0;i<paths.length;i++)names[i]=new java.io.File(paths[i]).getName();
            runOnUiThread(()->new AlertDialog.Builder(this).setTitle("Restore from dump").setItems(names,(d,index)->
                new AlertDialog.Builder(this).setTitle("Restore this dump?").setMessage(names[index]+"\n\nOnly a dump with supported STOCK or PATCHED firmware files is accepted. Do not power off during writing.")
                    .setPositiveButton("Restore",(confirm,w)->run(()->{
                        String block=NativeFlows.partition(this);String output=NativeFlows.run(this,true,block,"--restore-dump",paths[index]);logLine(output);completeWrite(output);
                    })).setNegativeButton("Cancel",null).show()).setNegativeButton("Cancel",null).show());
        });
    }
    private void change(boolean patch)throws Exception{
        String block=NativeFlows.partition(this);logLine(patch?"Verifying and applying the patch…":"Verifying and restoring stock files…");
        String output=NativeFlows.run(this,true,block,patch?"--apply":"--revert");logLine(output);completeWrite(output);
    }
    private void completeWrite(String output)throws Exception{
        if(output.contains("READBACK_VERIFIED")){logLine("Written and verified after a fresh read. Reboot the phone.");runOnUiThread(()->rebootButton.setVisibility(View.VISIBLE));}
        else logLine("The modem is already in the requested state.");refreshStatus();
    }
    private void showLicenses()throws Exception{
        StringBuilder text=new StringBuilder(NativeFlows.assetText(this,"LICENSE"));
        for(String name:getAssets().list("licenses"))text.append("\n\n--- ").append(name).append(" ---\n").append(NativeFlows.assetText(this,"licenses/"+name));
        final String notices=text.toString();runOnUiThread(()->{
            TextView view=new TextView(this);view.setText(notices);view.setTextSize(12);view.setTextIsSelectable(true);view.setPadding(dp(12),dp(12),dp(12),dp(12));
            ScrollView scroll=new ScrollView(this);scroll.addView(view);new AlertDialog.Builder(this).setTitle("Licenses").setView(scroll).setPositiveButton("Close",null).show();
        });
    }
    public void onBackPressed(){if(busy)logLine("Please wait for the operation to finish");else super.onBackPressed();}
    protected void onDestroy(){worker.shutdown();super.onDestroy();}
}
