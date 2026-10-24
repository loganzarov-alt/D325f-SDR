#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Logan Zarov
"""Fixture-only regression tests. Never opens or writes a phone partition."""
from pathlib import Path
import hashlib,json,os,shutil,struct,subprocess
ROOT=Path(__file__).resolve().parent.parent
REC=ROOT/'recovery';BUILD=ROOT/'build/recovery-tests';BUILD.mkdir(parents=True,exist_ok=True)
HOST=os.environ.get('D325_HOST_TESTS')=='1'
CC=os.environ.get('HOST_CC','cc') if HOST else os.environ.get('ARM_CC',str(ROOT/'toolchain/musl-arm/bin/musl-gcc'))
FLAGS=['-std=c99','-O2','-Wall','-Wextra','-Werror']+([] if HOST else ['-static','-march=armv7-a'])
TOOL=BUILD/'modem-toggle-host' if HOST else REC/'tools/modem-toggle'
if HOST:subprocess.run([CC,*FLAGS,str(REC/'modem-toggle.c'),str(REC/'sha256.c'),'-o',str(TOOL)],check=True)
FAULT=BUILD/'modem-toggle-fault'
subprocess.run([CC,*FLAGS,'-DD325_TESTING',str(REC/'modem-toggle.c'),str(REC/'sha256.c'),'-o',str(FAULT)],check=True)
PAYLOAD=REC/'patch.payload';STOCK=Path(os.environ.get('STOCK_IMAGE','stock.bin'));WORKING=Path(os.environ.get('WORKING_IMAGE','patched.bin'))
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
assert sha(PAYLOAD)=='10fc481c411f22153fb570e970b0eecb652f2360ca9d7a79df56a57c377e2ba8'
assert sha(STOCK)=='1d6d32af2821cc96e9f80b6f9bf9d381b73e9ae60b1bec6fe45786603578f455'
results=[]
def passed(name,**details):results.append(dict(test=name,passed=True,**details));print('PASS',name,flush=True)
def run(image,args=('--toggle',),tool=TOOL,extra_env=None,ok=True):
    env=dict(os.environ);env.update(extra_env or {})
    command=([] if HOST else ['qemu-arm'])+[str(tool),'--image',str(image),'--payload',str(PAYLOAD),*map(str,args)]
    p=subprocess.run(command,capture_output=True,text=True,env=env,timeout=240)
    print(p.stdout+p.stderr,end='',flush=True)
    if ok and p.returncode:raise RuntimeError(p.stdout+p.stderr)
    if not ok and not p.returncode:raise RuntimeError('Unsafe fixture/operation was accepted')
    return p
for name,source in [('stock',STOCK),('working',WORKING)]:
    fixture=BUILD/(name+'.bin');shutil.copyfile(source,fixture);before=sha(fixture)
    first=run(fixture);between=sha(fixture);run(fixture)
    assert 'CHANGED_SECTORS 20' in first.stdout and sha(fixture)==before and before!=between
    passed(name+' toggle twice is exact; 20 sectors',before=before,between=between);fixture.unlink()
