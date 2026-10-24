#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Logan Zarov
"""Package only project sources, release artifacts and verification reports."""
from pathlib import Path
import hashlib,json,shutil,tarfile,zipfile
ROOT=Path(__file__).resolve().parent
DIST=ROOT/'dist';NAME='LG-D325-Modem-Patcher-v1.1.0'
STAGE=ROOT/'build/source-package'/NAME
if STAGE.exists():shutil.rmtree(STAGE)
STAGE.mkdir(parents=True)
files=['LICENSE','README.md','README.ru.md','AndroidManifest.xml','.gitignore','build.py','package-sources.py']
for folder in ['src','tests','recovery','assets','licenses','third_party','reports']:
    for p in (ROOT/folder).rglob('*'):
        if p.is_file() and (p.suffix in {'.java','.py','.c','.h','.sh','.json','.txt','.md','.log'} or p.name in {'update-binary','patch.payload','modem-toggle','musl-1.2.5.tar.gz'}):
            files.append(str(p.relative_to(ROOT)))
for name in files:
    target=STAGE/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy(ROOT/name,target)
manifest={str(p.relative_to(STAGE)):hashlib.sha256(p.read_bytes()).hexdigest() for p in STAGE.rglob('*') if p.is_file()}
(STAGE/'SOURCE-MANIFEST.sha256').write_text(''.join(digest+'  '+name+'\n' for name,digest in sorted(manifest.items())))
source=DIST/(NAME+'-sources.tar.gz')
with tarfile.open(source,'w:gz') as tar:tar.add(STAGE,arcname=NAME)
with tarfile.open(source) as tar:
    for member in tar.getmembers():
        if not member.isfile():continue
        p=Path(member.name)
        if p.suffix.lower() in {'.bin','.img','.keystore','.jks','.p12','.pem','.key','.pass'}:raise RuntimeError('Forbidden source archive member: '+member.name)
        if 'boot.img' in p.name.lower():raise RuntimeError('Boot image must not be archived')
release=DIST/(NAME+'-release.zip')
artifacts=[DIST/'Modem-Patcher-LG-D325-v1.1.0.apk',DIST/'LG-D325-Modem-Toggle-TWRP-v1.2.zip',DIST/'LG-D325-Modem-Restore-Dump-TWRP-v1.2.zip',source]
sums=''.join(hashlib.sha256(p.read_bytes()).hexdigest()+'  '+p.name+'\n' for p in artifacts)
with zipfile.ZipFile(release,'w',compression=zipfile.ZIP_DEFLATED) as z:
    for p in artifacts:z.write(p,p.name)
    for name in ['README.md','README.ru.md','LICENSE']:z.write(ROOT/name,name)
    for p in (ROOT/'reports').glob('*'):
        if p.is_file():z.write(p,'reports/'+p.name)
    z.writestr('SHA256SUMS',sums)
report={'source':source.name,'source_sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'release':release.name,'release_sha256':hashlib.sha256(release.read_bytes()).hexdigest(),'source_files':len(manifest)+1,'full_firmware_images_included':False,'private_keys_included':False}
print(json.dumps(report,indent=2))
