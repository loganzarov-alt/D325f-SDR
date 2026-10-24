#!/usr/bin/env python3
from pathlib import Path
import hashlib,json,os,subprocess,zipfile
ROOT=Path(__file__).resolve().parent.parent
REC=ROOT/'recovery';DIST=ROOT/'dist';DIST.mkdir(exist_ok=True)
(REC/'tools').mkdir(exist_ok=True)
compiler=os.environ.get('ARM_CC',str(ROOT/'toolchain/musl-arm/bin/musl-gcc'))
subprocess.run(['python3',str(REC/'build-payload.py')],check=True)
subprocess.run([compiler,'-static','-Os','-Wall','-Wextra','-Wno-misleading-indentation','-march=armv7-a',
    str(REC/'modem-toggle.c'),str(REC/'sha256.c'),'-Wl,-Map,'+str(DIST/'native-link.map'),'-o',str(REC/'tools/modem-toggle')],check=True)
subprocess.run(['qemu-arm',str(REC/'tools/modem-toggle'),'--self-test'],check=True)
subprocess.run(['sh','-n',str(REC/'update-binary')],check=True)
target=DIST/'LG-D325-Modem-Toggle-TWRP-v1.1.zip'
with zipfile.ZipFile(target,'w',compression=zipfile.ZIP_DEFLATED) as z:
    for name,source,mode in [
        ('META-INF/com/google/android/update-binary',REC/'update-binary',0o755),
        ('tools/modem-toggle',REC/'tools/modem-toggle',0o755),
        ('patch.payload',REC/'patch.payload',0o644),
        ('scan-partition.sh',REC/'scan-partition.sh',0o644),
        *[('licenses/'+p.name,p,0o644) for p in (ROOT/'licenses').glob('*')]]:
        info=zipfile.ZipInfo(name);info.external_attr=(0o100000|mode)<<16;info.compress_type=zipfile.ZIP_DEFLATED
        z.writestr(info,source.read_bytes())
    z.writestr('META-INF/com/google/android/updater-script','# Installer logic is in update-binary.\n')
report={'zip':str(target),'sha256':hashlib.sha256(target.read_bytes()).hexdigest(),
    'architecture':'ARMv7 static musl; runtime notices included',
    'requires':'TWRP on LG D325 and supported firmware file hashes',
    'toggle':'STOCK -> PATCHED -> STOCK','external_backup_required':False,
    'partition_selection':'mounted firmware or exact modem label; 64 MiB required',
    'device_binding':'model and firmware contents only; no serial, IMEI or block index',
    'writes':'changed sectors of modem only; no modemst/persist/fsg/boot',
    'actual_twrp_flash_test':'not run: user will test in TWRP'}
(DIST/'twrp-build-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n')
print(json.dumps(report,ensure_ascii=False,indent=2))
