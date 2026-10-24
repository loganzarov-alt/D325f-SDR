# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Logan Zarov
# Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
import os,pty,tty,threading,select,tempfile,subprocess
from pathlib import Path
root=Path(__file__).resolve().parents[2]
build=root/'tests/pipeline/build'
sources=list((root/'tests/pipeline/bt').rglob('*.java'))+[root/'app/src/main/java/com/mantz_it/rfanalyzer/source/BtCwController.java']
subprocess.run(['javac','-d',str(build),*map(str,sources)],check=True)
with tempfile.TemporaryDirectory() as d:
 master,slave=pty.openpty();tty.setraw(slave);done=threading.Event()
 def hci():
  data=b''
  while not done.is_set():
   if not select.select([master],[],[],.05)[0]:continue
   data+=os.read(master,4096)
   while len(data)>=3 and len(data)>=3+data[2]:
    cmd,data=data[:3+data[2]],data[3+data[2]:]
    os.write(master,bytes([14,4,1,cmd[0],cmd[1],0]))
 t=threading.Thread(target=hci);t.start()
 stub=Path(d,'bin');stub.mkdir()
 su=stub/'su';su.write_text('#!/bin/sh\ncase "$2" in *"svc bluetooth disable"*) exit 0;; esac\nexec /bin/sh "$@"\n');su.chmod(0o700)
 env=dict(os.environ,PATH=str(stub)+':'+os.environ['PATH'],BTCW_TEST_DIR=d,BTCW_TEST_DEVICE=os.ttyname(slave),BTCW_TEST_BINARY=str(build/'bt-guard-test'))
 try:subprocess.run(['java','-cp',str(build),'TestBtController'],env=env,check=True,timeout=35)
 finally:done.set();t.join();os.close(master);os.close(slave)