image=bytearray(STOCK.read_bytes());bps=struct.unpack_from('<H',image,11)[0];cluster=image[13]*bps
reserved=struct.unpack_from('<H',image,14)[0];nfats=image[16];entries=struct.unpack_from('<H',image,17)[0];fatbytes=struct.unpack_from('<H',image,22)[0]*bps
fat=reserved*bps;root=fat+nfats*fatbytes;data=root+((entries*32+bps-1)//bps)*bps
def entry(path):
    off=root;count=entries
    for component in path.split('/'):
        found=None
        for p in range(off,off+count*32,32):
            if image[p]==0:break
            if image[p]==229 or image[p+11]==15 or image[p+11]&8:continue
            n=bytes(image[p:p+8]).decode().strip();e=bytes(image[p+8:p+11]).decode().strip();n+=('.'+e) if e else ''
            if n==component:found=p;break
        assert found is not None
        off=data+(struct.unpack_from('<H',image,found+26)[0]-2)*cluster;count=cluster//32
    return found,off
p,oldoff=entry('IMAGE/MODEM.MDT');oldcluster=struct.unpack_from('<H',image,p+26)[0]
available=[c for c in range(2,(len(image)-data)//cluster+2) if struct.unpack_from('<H',image,fat+c*2)[0]==0]
newcluster=available[-1];newoff=data+(newcluster-2)*cluster
image[newoff:newoff+cluster]=image[oldoff:oldoff+cluster];image[oldoff:oldoff+cluster]=bytes(cluster)
for table in range(nfats):
    struct.pack_into('<H',image,fat+table*fatbytes+oldcluster*2,0);struct.pack_into('<H',image,fat+table*fatbytes+newcluster*2,0xffff)
struct.pack_into('<H',image,p+26,newcluster)
fixture=BUILD/'relocated.bin';fixture.write_bytes(image);before=sha(fixture);run(fixture);run(fixture);assert sha(fixture)==before;fixture.unlink();passed('different FAT cluster placement')
fixture=BUILD/'fixture.bin';shutil.copyfile(STOCK,fixture)
dumps=BUILD/'dumps';dumps.mkdir(exist_ok=True)
for p in dumps.iterdir():p.unlink()
p=run(fixture,('--dump',dumps));dump=next(dumps.glob('modem-dump-stock-*.bin'))
assert sha(dump)==sha(STOCK) and 'DUMP_VERIFIED' in p.stdout
side=Path(str(dump)+'.sha256');assert side.read_text().startswith(sha(dump));passed('double-read dump and checksum')
before=sha(dump);run(fixture,('--dump',dumps),ok=False);assert sha(dump)==before;passed('existing dump is never overwritten')
run(fixture);run(fixture,('--restore-dump',dump));assert sha(fixture)==sha(STOCK);passed('restore STOCK dump over PATCHED')
run(fixture);patched_dump=BUILD/'patched-dump.bin'
if patched_dump.exists():patched_dump.unlink()
if Path(str(patched_dump)+'.sha256').exists():Path(str(patched_dump)+'.sha256').unlink()
run(fixture,('--dump',patched_dump));patched_sha=sha(fixture);run(fixture);run(fixture,('--restore-dump',patched_dump));assert sha(fixture)==patched_sha;passed('restore PATCHED dump over STOCK')
shutil.copyfile(STOCK,fixture)
with fixture.open('r+b') as f:f.seek(510);f.write(b'\0')
unknown=sha(fixture);run(fixture,ok=False);assert sha(fixture)==unknown
run(fixture,('--restore-dump',dump));assert sha(fixture)==sha(STOCK);passed('restore dump over UNKNOWN with corrupt FAT header')
for threshold in range(1,21):
    shutil.copyfile(STOCK,fixture)
    p=run(fixture,tool=FAULT,extra_env={'D325_TEST_FAIL_AFTER':str(threshold),'D325_TEST_DISABLE_ROLLBACK':'1'},ok=False)
    assert 'TEST_INTERRUPTED_NO_ROLLBACK' in p.stdout
    classification=run(fixture,())
    if 'STATE MIXED' in classification.stdout:break
else:raise AssertionError('No interrupted-write MIXED fixture was produced')
run(fixture,('--restore-dump',dump));assert sha(fixture)==sha(STOCK);passed('MIXED recovery after injected interruption without rollback',fail_after_sectors=threshold)
shutil.copyfile(STOCK,fixture);before=sha(fixture)
p=run(fixture,tool=FAULT,extra_env={'D325_TEST_FAIL_AFTER':'1'},ok=False);assert 'ROLLBACK_VERIFIED' in p.stdout and sha(fixture)==before;passed('write failure rollback uses fresh verification')
shutil.copyfile(STOCK,fixture)
p=run(fixture,tool=FAULT,extra_env={'D325_TEST_FAIL_FLUSH_AFTER':'3'},ok=False)
assert 'ROLLBACK_VERIFIED' in p.stdout and 'READBACK_VERIFIED' not in p.stdout and sha(fixture)==before;passed('post-write cache barrier error fails and rolls back')
p=run(fixture,tool=FAULT);text=p.stdout
phase=text.index('TEST_WRITE_PHASE WRITE');barrier=text.index('CACHE_FLUSHED',phase);verified=text.index('READBACK_VERIFIED');assert phase<barrier<verified;passed('cache barrier occurs after write and before READBACK_VERIFIED')
shutil.copyfile(STOCK,fixture);checksum=side.read_text();side.write_text('0'*64+'  '+dump.name+'\n')
run(fixture,('--restore-dump',dump),ok=False);assert sha(fixture)==sha(STOCK);side.write_text(checksum);passed('corrupt sidecar rejected without writes')
bad=BUILD/'bad-dump.bin';corrupt=bytearray(STOCK.read_bytes());image=bytearray(STOCK.read_bytes());_,off=entry('IMAGE/MODEM.B04');corrupt[off]^=1;bad.write_bytes(corrupt)
run(fixture,('--restore-dump',bad),ok=False);assert sha(fixture)==sha(STOCK);bad.unlink();passed('unsupported dump rejected without writes')
short=BUILD/'short.bin';short.write_bytes(b'not-a-modem');run(short,ok=False);assert short.read_bytes()==b'not-a-modem';short.unlink();passed('wrong size rejected')
if HOST:
    contract=BUILD/'cache-contract'
    subprocess.run([CC,*FLAGS,str(ROOT/'tests/cache-contract.c'),str(REC/'sha256.c'),'-o',str(contract)],check=True)
    subprocess.run([str(contract)],check=True);passed('block ioctl failure and aligned separate O_DIRECT contract (mock ioctl, regular fixture)')
fixture.unlink()
for p in dumps.iterdir():p.unlink()
patched_dump.unlink();Path(str(patched_dump)+'.sha256').unlink()
report={'platform':'host native' if HOST else 'ARM qemu','tests':results,'real_device_writes':0,'payload_sha256':sha(PAYLOAD)}
name='native-host-tests.json' if HOST else 'native-arm-tests.json'
(ROOT/'reports'/name).write_text(json.dumps(report,indent=2)+'\n');print('PASS',len(results),'fixture tests',flush=True)
