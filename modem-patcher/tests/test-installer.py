#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Logan Zarov
"""Offline TWRP installer logic tests with filesystem/command bindings.
The native block target is mapped to a regular fixture; no device is accessed.
"""
from pathlib import Path
import hashlib,json,os,shutil,subprocess,zipfile
ROOT=Path(__file__).resolve().parent.parent
BASE=ROOT/'build/installer-tests'
if BASE.exists():shutil.rmtree(BASE)
BASE.mkdir(parents=True)
for name in ['twres','tmp','bin','releases','data/media/0/D325f']:(BASE/name).mkdir(parents=True,exist_ok=True)
STOCK=Path(os.environ['STOCK_IMAGE']);FIXTURE=BASE/'fixture.bin';shutil.copyfile(STOCK,FIXTURE)
TOOL=ROOT/'build/recovery-tests/modem-toggle-host'
if not TOOL.exists():raise SystemExit('Run D325_HOST_TESTS=1 recovery/test-toggle.py first')
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
for label in ['Toggle','Restore-Dump']:shutil.copyfile(ROOT/('dist/LG-D325-Modem-'+label+'-TWRP-v1.2.zip'),BASE/'releases'/('LG-D325-Modem-'+label+'-TWRP-v1.2.zip'))
script=(ROOT/'recovery/update-binary').read_text()
script=script.replace('WORK=/tmp/d325-modem-toggle-$$','WORK=$D325_FIXTURE_ROOT/tmp/d325-modem-toggle-$$')
script=script.replace('[ -d /twres ]','[ -d "$D325_FIXTURE_ROOT/twres" ]')
script=script.replace("grep -q ' /data ' /proc/mounts",'true')
script=script.replace('DUMPS=/data/media/0/D325f','DUMPS=$D325_FIXTURE_ROOT/data/media/0/D325f')
installer=BASE/'installer';installer.write_text(script)
getprop=BASE/'bin/getprop';getprop.write_text('#!/bin/sh\ncase "$1" in ro.product.model) echo "${D325_FIXTURE_MODEL:-LG-D325}";; ro.product.device) echo w5ds;; esac\n');getprop.chmod(0o755)
unzip=BASE/'bin/unzip'
unzip.write_text('''#!/usr/bin/env python3
import os,sys,zipfile
from pathlib import Path
args=sys.argv[1:];archive=Path(args[1]);dest=Path(args[args.index('-d')+1]);names=args[2:args.index('-d')]
with zipfile.ZipFile(archive) as z:
 for name in names:
  p=dest/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(z.read(name))
scan=dest/'scan-partition.sh';scan.write_text("printf 'D325F_UID=0\\\\nD325F_CAND\\\\tMOUNT\\\\t/dev/block/fixture\\\\t131072\\\\n'\\n")
tool=dest/'tools/modem-toggle'
tool.write_text("#!/usr/bin/env python3\\nimport os,sys\\na=sys.argv[1:]\\nif '--block' in a:\\n i=a.index('--block');a[i]='--image';a[i+1]=os.environ['D325_FIXTURE_IMAGE']\\nos.execv(os.environ['D325_FIXTURE_TOOL'],[os.environ['D325_FIXTURE_TOOL']]+a)\\n")
tool.chmod(0o755)
''')
unzip.chmod(0o755)
env=dict(os.environ,PATH=str(BASE/'bin')+os.pathsep+os.environ['PATH'],D325_FIXTURE_ROOT=str(BASE),D325_FIXTURE_IMAGE=str(FIXTURE),D325_FIXTURE_TOOL=str(TOOL))
results=[];logs=[]
def run(label='Toggle',success=True):
 p=subprocess.run(['sh',str(installer),'3','1',str(BASE/('releases/LG-D325-Modem-'+label+'-TWRP-v1.2.zip'))],capture_output=True,text=True,env=env,timeout=120)
 logs.append(p.stdout+p.stderr)
 if success and (p.returncode or 'READBACK_VERIFIED' not in p.stdout):raise RuntimeError(p.stdout+p.stderr)
 if not success and not p.returncode:raise AssertionError('Installer unexpectedly succeeded')
 return p
def passed(text):results.append({'test':text,'passed':True});print('PASS',text,flush=True)
run();patched=sha(FIXTURE);assert patched!=sha(STOCK)
stock_dump=next((BASE/'data/media/0/D325f').glob('modem-dump-stock-*.bin'));assert sha(stock_dump)==sha(STOCK)
passed('toggle creates persistent stock dump before writing')
run();assert sha(FIXTURE)==sha(STOCK);passed('second toggle returns exact stock and retains both dumps')
nextdump=BASE/'releases'/stock_dump.name;shutil.copyfile(stock_dump,nextdump);shutil.copyfile(Path(str(stock_dump)+'.sha256'),Path(str(nextdump)+'.sha256'))
with FIXTURE.open('r+b') as f:f.seek(510);f.write(b'\0')
run();assert sha(FIXTURE)==sha(STOCK);passed('toggle ZIP restores UNKNOWN from adjacent supported dump')
run();assert sha(FIXTURE)==patched;run('Restore-Dump');assert sha(FIXTURE)==sha(STOCK);passed('restore ZIP restores dump over healthy patched state')
patched_dump=next((BASE/'data/media/0/D325f').glob('modem-dump-patched-*.bin'));extra=BASE/'releases'/patched_dump.name;shutil.copyfile(patched_dump,extra)
before=sha(FIXTURE);run('Restore-Dump',False);assert sha(FIXTURE)==before;passed('multiple supported adjacent dumps rejected without writing')
extra.unlink();side=Path(str(nextdump)+'.sha256');old=side.read_text();side.write_text('0'*64+'  '+nextdump.name+'\n')
run('Restore-Dump',False);assert sha(FIXTURE)==before;side.write_text(old);passed('corrupt adjacent checksum rejected without writing')
(ROOT/'reports/installer-tests.log').write_text('\n'.join(logs))
(ROOT/'reports/installer-tests.json').write_text(json.dumps({'tests':results,'environment':'offline filesystem bindings and regular-file native targets','real_device_writes':0},indent=2)+'\n')
print('PASS',len(results),'offline installer tests')
