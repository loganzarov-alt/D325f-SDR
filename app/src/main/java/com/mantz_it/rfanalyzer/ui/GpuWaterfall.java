// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Logan Zarov
// Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
package com.mantz_it.rfanalyzer.ui;

import android.graphics.Bitmap;
import android.opengl.*;
import android.view.Surface;
import java.nio.*;

/** EGL renderer owned by the existing surface drawing thread. ES 2.0 / API 25. */
public final class GpuWaterfall implements AutoCloseable {
    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface surface = EGL14.EGL_NO_SURFACE;
    private int program, position, uv, sampler, ring, offset, span, available, texel;
    private final int[] textures = new int[3];
    private int traceProgram, tracePosition, traceColor, traceGradient;
    private FloatBuffer trace, fill, peaks;
    private int traceCount, peakCount;
    private int statsWidth, statsHeight;
    private final FloatBuffer vertices = ByteBuffer.allocateDirect(16*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    private ByteBuffer row;
    private int width, historyHeight, head, valid, overlayWidth, overlayHeight;
    private final WaterfallMotion motion = new WaterfallMotion();

    public GpuWaterfall(Surface nativeSurface) {
        try {
            display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version=new int[2];
            require(EGL14.eglInitialize(display,version,0,version,1),"eglInitialize");
            int[] attributes={EGL14.EGL_RENDERABLE_TYPE,4,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,0,EGL14.EGL_NONE};
            EGLConfig[] configs=new EGLConfig[1]; int[] count=new int[1];
            require(EGL14.eglChooseConfig(display,attributes,0,configs,0,1,count,0)&&count[0]>0,"eglChooseConfig");
            context=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
            require(context!=EGL14.EGL_NO_CONTEXT,"eglCreateContext");
            surface=EGL14.eglCreateWindowSurface(display,configs[0],nativeSurface,new int[]{EGL14.EGL_NONE},0);
            require(surface!=EGL14.EGL_NO_SURFACE,"eglCreateWindowSurface");
            require(EGL14.eglMakeCurrent(display,surface,surface,context),"eglMakeCurrent");
            EGL14.eglSwapInterval(display,1);
            String vs="attribute vec2 p; attribute vec2 t; varying vec2 v; void main(){v=t;gl_Position=vec4(p,0.,1.);}";
            String fs="precision highp float; varying vec2 v; uniform sampler2D tex; uniform float ring,off,span,available,texel; void main(){float y=v.y; if(ring>0.5){float distance=y*span; if(distance>=available){gl_FragColor=vec4(0.0196,0.0392,0.0588,1.);return;}float pos=(off+distance)/texel-0.5;float base=floor(pos);float blend=fract(pos);vec4 a=texture2D(tex,vec2(v.x,fract((base+0.5)*texel)));vec4 b=texture2D(tex,vec2(v.x,fract((base+1.5)*texel)));if(distance+(1.0-blend)*texel>=available)b=a;gl_FragColor=mix(a,b,blend);return;}gl_FragColor=texture2D(tex,vec2(v.x,y));}";
            int a=shader(GLES20.GL_VERTEX_SHADER,vs),b=shader(GLES20.GL_FRAGMENT_SHADER,fs);
            program=GLES20.glCreateProgram(); GLES20.glAttachShader(program,a);GLES20.glAttachShader(program,b);GLES20.glLinkProgram(program);
            int[] status=new int[1];GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,status,0);
            require(status[0]!=0,GLES20.glGetProgramInfoLog(program));
            GLES20.glDeleteShader(a);GLES20.glDeleteShader(b);
            position=GLES20.glGetAttribLocation(program,"p");uv=GLES20.glGetAttribLocation(program,"t");
            sampler=GLES20.glGetUniformLocation(program,"tex");ring=GLES20.glGetUniformLocation(program,"ring");
            offset=GLES20.glGetUniformLocation(program,"off");span=GLES20.glGetUniformLocation(program,"span");available=GLES20.glGetUniformLocation(program,"available");texel=GLES20.glGetUniformLocation(program,"texel");
            String traceVs="attribute vec2 p; varying float level; void main(){gl_Position=vec4(p,0.,1.);gl_PointSize=2.;level=(p.y+1.)*.5;}";
            String traceFs="precision mediump float; varying float level; uniform vec4 color; uniform float gradient; void main(){vec3 top=vec3(174.,255.,241.)/255.;gl_FragColor=vec4(mix(color.rgb,top,clamp(level,0.,1.)*gradient),color.a);}";
            a=shader(GLES20.GL_VERTEX_SHADER,traceVs);b=shader(GLES20.GL_FRAGMENT_SHADER,traceFs);
            traceProgram=GLES20.glCreateProgram();GLES20.glAttachShader(traceProgram,a);GLES20.glAttachShader(traceProgram,b);GLES20.glLinkProgram(traceProgram);
            GLES20.glGetProgramiv(traceProgram,GLES20.GL_LINK_STATUS,status,0);require(status[0]!=0,GLES20.glGetProgramInfoLog(traceProgram));
            GLES20.glDeleteShader(a);GLES20.glDeleteShader(b);
            tracePosition=GLES20.glGetAttribLocation(traceProgram,"p");traceColor=GLES20.glGetUniformLocation(traceProgram,"color");traceGradient=GLES20.glGetUniformLocation(traceProgram,"gradient");
            GLES20.glGenTextures(3,textures,0);
            for(int id:textures){GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,id);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_NEAREST);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_NEAREST);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);}
            check("init");
        } catch(RuntimeException error){close();throw error;}
    }
    private static void require(boolean success,String message){if(!success)throw new IllegalStateException(message);}
    private static int shader(int type,String source){int s=GLES20.glCreateShader(type);GLES20.glShaderSource(s,source);GLES20.glCompileShader(s);int[] ok=new int[1];GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0);require(ok[0]!=0,GLES20.glGetShaderInfoLog(s));return s;}
    private static void check(String where){int error=GLES20.glGetError();require(error==GLES20.GL_NO_ERROR,where+": GL "+error);}

    public void reset(int w,int visibleHeight){
        int h=1;while(h<visibleHeight+16)h*=2;
        if(width!=w||historyHeight!=h){
            int[] limit=new int[1];GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE,limit,0);
            require(w<=limit[0]&&h<=limit[0],"waterfall exceeds texture size");
            width=w;historyHeight=h;row=ByteBuffer.allocateDirect(w*4);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,textures[0]);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,w,h,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null);
            check("history allocation");
        }
        head=0;valid=0;motion.reset();
    }
    public void append(int[] colors,long measuredNs){
        require(colors.length==width,"row width changed");
        row.clear();for(int c:colors){row.put((byte)(c>>16));row.put((byte)(c>>8));row.put((byte)c);row.put((byte)255);}row.flip();
        head=(head+historyHeight-1)%historyHeight;
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,textures[0]);
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D,0,0,head,width,1,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,row);
        valid=Math.min(valid+1,historyHeight);motion.append(measuredNs);
    }
    public void uploadOverlay(Bitmap bitmap){
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,textures[1]);
        if(overlayWidth!=bitmap.getWidth()||overlayHeight!=bitmap.getHeight()){
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,bitmap,0);overlayWidth=bitmap.getWidth();overlayHeight=bitmap.getHeight();
        }else GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D,0,0,0,bitmap);
        check("overlay upload");
    }
    public void uploadStats(Bitmap bitmap){
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,textures[2]);
        if(statsWidth!=bitmap.getWidth()||statsHeight!=bitmap.getHeight()){
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,bitmap,0);statsWidth=bitmap.getWidth();statsHeight=bitmap.getHeight();
        }else GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D,0,0,0,bitmap);
        check("stats upload");
    }
    private static FloatBuffer floats(int count){return ByteBuffer.allocateDirect(count*4).order(ByteOrder.nativeOrder()).asFloatBuffer();}
    public void updateSpectrum(float[] db,float low,float high,float[] peakY,int fftHeight){
        int n=db.length;
        if(trace==null||trace.capacity()!=n*2){trace=floats(n*2);fill=floats(n*4);peaks=floats(n*2);}
        trace.clear();fill.clear();peaks.clear();
        for(int x=0;x<n;x++){
            float px=2f*x/n-1, y=2f*Math.max(0,Math.min(1,(db[x]-low)/(high-low)))-1;
            trace.put(px).put(y);fill.put(px).put(y).put(px).put(-1);
            if(peakY!=null&&x<peakY.length)peaks.put(px).put(1-2f*peakY[x]/fftHeight);
        }
        trace.flip();fill.flip();peaks.flip();traceCount=n;peakCount=peaks.remaining()/2;
    }
    private void spectrum(int w,int h,int fftHeight,boolean filled){
        if(traceCount==0)return;
        GLES20.glViewport(0,h-fftHeight,w,fftHeight);
        GLES20.glUseProgram(traceProgram);
        GLES20.glDisableVertexAttribArray(uv);
        GLES20.glUniform4f(traceColor,52/255f,229/255f,196/255f,1);
        GLES20.glUniform1f(traceGradient,1);
        FloatBuffer points=filled?fill:trace;points.position(0);
        GLES20.glVertexAttribPointer(tracePosition,2,GLES20.GL_FLOAT,false,0,points);GLES20.glEnableVertexAttribArray(tracePosition);
        GLES20.glLineWidth(1);
        GLES20.glDrawArrays(filled?GLES20.GL_TRIANGLE_STRIP:GLES20.GL_LINE_STRIP,0,filled?traceCount*2:traceCount);
        if(peakCount>0){
            GLES20.glUniform4f(traceColor,1,1,0,1);GLES20.glUniform1f(traceGradient,0);peaks.position(0);
            GLES20.glVertexAttribPointer(tracePosition,2,GLES20.GL_FLOAT,false,0,peaks);GLES20.glDrawArrays(GLES20.GL_POINTS,0,peakCount);
        }
        GLES20.glDisableVertexAttribArray(tracePosition);
        GLES20.glViewport(0,0,w,h);
    }
    private void quad(int texture,float top,float bottom,float ringMode,float off,float extent,float availableRows){
        vertices.clear();vertices.put(-1).put(top).put(0).put(0).put(-1).put(bottom).put(0).put(1).put(1).put(top).put(1).put(0).put(1).put(bottom).put(1).put(1);vertices.flip();
        GLES20.glUseProgram(program);GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture);
        GLES20.glUniform1i(sampler,0);GLES20.glUniform1f(ring,ringMode);GLES20.glUniform1f(offset,off);GLES20.glUniform1f(span,extent);GLES20.glUniform1f(available,availableRows);GLES20.glUniform1f(texel,historyHeight>0?1f/historyHeight:1f);
        vertices.position(0);GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,16,vertices);GLES20.glEnableVertexAttribArray(position);
        vertices.position(2);GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,16,vertices);GLES20.glEnableVertexAttribArray(uv);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
    }
    private boolean frozen;
    private float frozenLag;
    public void setFrozen(boolean value) {
        if (value && !frozen) frozenLag = (float)motion.lag(System.nanoTime());
        frozen = value;
    }
    public boolean isAnimating(){return motion.isAnimating();}
    public void render(int w,int h,int fftHeight,boolean filled,float channelX){
        GLES20.glViewport(0,0,w,h);GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glClearColor(5/255f,10/255f,15/255f,1);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        float split=1-2f*fftHeight/h;
        if(valid>0){
            float lag=frozen?frozenLag:(float)motion.lag(System.nanoTime());
            // Keep sampling inside initialized rows and away from the circular seam.
            // Always one measurement per physical pixel: speed NEVER magnifies rows.
            float extent=(h-fftHeight)/(float)historyHeight;
            quad(textures[0],split,-1,1,(head+lag)/historyHeight,extent,Math.max(0,valid-lag)/historyHeight);
        }
        spectrum(w,h,fftHeight,filled);
        GLES20.glEnable(GLES20.GL_BLEND);GLES20.glBlendFunc(GLES20.GL_ONE,GLES20.GL_ONE_MINUS_SRC_ALPHA);
        if(overlayWidth>0)quad(textures[1],1,split,0,0,1,1);
        if(statsWidth>0)quad(textures[2],1,split,0,0,1,1);
        GLES20.glDisable(GLES20.GL_BLEND);
        if(channelX>=0&&channelX<w){GLES20.glEnable(GLES20.GL_SCISSOR_TEST);GLES20.glScissor((int)channelX,0,1,h-fftHeight);GLES20.glClearColor(1,1,0,1);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);GLES20.glDisable(GLES20.GL_SCISSOR_TEST);}
        check("render");require(EGL14.eglSwapBuffers(display,surface),"eglSwapBuffers");
    }
    @Override public void close(){
        if(display!=EGL14.EGL_NO_DISPLAY){
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
            if(surface!=EGL14.EGL_NO_SURFACE)EGL14.eglDestroySurface(display,surface);
            if(context!=EGL14.EGL_NO_CONTEXT)EGL14.eglDestroyContext(display,context);
            EGL14.eglTerminate(display);EGL14.eglReleaseThread();
        }
        display=EGL14.EGL_NO_DISPLAY;context=EGL14.EGL_NO_CONTEXT;surface=EGL14.EGL_NO_SURFACE;
    }
}
