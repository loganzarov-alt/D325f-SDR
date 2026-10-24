#!/usr/bin/env python3
from pathlib import Path
import hashlib,json,os,shutil,struct,subprocess
ROOT=Path(__file__).resolve().parent.parent
REC=ROOT/'recovery';BUILD=ROOT/'build/recovery-tests';BUILD.mkdir(parents=True,exist_ok=True)
TOOL=REC/'tools/modem-toggle';PAYLOAD=REC/'patch.payload'
STOCK=Path(os.environ.get('STOCK_IMAGE','stock.bin'))
WORKING=Path(os.environ.get('WORKING_IMAGE','patched.bin'))
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
results=[]
def run(image,tool=TOOL,extra=(),env=None,ok=True):
    p=subprocess.run(['qemu-arm',str(tool),'--image',str(image),'--payload',str(PAYLOAD),'--toggle',*extra],
        capture_output=True,text=True,env=env,timeout=180)
    print(p.stdout+p.stderr,end='',flush=True)
    if ok and (p.returncode or 'READBACK_VERIFIED' not in p.stdout):raise RuntimeError(p.stdout+p.stderr)
    if not ok and not p.returncode:raise RuntimeError('Bad fixture was accepted')
    return p

for name,source in [('stock',STOCK),('working',WORKING)]:
    fixture=BUILD/(name+'.bin');shutil.copyfile(source,fixture);before=sha(fixture)
    first=run(fixture);between=sha(fixture);second=run(fixture)
    assert sha(fixture)==before and before!=between
    results.append(dict(test=name+' twice toggle',before=before,between=between,after=sha(fixture),passed=True))
    fixture.unlink()

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

# Same firmware on a different FAT layout. Payload offsets are file-relative.
p,oldoff=entry('IMAGE/MODEM.MDT');oldcluster=struct.unpack_from('<H',image,p+26)[0]
available=[c for c in range(2,(len(image)-data)//cluster+2) if struct.unpack_from('<H',image,fat+c*2)[0]==0]
newcluster=available[-1];newoff=data+(newcluster-2)*cluster
image[newoff:newoff+cluster]=image[oldoff:oldoff+cluster];image[oldoff:oldoff+cluster]=bytes(cluster)
for table in range(nfats):
    struct.pack_into('<H',image,fat+table*fatbytes+oldcluster*2,0)
    struct.pack_into('<H',image,fat+table*fatbytes+newcluster*2,0xffff)
struct.pack_into('<H',image,p+26,newcluster)
fixture=BUILD/'relocated.bin';fixture.write_bytes(image);before=sha(fixture)
run(fixture);run(fixture);assert sha(fixture)==before
results.append(dict(test='same firmware at different FAT cluster location',old_cluster=oldcluster,new_cluster=newcluster,passed=True));fixture.unlink()

inverse=json.loads((ROOT/'assets/d325-rx-42ec.xor.json').read_text())
image=bytearray(STOCK.read_bytes());first=next(iter(inverse['files']));_,off=entry(first)
for r in inverse['files'][first]['runs']:
    b=bytes.fromhex(r['xor']);at=off+r['offset']
    image[at:at+len(b)]=bytes(a^v for a,v in zip(image[at:at+len(b)],b))
fixture=BUILD/'mixed.bin';fixture.write_bytes(image);before=sha(fixture);run(fixture,ok=False);assert sha(fixture)==before
results.append(dict(test='mixed state rejected without writes',passed=True));fixture.unlink()
image=bytearray(STOCK.read_bytes());_,off=entry('IMAGE/MODEM.B04');image[off]^=1
fixture=BUILD/'unknown.bin';fixture.write_bytes(image);before=sha(fixture);run(fixture,ok=False);assert sha(fixture)==before
results.append(dict(test='unknown firmware rejected without writes',passed=True));fixture.unlink()
fixture=BUILD/'short.bin';fixture.write_bytes(b'not-a-modem');run(fixture,ok=False);assert fixture.read_bytes()==b'not-a-modem';fixture.unlink()
results.append(dict(test='wrong-sized image rejected without writes',passed=True))

testtool=BUILD/'modem-toggle-fault-injection'
subprocess.run([os.environ.get('ARM_CC',str(ROOT/'toolchain/musl-arm/bin/musl-gcc')),'-DD325_TESTING','-static','-O2','-march=armv7-a',str(REC/'modem-toggle.c'),str(REC/'sha256.c'),'-o',str(testtool)],check=True)
fixture=BUILD/'rollback.bin';shutil.copyfile(STOCK,fixture);before=sha(fixture)
environment=dict(os.environ,D325_TEST_FAIL_AFTER='1')
p=run(fixture,tool=testtool,env=environment,ok=False)
assert 'ROLLBACK_VERIFIED' in p.stdout and sha(fixture)==before
results.append(dict(test='injected write failure rolls back all changed sectors',passed=True));fixture.unlink()
(ROOT/'dist/recovery-tests.json').write_text(json.dumps({'arm_qemu_fixture_tests':results,'actual_twrp_flash_test':False},ensure_ascii=False,indent=2)+'\n')
print('PASS',len(results),'recovery engine tests; no real block device written',flush=True)
