#!/usr/bin/env python3
"""Package a whitelist of rebuildable sources, never personal firmware backups."""
from pathlib import Path
import hashlib,json,shutil,tarfile
ROOT=Path(__file__).resolve().parent
NAME='LG-D325-Modem-Patcher-sources-v1.0.4'
STAGE=ROOT/'build/public-source'/NAME
if STAGE.exists():shutil.rmtree(STAGE)
STAGE.mkdir(parents=True)
files=['AndroidManifest.xml','build.py','README.ru.txt','README.md','LICENSE','LICENSING.md','.gitignore','package-sources.py',
'assets/d325-rx-42ec.xor.json','third_party/musl-1.2.5.tar.gz',
'recovery/build-runtime.sh','recovery/build-payload.py','recovery/build-zip.py',
'recovery/modem-toggle.c','recovery/sha256.c','recovery/sha256.h',
'recovery/update-binary','recovery/scan-partition.sh','recovery/patch.payload',
'recovery/tools/modem-toggle','recovery/test-toggle.py',
'tests/CacheRegression.java','tests/AndroidManifest.xml','tests/build-tests.py','tests/run-device-tests.py','tests/host-synthetic.py']
files += [str(p.relative_to(ROOT)) for p in (ROOT/'src').rglob('*.java') if p.name!='ReversiblePatch.java']
files += [str(p.relative_to(ROOT)) for p in (ROOT/'licenses').glob('*')]
for name in files:
    source=ROOT/name;target=STAGE/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,target)
reports=STAGE/'reports';reports.mkdir()
for name in ['build-report.json','twrp-build-report.json','recovery-tests.json','recovery-tests.log','device-regression.txt','device-test-report.json','source-reproduction-report.json','publication-review.json']:
    source=ROOT/'dist'/name
    # Keep supplied reports when packaging an extracted source release.
    if not source.exists():source=ROOT/'reports'/name
    if not source.exists():raise RuntimeError('Missing verification report: '+name)
    if source.suffix=='.json':
        obj=json.loads(source.read_text())
        for key in ['apk','zip']:
            if key in obj:obj[key]=Path(obj[key]).name
        (reports/name).write_text(json.dumps(obj,ensure_ascii=False,indent=2)+'\n')
    else:shutil.copyfile(source,reports/name)
manifest={str(p.relative_to(STAGE)):hashlib.sha256(p.read_bytes()).hexdigest() for p in STAGE.rglob('*') if p.is_file()}
(STAGE/'SOURCE-MANIFEST.sha256').write_text(''.join(value+'  '+key+'\n' for key,value in sorted(manifest.items())))
target=ROOT/'dist'/(NAME+'.tar.gz')
target.parent.mkdir(parents=True,exist_ok=True)
with tarfile.open(target,'w:gz') as archive:archive.add(STAGE,arcname=NAME)
with tarfile.open(target) as archive:
    names=archive.getnames()
    assert not any(Path(name).suffix in ('.img','.bin','.apk','.keystore') for name in names)
    assert not any('inverse.json' in name or 'original/' in name or 'ReversiblePatch.java' in name for name in names)
print(json.dumps({'archive':str(target),'bytes':target.stat().st_size,'sha256':hashlib.sha256(target.read_bytes()).hexdigest(),'files':len(manifest)+1},indent=2))
