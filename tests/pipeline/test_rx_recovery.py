"""Exercise real LgRxRecovery state transitions with an injected root runner."""
from pathlib import Path
import tempfile, subprocess
root=Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory() as tmp:
 d=Path(tmp); log=d/'android/util/Log.java';log.parent.mkdir(parents=True)
 log.write_text('package android.util; public class Log {public static int i(String a,String b){return 0;}public static int w(String a,String b){return 0;}}')
 t=d/'TestRecovery.java'
 t.write_text('''package com.mantz_it.rfanalyzer.source;
import java.io.*;
public class TestRecovery {
 static int calls;
 static void fail(Object s)throws Exception {LgRxRecovery.beforeStart(s,null,null);LgRxRecovery.failed(s,"test");LgRxRecovery.failed(s,"duplicate");}
 public static void main(String[] args)throws Exception {
  LgRxRecovery.testRunner(f->{calls++;return calls%2==1?"response=0204000000000001010000":"response=0204000000000001010002";});
  Object a=new Object();fail(a);if(calls!=0)throw new AssertionError("premature recovery");
  fail(a);if(calls!=0)throw new AssertionError("recovery before next start");
  LgRxRecovery.beforeStart(a,null,null);if(calls!=2)throw new AssertionError("expected ONLINE + FACTORY");
  LgRxRecovery.received(a);LgRxRecovery.failed(a,"after ready");
  LgRxRecovery.beforeStart(a,null,null);LgRxRecovery.received(a);
  if(calls!=2)throw new AssertionError("healthy session counted as failed");
  LgRxRecovery.testRunner(f->{calls++;return "bad mode";});calls=0;fail(a);fail(a);
  boolean rejected=false;try{LgRxRecovery.beforeStart(a,null,null);}catch(IOException ex){rejected=true;}
  if(!rejected||calls!=3)throw new AssertionError("unconfirmed mode must block retry");
  IOException cause=new IOException("root failed");
  LgRxRecovery.testRunner(f->{throw cause;});fail(a);fail(a);
  try {LgRxRecovery.beforeStart(a,null,null);throw new AssertionError("missing failure");}
  catch(IOException ex){if(ex.getCause()!=cause)throw new AssertionError("last IOException cause lost");}
  System.out.println("PASS: two failures trigger recovery, duplicate errors count once, first IQ resets failures, unconfirmed mode blocks start");
 }
}''')
 subprocess.run(['javac','-d',str(d),str(log),str(t),str(root/'app/src/main/java/com/mantz_it/rfanalyzer/source/LgRxRecovery.java')],check=True)
 subprocess.run(['java','-cp',str(d),'com.mantz_it.rfanalyzer.source.TestRecovery'],check=True)
