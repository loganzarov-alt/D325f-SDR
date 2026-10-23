import os, signal, subprocess, tempfile, time
from pathlib import Path
root=Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory() as d:
 d=Path(d);binary=d/'focus';config=d/'targets'
 subprocess.run(['gcc','-O2','-Wall','-Wextra','-DTESTING',str(root/'tools/focus/focus.c'),'-o',str(binary)],check=True)
 name='focus-test-'+str(os.getpid());config.write_text(name+'\n')
 def state(pid): return Path('/proc',str(pid),'stat').read_text().split(') ')[1][0]
 def waitstate(pid,paused):
  until=time.monotonic()+2
  while time.monotonic()<until:
   if (state(pid)=='T')==paused:return
   time.sleep(.01)
  raise AssertionError(('paused',paused,'actual',state(pid)))
 for mode in ['eof','timeout','signal','cpu-only']:
  config.write_text('' if mode=='cpu-only' else name+'\n')
  victim=subprocess.Popen([name,'30'],executable='/bin/sleep')
  already=subprocess.Popen([name,'30'],executable='/bin/sleep');os.kill(already.pid,signal.SIGSTOP)
  guard=None
  try:
   waitstate(already.pid,True)
   guard=subprocess.Popen([str(binary),str(config)],stdin=subprocess.PIPE,stdout=subprocess.PIPE,text=True)
   line=guard.stdout.readline();assert ('paused=0' if mode=='cpu-only' else 'paused=1') in line,line
   waitstate(victim.pid,mode!='cpu-only')
   if mode in ['eof','cpu-only']:guard.stdin.close()
   if mode=='signal':guard.terminate()
   guard.wait(timeout=6)
   waitstate(victim.pid,False);waitstate(already.pid,True)
   assert 'restored' in guard.stdout.read()
  finally:
   if guard and guard.poll() is None:guard.terminate();guard.wait(timeout=3)
   for p in [victim,already]:os.kill(p.pid,signal.SIGCONT);p.terminate();p.wait()
 print('PASS: focus restores on pipe close, missed UI heartbeat and signal; pre-stopped process preserved')
