#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Logan Zarov
from pathlib import Path
import hashlib,json,os,subprocess,zipfile
ROOT=Path(__file__).resolve().parent.parent
REC=ROOT/'recovery';DIST=ROOT/'dist';DIST.mkdir(exist_ok=True)
(REC/'tools').mkdir(exist_ok=True)
compiler=os.environ.get('ARM_CC',str(ROOT/'toolchain/musl-arm/bin/musl-gcc'))
subprocess.run(['python3',str(REC/'build-payload.py')],check=True)
subprocess.run([compiler,'-static','-Os','-Wall','-Wextra','-Werror','-march=armv7-a',
    str(REC/'modem-toggle.c'),str(REC/'sha256.c'),'-Wl,-Map,'+str(DIST/'native-link.map'),'-o',str(REC/'tools/modem-toggle')],check=True)
subprocess.run(['qemu-arm',str(REC/'tools/modem-toggle'),'--self-test'],check=True)
subprocess.run(['sh','-n',str(REC/'update-binary')],check=True)
artifacts=[]
for label,operation in [('Toggle','toggle'),('Restore-Dump','restore-dump')]:
    target=DIST/('LG-D325-Modem-'+label+'-TWRP-v1.2.zip')
    with zipfile.ZipFile(target,'w',compression=zipfile.ZIP_DEFLATED) as z:
        for name,source,mode in [
            ('META-INF/com/google/android/update-binary',REC/'update-binary',0o755),
            ('tools/modem-toggle',REC/'tools/modem-toggle',0o755),
            ('patch.payload',REC/'patch.payload',0o644),
            ('scan-partition.sh',REC/'scan-partition.sh',0o644),
            ('LICENSE',ROOT/'LICENSE',0o644),
            *[('licenses/'+p.name,p,0o644) for p in (ROOT/'licenses').glob('*')]]:
            info=zipfile.ZipInfo(name);info.external_attr=(0o100000|mode)<<16;info.compress_type=zipfile.ZIP_DEFLATED
            z.writestr(info,source.read_bytes())
        z.writestr('operation',operation+'\n')
        z.writestr('META-INF/com/google/android/updater-script','# SPDX-License-Identifier: GPL-3.0-or-later\n# Copyright (C) 2026 Logan Zarov\n# Installer logic is in update-binary.\n')
    artifacts.append({'zip':target.name,'sha256':hashlib.sha256(target.read_bytes()).hexdigest(),'operation':operation})
report={'artifacts':artifacts,'payload_sha256':hashlib.sha256((REC/'patch.payload').read_bytes()).hexdigest(),
    'architecture':'ARMv7 static musl; notices included','direct_readback':True,
    'requires':'TWRP on LG D325 and supported firmware hashes',
    'automatic_dump_before_toggle':True,'restore_mixed_unknown_from_dump':True,
    'device_binding':'model and firmware contents only; no serial, IMEI or block index',
    'actual_device_test':'not run; confirmation required before phone writes'}
(ROOT/'reports').mkdir(exist_ok=True)
(ROOT/'reports/twrp-build-report.json').write_text(json.dumps(report,indent=2)+'\n')
print(json.dumps(report,indent=2))
