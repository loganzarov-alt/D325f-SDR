import os, pty, tty, subprocess, tempfile, threading, time, select
from pathlib import Path
root=Path(__file__).resolve().parents[2]
binary=root/'tests/pipeline/build/bt-guard-test'
binary.parent.mkdir(exist_ok=True)
subprocess.run(['gcc','-DTESTING','-O2','-Wall','-Wextra',str(root/'tools/btcw/btcw.c'),'-o',str(binary)],check=True)
for mode in ['eof','stall','live','no_ack','rejected']:
    with tempfile.TemporaryDirectory() as d:
        master,slave=pty.openpty();tty.setraw(slave)
        commands=[];done=threading.Event()
        def hci():
            data=b''
            while not done.is_set():
                if not select.select([master],[],[],.05)[0]:continue
                data+=os.read(master,4096)
                while len(data)>=3 and len(data)>=3+data[2]:
                    cmd,data=data[:3+data[2]],data[3+data[2]:]
                    commands.append(cmd)
                    if mode!='no_ack':os.write(master,bytes([14,4,1,cmd[0],cmd[1],1 if mode=='rejected' and cmd[0]==4 else 0]))
        t=threading.Thread(target=hci);t.start()
        env=dict(os.environ,BTCW_TEST_DIR=d,BTCW_TEST_DEVICE=os.ttyname(slave))
        p=subprocess.Popen([str(binary),'guard','78','0','0'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,env=env)
        if mode=='live':
            for _ in range(14):
                p.stdin.write(b'H');p.stdin.flush();time.sleep(.25)
            assert p.poll() is None, 'guardian stopped despite live IQ'
        elif mode not in ['no_ack','rejected']:time.sleep(.35)
        if mode!='stall':p.stdin.close()
        p.wait(timeout=6)
        state=Path(d,'status').read_text()
        done.set();t.join();os.close(master);os.close(slave)
        assert commands[0]==bytes([4,252,9,5,78,0,4,32,0,0,0,0]),commands
        assert any(c==bytes([3,12,0]) for c in commands),commands
        assert ('STOP_UNCONFIRMED' if mode=='no_ack' else 'STATE=STOPPED') in state,state
        if mode=='stall':assert 'RX_HEARTBEAT_LOST' in state,state
        print('PASS BT guardian:',mode)
