"""Exercise the shipped GLSL on Mesa ES2: orientation, ring wrap, stale row masking."""
import os,ctypes as c,re
from pathlib import Path
os.environ.setdefault('EGL_PLATFORM','surfaceless')
e=c.CDLL('libEGL.so.1');I=c.c_int;P=c.c_void_p;F=c.c_float;U=c.c_uint

def egl(name,ret,args):
 f=getattr(e,name);f.restype=ret;f.argtypes=args;return f
get=egl('eglGetDisplay',P,[P]);init=egl('eglInitialize',U,[P,P,P]);choose=egl('eglChooseConfig',U,[P,P,P,I,P]);ctx=egl('eglCreateContext',P,[P,P,P,P]);surf=egl('eglCreatePbufferSurface',P,[P,P,P]);make=egl('eglMakeCurrent',U,[P,P,P,P]);proc=egl('eglGetProcAddress',P,[c.c_char_p])
d=get(None);a=I();b=I();assert init(d,c.byref(a),c.byref(b))
attrs=(I*15)(0x3040,4,0x3033,1,0x3024,8,0x3023,8,0x3022,8,0x3021,8,0x3038,0,0)
config=P();n=I();assert choose(d,attrs,c.byref(config),1,c.byref(n)) and n.value
context=ctx(d,config,None,(I*3)(0x3098,2,0x3038));surface=surf(d,config,(I*5)(0x3057,8,0x3056,8,0x3038));assert make(d,surface,surface,context)
def gl(name,ret,args):return c.CFUNCTYPE(ret,*args)(proc(name.encode()))
create=gl('glCreateShader',U,[U]);source=gl('glShaderSource',None,[U,I,P,P]);compile=gl('glCompileShader',None,[U]);status=gl('glGetShaderiv',None,[U,U,P])
java=Path('app/src/main/java/com/mantz_it/rfanalyzer/ui/GpuWaterfall.java').read_text()
shaders=[]
for name,typ in [('vs',0x8B31),('fs',0x8B30)]:
 text=re.search(r'String '+name+r'="([^"]+)"',java).group(1).encode();s=create(typ);strings=(c.c_char_p*1)(text);source(s,1,strings,None);compile(s);ok=I();status(s,0x8B81,c.byref(ok));assert ok.value, name;shaders.append(s)
p=gl('glCreateProgram',U,[])();attach=gl('glAttachShader',None,[U,U]);[attach(p,s) for s in shaders];gl('glLinkProgram',None,[U])(p);ok=I();gl('glGetProgramiv',None,[U,U,P])(p,0x8B82,c.byref(ok));assert ok.value;gl('glUseProgram',None,[U])(p)
verts=(F*16)(-1,1,0,0,-1,-1,0,1,1,1,1,0,1,-1,1,1)
for name,offset in [(b'p',0),(b't',8)]:
 loc=gl('glGetAttribLocation',I,[U,c.c_char_p])(p,name);gl('glEnableVertexAttribArray',None,[U])(loc);gl('glVertexAttribPointer',None,[U,I,U,U,I,P])(loc,2,0x1406,0,16,c.cast(c.byref(verts,offset),P))
