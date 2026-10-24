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
    private TextView status,log;
    private ScrollView logScroll;
    private Button patchButton,restoreButton,refreshButton,rebootButton;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private interface Job{void run()throws Exception;}
    protected void onCreate(Bundle saved){
        super.onCreate(saved);
        LinearLayout box=new LinearLayout(this);box.setOrientation(1);int pad=dp(16);box.setPadding(pad,pad,pad,pad);
        TextView title=new TextView(this);title.setText("Модем LG D325 для D325f SDR");title.setTextSize(20);title.setTypeface(Typeface.DEFAULT_BOLD);box.addView(title);
        status=new TextView(this);status.setPadding(0,dp(8),0,dp(8));status.setText("Проверка…");box.addView(status);
        refreshButton=button("Проверить",v->run(()->refreshStatus()));
        patchButton=button("Пропатчить модем",v->confirmStart(true));
        restoreButton=button("Восстановить сток",v->confirmStart(false));
        rebootButton=button("Перезагрузить телефон",v->new AlertDialog.Builder(this).setMessage("Перезагрузить телефон сейчас?").setPositiveButton("Перезагрузить",(d,w)->run(()->{ModemOps.Shell result=ModemOps.su("sync\nreboot");if(result.exit!=0)throw new PatchException(result.out);})).setNegativeButton("Отмена",null).show());
        rebootButton.setVisibility(View.GONE);
        box.addView(refreshButton);box.addView(patchButton);box.addView(restoreButton);box.addView(rebootButton);
        log=new TextView(this);log.setTextSize(12);log.setTypeface(Typeface.MONOSPACE);log.setTextIsSelectable(true);
        logScroll=new ScrollView(this);logScroll.addView(log);box.addView(logScroll,new LinearLayout.LayoutParams(-1,0,1));setContentView(box);
        run(()->refreshStatus());
    }
    private Button button(String text,View.OnClickListener action){Button b=new Button(this);b.setText(text);b.setAllCaps(false);b.setOnClickListener(action);return b;}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void logLine(String text){runOnUiThread(()->{log.append(text+"\n");logScroll.post(()->logScroll.fullScroll(View.FOCUS_DOWN));});}
    private void setBusy(boolean value){busy=value;runOnUiThread(()->{
        refreshButton.setEnabled(!value);patchButton.setEnabled(!value);restoreButton.setEnabled(!value);rebootButton.setEnabled(!value);
        if(value)getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    });}
    private void run(Job job){if(busy)return;setBusy(true);worker.execute(()->{try{job.run();}catch(Exception e){logLine("Ошибка: "+e.getMessage());}finally{setBusy(false);}});}
    private void refreshStatus()throws Exception{
        String text="Телефон: "+Build.MANUFACTURER+" "+Build.MODEL;
        try{
            String block=NativeFlows.partition(this);
            String state=NativeFlows.state(NativeFlows.run(this,true,block,""));
            text+="\nBaseband: "+PrivateFiles.baseband()+"\nРаздел: "+block+"\nМодем: "+(state.equals("PATCHED")?"пропатчен для D325f SDR":"стоковый")+"\nВосстановление стока доступно без отдельного бэкапа.";
        }catch(Exception e){text+="\nПроверка не завершена: "+e.getMessage();throw e;}
        finally{final String message=text;runOnUiThread(()->status.setText(message));}
    }
    private void confirmStart(boolean patch){
        String text=patch?"Будут проверены файлы модема и записаны изменённые секторы.":"Будут восстановлены исходные файлы модема. Отдельный бэкап не требуется.";
        new AlertDialog.Builder(this).setTitle(patch?"Пропатчить модем?":"Восстановить сток?").setMessage(text+"\n\nПеред началом охладите телефон ниже 48 °C. Не выключайте его во время записи.").setPositiveButton("Продолжить",(d,w)->run(()->change(patch))).setNegativeButton("Отмена",null).show();
    }
    private void change(boolean patch)throws Exception{
        String block=NativeFlows.partition(this);logLine(patch?"Проверка и установка патча…":"Проверка и восстановление стока…");
        String output=NativeFlows.run(this,true,block,patch?"--apply":"--revert");logLine(output);
        if(output.contains("READBACK_VERIFIED")){logLine("Записано и проверено. Перезагрузите телефон.");runOnUiThread(()->rebootButton.setVisibility(View.VISIBLE));}
        else logLine("Модем уже в выбранном состоянии.");
        refreshStatus();
    }
    public void onBackPressed(){if(busy)logLine("Подождите окончания операции");else super.onBackPressed();}
    protected void onDestroy(){worker.shutdown();super.onDestroy();}
}
