// SPDX-License-Identifier: GPL-3.0-or-later
package com.mantz_it.rfanalyzer.radar;

import android.app.Activity;
import android.content.Intent;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.Gravity;
import android.widget.*;
import com.mantz_it.rfanalyzer.source.IQSourceInterface;
import com.mantz_it.rfanalyzer.source.LgModemSource;
import com.mantz_it.rfanalyzer.source.BtCwController;
import java.io.*;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Separate launcher for the offline radar variant; no network or TX UI. */
public final class RadarActivity extends Activity {
    private static final int BG=0xff0d171f,PANEL=0xff16252f,FG=0xffe3f1f5,MUTED=0xffa0b6bd,ACCENT=0xff56d9ae;
    private static final long[] FREQ={1090000000L,162000000L};
    private static final int PICK=81;
    private final RadarDecoder.Tracker[] trackers={new RadarDecoder.Tracker(),new RadarDecoder.Tracker()};
    private final Handler handler=new Handler(Looper.getMainLooper());
    private volatile boolean cancel;
    private volatile Thread worker;
    private volatile String status="Stopped";
    private volatile long samples;
    private int tab;
    private Button adsbTab,aisTab,start,stop,replay,clear;
    private Spinner band,lna,bt;
    private EditText correction,dvga;
    private TextView frequency,summary,state,rows;
    private RadarPlot plot;
    private long priorFrames,priorTick;
    private int priorTab=-1;
    private final Runnable refresh=new Runnable(){public void run(){update();handler.postDelayed(this,1000);}};
    private int dp(float x){return Math.round(x*getResources().getDisplayMetrics().density);}
    @Override public void onCreate(Bundle saved){super.onCreate(saved);build();select(0);}
    @Override protected void onStart(){super.onStart();handler.removeCallbacks(refresh);handler.post(refresh);}
    @Override protected void onStop(){cancel=true;handler.removeCallbacks(refresh);super.onStop();}
    @Override protected void onDestroy(){cancel=true;handler.removeCallbacksAndMessages(null);super.onDestroy();}
    private TextView label(String s,int size,int color){TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setPadding(dp(4),dp(3),dp(4),dp(3));return t;}
    private Button button(String s,View.OnClickListener action){Button b=new Button(this);b.setText(s);b.setTextSize(12);b.setAllCaps(false);b.setOnClickListener(action);return b;}
    private LinearLayout line(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.HORIZONTAL);l.setGravity(Gravity.CENTER_VERTICAL);return l;}
    private void share(LinearLayout l,View v){l.addView(v,new LinearLayout.LayoutParams(0,-2,1));}
    private Spinner spinner(String[] choices,int selected){Spinner v=new Spinner(this);ArrayAdapter<String> a=new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,choices);v.setAdapter(a);v.setSelection(selected);return v;}
    private EditText number(String s,String hint){EditText e=new EditText(this);e.setSingleLine();e.setTextColor(FG);e.setHintTextColor(MUTED);e.setTextSize(13);e.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_SIGNED);e.setText(s);e.setHint(hint);return e;}
    private void build(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(BG);root.setPadding(dp(10),dp(8),dp(10),dp(6));setContentView(root);
        root.addView(label("D325f RADAR",23,FG));
        LinearLayout tabs=line();adsbTab=button("ADS-B  /  AIR",v->select(0));aisTab=button("AIS  /  SEA",v->select(1));share(tabs,adsbTab);share(tabs,aisTab);root.addView(tabs);
        frequency=label("",14,ACCENT);root.addView(frequency);
        LinearLayout settings=line();band=spinner(new String[]{"B1","B8"},0);lna=spinner(new String[]{"LNA 0","LNA 1","LNA 2","LNA 3","LNA 4","LNA 5"},3);String[] channels=new String[79];for(int i=0;i<79;i++)channels[i]="BT "+i;bt=spinner(channels,7);share(settings,band);share(settings,lna);share(settings,bt);root.addView(settings);
        LinearLayout settings2=line();settings2.addView(label("DVGA",12,MUTED));dvga=number("0","-512…511");share(settings2,dvga);settings2.addView(label("AIS offset Hz",12,MUTED));correction=number("0","±4000");share(settings2,correction);root.addView(settings2);
        LinearLayout controls=line();start=button("Start RX",v->launch(null));stop=button("Stop",v->{cancel=true;status="Stopping…";update();});replay=button("IQ file",v->pick());clear=button("Clear",v->{trackers[tab].clear();update();});share(controls,start);share(controls,stop);share(controls,replay);share(controls,clear);root.addView(controls);
        state=label("",12,MUTED);root.addView(state);summary=label("",15,FG);root.addView(summary);
        plot=new RadarPlot(this);root.addView(plot,new LinearLayout.LayoutParams(-1,0,1.25f));
        ScrollView sv=new ScrollView(this);rows=label("",13,FG);rows.setTextIsSelectable(true);sv.addView(rows);root.addView(sv,new LinearLayout.LayoutParams(-1,0,1));
        root.addView(label("Offline relative plot · experimental · not for navigation",11,MUTED));
    }
    private void select(int n){if(n!=tab&&worker!=null){cancel=true;status="Stopping previous mode…";}tab=n;adsbTab.setTextColor(n==0?ACCENT:FG);aisTab.setTextColor(n==1?ACCENT:FG);frequency.setText(n==0?"1090 MHz  ·  DF17/18  ·  PPM":"161.975 / 162.025 MHz  ·  AIS A+B  ·  GMSK");priorTab=-1;update();}
    private void pick(){if(worker!=null)return;Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,PICK);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==PICK&&result==RESULT_OK&&data!=null&&data.getData()!=null)launch(data.getData());}
    private int parse(EditText field,int min,int max){int v=Integer.parseInt(field.getText().toString().trim());if(v<min||v>max)throw new IllegalArgumentException("range "+min+"…"+max);return v;}
    private void launch(Uri file){if(worker!=null)return;final int mode=tab,rfBand=band.getSelectedItemPosition()==0?1:8,gain=lna.getSelectedItemPosition(),btChannel=bt.getSelectedItemPosition(),offset,dvg;
        try{offset=parse(correction,-4000,4000);dvg=parse(dvga,-512,511);}catch(Exception e){status="Invalid settings: "+e.getMessage();update();return;}
        cancel=false;samples=0;trackers[mode].clear();priorTab=-1;status=file==null?"Opening LG modem…":"Reading signed CI8 7.68 MS/s…";
        worker=new Thread(()->{
            RadarDecoder.Listener listener=(frame,ch)->trackers[mode].update(frame,ch,SystemClock.elapsedRealtime());
            RadarDecoder.Decoder decoder=mode==0?new RadarDecoder.Adsb(listener):new RadarDecoder.Ais(listener,offset);
            String outcome="Stopped";
            try{if(file!=null){readFile(file,decoder);if(!cancel)outcome="IQ file completed";}else receive(mode,rfBand,gain,dvg,btChannel,decoder);}
            catch(Exception e){outcome="Error: "+(e.getMessage()==null?e.toString():e.getMessage());}
            finally{status=outcome;worker=null;handler.post(()->{if(!isDestroyed())update();});}
        },"D325f-radar-rx");worker.start();update();
    }
    private void readFile(Uri file,RadarDecoder.Decoder decoder)throws IOException{
        try(InputStream input=new BufferedInputStream(Objects.requireNonNull(getContentResolver().openInputStream(file)),65536)){
            byte[] buf=new byte[32768];int len=0;while(!cancel){int n=input.read(buf,len,buf.length-len);if(n<0)break;if(n==0)continue;len+=n;int even=len&~1;decoder.feed(buf,even);samples+=even/2;if(even<len)buf[0]=buf[even];len-=even;}
        }
    }
    private void receive(int mode,int rfBand,int gain,int dvg,int btChannel,RadarDecoder.Decoder decoder)throws Exception{
        Context app=getApplicationContext();LgModemSource source=new LgModemSource(FREQ[mode],rfBand,LgModemSource.TUNE_MODE_EXACT,0,LgModemSource.PROFILE_AUTO,20,gain,dvg);
        AtomicReference<String> error=new AtomicReference<>();CountDownLatch opened=new CountDownLatch(1);boolean attempted=false;
        source.setSampleRate(RadarDecoder.RATE);source.setInvertSpectrum(true);source.setDemodulationContinuityMode(true);
        try{
            source.open(app,new IQSourceInterface.Callback(){public void onIQSourceReady(IQSourceInterface s){opened.countDown();}public void onIQSourceError(IQSourceInterface s,String msg){error.compareAndSet(null,msg);opened.countDown();}});
            if(!opened.await(45,TimeUnit.SECONDS))throw new IOException("LG init timeout");if(error.get()!=null)throw new IOException(error.get());if(cancel)return;
            if(mode==1)BtCwController.get(app).setChannel(btChannel);
            attempted=true;source.startSampling();if(error.get()!=null)throw new IOException(error.get());status="RX running · CI8 · 7.68 MS/s";
            while(!cancel&&error.get()==null){byte[] iq=source.getPacket(1000);if(iq==null){if(!cancel)throw new IOException(error.get()==null?"No CI8 packets":error.get());break;}try{if(source.consumeDiscontinuity())decoder.reset();decoder.feed(iq,iq.length);samples+=iq.length/2;}finally{source.returnPacket(iq);}}
            if(!cancel&&error.get()!=null)throw new IOException(error.get());
        }finally{if(attempted){status="Restoring modem ONLINE…";if(!source.close())throw new IOException("Modem ONLINE restore unconfirmed; check original SDR or reboot");}}
    }
    private void update(){if(state==null)return;boolean busy=worker!=null;start.setEnabled(!busy);stop.setEnabled(busy&&!cancel);replay.setEnabled(!busy);clear.setEnabled(!busy);band.setEnabled(!busy);lna.setEnabled(!busy);dvga.setEnabled(!busy);bt.setEnabled(!busy&&tab==1);correction.setEnabled(!busy&&tab==1);
        long now=SystemClock.elapsedRealtime();List<RadarDecoder.Target> targets=trackers[tab].snapshot(now);long n=trackers[tab].frameCount();double rate=priorTab==tab&&now>priorTick&&n>=priorFrames?(n-priorFrames)*1000.0/(now-priorTick):0;priorTab=tab;priorFrames=n;priorTick=now;
        state.setText(status+(busy?String.format(Locale.US," · %.1f M samples",samples/1e6):""));summary.setText(String.format(Locale.US,"%d targets  ·  %d CRC frames  ·  %.1f/s",targets.size(),n,rate));
        StringBuilder txt=new StringBuilder();for(RadarDecoder.Target t:targets){txt.append(t.id);if(!t.name.isEmpty())txt.append("  ").append(t.name);if(tab==1)txt.append("  CH ").append(t.channel);txt.append("  ·  ").append(t.count).append(" msg  ·  ").append(Math.max(0,(now-t.last)/1000)).append(" s\n");
            if(!Double.isNaN(t.lat))txt.append(String.format(Locale.US,"%.5f, %.5f  ",t.lat,t.lon));if(!Double.isNaN(t.altitude))txt.append(String.format(Locale.US,"%.0f ft  ",t.altitude));if(!Double.isNaN(t.speed))txt.append(String.format(Locale.US,"%.1f kn  ",t.speed));if(!Double.isNaN(t.course))txt.append(String.format(Locale.US,"%.0f°  ",t.course));if(!t.status.isEmpty())txt.append(t.status);txt.append("\n\n");}
        rows.setText(txt.length()==0?"No valid messages yet.\nStart reception or open a raw CI8 file.":txt.toString());plot.targets=targets;plot.invalidate();
    }
    private static final class RadarPlot extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);List<RadarDecoder.Target> targets=Collections.emptyList();
        RadarPlot(Context c){super(c);setBackgroundColor(PANEL);}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float w=getWidth(),h=getHeight(),cx=w/2,cy=h/2,r=Math.min(w,h)*.42f;if(r<=0)return;
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1);p.setColor(0xff34525d);for(int k=1;k<=4;k++)c.drawCircle(cx,cy,r*k/4,p);c.drawLine(cx-r,cy,cx+r,cy,p);c.drawLine(cx,cy-r,cx,cy+r,p);p.setStyle(Paint.Style.FILL);p.setTextSize(12*getResources().getDisplayMetrics().scaledDensity);p.setColor(MUTED);c.drawText("N",cx-5,cy-r-3,p);
            List<RadarDecoder.Target> pos=new ArrayList<>();for(RadarDecoder.Target t:targets)if(!Double.isNaN(t.lat)&&!Double.isNaN(t.lon))pos.add(t);
            if(pos.isEmpty()){c.drawText("Waiting for coordinates",8,h-8,p);return;}
            double refLat=pos.get(0).lat,refLon=pos.get(0).lon,scale=111*Math.max(.01,Math.cos(Math.toRadians(refLat))),sumX=0,sumY=0;
            for(RadarDecoder.Target t:pos){sumX+=Math.IEEEremainder(t.lon-refLon,360)*scale;sumY+=(t.lat-refLat)*111;}double mx=sumX/pos.size(),my=sumY/pos.size(),range=1;
            for(RadarDecoder.Target t:pos)range=Math.max(range,Math.hypot(Math.IEEEremainder(t.lon-refLon,360)*scale-mx,(t.lat-refLat)*111-my)*1.15);
            c.drawText(String.format(Locale.US,"Auto center · %.1f km radius",range),8,h-8,p);for(RadarDecoder.Target t:pos){float x=cx+(float)((Math.IEEEremainder(t.lon-refLon,360)*scale-mx)*r/range),y=cy-(float)(((t.lat-refLat)*111-my)*r/range);p.setColor(ACCENT);c.drawCircle(x,y,4,p);if(!Double.isNaN(t.course)){double theta=Math.toRadians(t.course);c.drawLine(x,y,x+(float)(12*Math.sin(theta)),y-(float)(12*Math.cos(theta)),p);}p.setColor(FG);c.drawText(t.name.isEmpty()?t.id:t.name,x+6,y-4,p);}
        }
    }
}