tex=U();gl('glGenTextures',None,[I,P])(1,c.byref(tex));gl('glBindTexture',None,[U,U])(0x0DE1,tex)
param=gl('glTexParameteri',None,[U,U,I]);
for a,b in [(0x2801,0x2600),(0x2800,0x2600),(0x2802,0x812F),(0x2803,0x812F)]:param(0x0DE1,a,b)
# Non-power-of-two width deliberately tests the ES2 clamp + manual wrap path.
pixels=(c.c_ubyte*(3*8*4))(*[component for y in range(8) for x in range(3) for component in (y*25, x*80, 10,255)])
gl('glTexImage2D',None,[U,I,I,I,I,I,U,U,P])(0x0DE1,0,0x1908,3,8,0,0x1908,0x1401,pixels)
uniform=gl('glGetUniformLocation',I,[U,c.c_char_p]);uf=gl('glUniform1f',None,[I,F]);ui=gl('glUniform1i',None,[I,I]);ui(uniform(p,b'tex'),0)
def check(head,lag,valid,scale,ring=1):
 for key,value in [('ring',ring),('off',(head+lag)/8),('span',1/scale),('available',(valid-lag)/8),('texel',1/8)]:uf(uniform(p,key.encode()),value)
 gl('glViewport',None,[I,I,I,I])(0,0,8,8);gl('glDrawArrays',None,[U,I,I])(5,0,4)
 data=(c.c_ubyte*256)();gl('glReadPixels',None,[I,I,I,I,U,U,P])(0,0,8,8,0x1908,0x1401,data)
 for screen_y in range(8):
  src=lag+(screen_y+.5)/scale
  if ring and src>=valid: expected=5
  elif ring:
   pos=head+src-0.5; base=int(__import__('math').floor(pos)); blend=pos-base
   aa=(base%8)*25; bb=((base+1)%8)*25
   if src+1-blend>=valid:bb=aa
   expected=aa*(1-blend)+bb*blend
  else: expected=screen_y*25
  actual=data[((7-screen_y)*8+4)*4]
  assert abs(actual-expected)<=1,(head,lag,valid,scale,screen_y,actual,expected)
for args in [(0,0,8,1),(6,0,8,1),(7,.25,8,1),(7,.75,8,1),(4,0,1,2),(4,0,3,1),(0,0,8,1,0)]:check(*args)
assert gl('glGetError',U,[])()==0
print('PASS: actual ES2 shaders, NPOT width, ring wrap, vertical orientation, fractional scroll, first row and uninitialized-history masking')

# Compile and render the actual spectrum shader, including its filled mode.
trace_shaders=[]
for name,typ in [('traceVs',0x8B31),('traceFs',0x8B30)]:
 text=re.search(r'String '+name+r'="([^"]+)"',java).group(1).encode()
 s=create(typ);source(s,1,(c.c_char_p*1)(text),None);compile(s)
 ok=I();status(s,0x8B81,c.byref(ok));assert ok.value,name;trace_shaders.append(s)
tp=gl('glCreateProgram',U,[])()
for s in trace_shaders:attach(tp,s)
gl('glLinkProgram',None,[U])(tp);gl('glGetProgramiv',None,[U,U,P])(tp,0x8B82,c.byref(ok));assert ok.value
gl('glUseProgram',None,[U])(tp)
pos=gl('glGetAttribLocation',I,[U,c.c_char_p])(tp,b'p')
gl('glUniform4f',None,[I,F,F,F,F])(uniform(tp,b'color'),52/255,229/255,196/255,1)
uf(uniform(tp,b'gradient'),1)
# Constant -50 dB in a -100..0 dB viewport occupies exactly the lower half.
trace_vertices=(F*8)(-1,0,-1,-1,1,0,1,-1)
gl('glEnableVertexAttribArray',None,[U])(pos)
gl('glVertexAttribPointer',None,[U,I,U,U,I,P])(pos,2,0x1406,0,0,trace_vertices)
gl('glClearColor',None,[F,F,F,F])(5/255,10/255,15/255,1)
gl('glClear',None,[U])(0x4000)
gl('glDrawArrays',None,[U,I,I])(5,0,4)
data=(c.c_ubyte*256)();gl('glReadPixels',None,[I,I,I,I,U,U,P])(0,0,8,8,0x1908,0x1401,data)
for y in range(8):
 pixel=list(data[(y*8+4)*4:(y*8+4)*4+4])
 if y>=4:assert pixel[:3]==[5,10,15],(y,pixel)
 else:assert pixel[1]>220 and pixel[2]>=196,(y,pixel)
assert gl('glGetError',U,[])()==0
print('PASS: GPU spectrum shader compiles and preserves calibrated vertical scale and gradient')
