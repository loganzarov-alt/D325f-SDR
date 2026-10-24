#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Logan Zarov
"""Static release checks; accesses only files in this source tree."""
from pathlib import Path
import hashlib,json,re,subprocess,zipfile
ROOT=Path(__file__).resolve().parent.parent
EXPECTED='10fc481c411f22153fb570e970b0eecb652f2360ca9d7a79df56a57c377e2ba8'
assert hashlib.sha256((ROOT/'recovery/patch.payload').read_bytes()).hexdigest()==EXPECTED
spec=json.loads((ROOT/'assets/d325-rx-42ec.xor.json').read_text())
assert len(spec['files'])==6 and sum(len(bytes.fromhex(r['xor'])) for f in spec['files'].values() for r in f['runs'])==2672
for p in ROOT.rglob('*'):
    if not p.is_file() or p.relative_to(ROOT).parts[0] in {'build','dist','toolchain','third_party','licenses','reports'}:continue
    if p.suffix in {'.c','.h','.java','.py','.sh'}:
        text=p.read_text();assert 'SPDX-License-Identifier: GPL-3.0-or-later' in text and 'Copyright (C) 2026 Logan Zarov' in text,p
        # Runtime code and test runners must not consult sensors or enforce limits.
        if p.name!='check-release.py':assert not re.search(r'temperature|thermal|CPU_C|thermal_zone',text,re.I),p
        if p.suffix in {'.c','.h','.java','.sh'}:assert not re.search('[\u0400-\u04ff]',text),p
assert 'GNU GENERAL PUBLIC LICENSE' in (ROOT/'LICENSE').read_text()
assert (ROOT/'README.md').is_file() and (ROOT/'README.ru.md').is_file()
apk=ROOT/'dist/Modem-Patcher-LG-D325-v1.1.0.apk'
certificate=subprocess.check_output(['apksigner','verify','--print-certs',str(apk)],text=True)
assert 'certificate DN:' in certificate and 'lgiqlocalbuild' not in re.sub('[^a-z0-9]','',certificate.lower())
manifest=subprocess.check_output(['aapt','dump','xmltree',str(apk),'AndroidManifest.xml'],text=True)
assert re.search(r'android:icon.*@0x0?1080042\b',manifest) and '1.1.0' in manifest
with zipfile.ZipFile(apk) as z:
    assert z.read('assets/patch.payload')==(ROOT/'recovery/patch.payload').read_bytes()
    assert z.read('assets/LICENSE')==(ROOT/'LICENSE').read_bytes()
    dex=z.read('classes.dex')
    for text in ['Save dump','Restore from dump','Licenses']:assert text.encode() in dex,text
    for name in z.namelist():assert not name.endswith(('.bin','.img','.keystore','.key','.pass')),name
    assert not re.search(b'D325_TEST_FAIL|CPU_C|temperature|thermal_zone',z.read('assets/modem-toggle'))
for label in ['Toggle','Restore-Dump']:
    with zipfile.ZipFile(ROOT/('dist/LG-D325-Modem-'+label+'-TWRP-v1.2.zip')) as z:
        assert z.read('patch.payload')==(ROOT/'recovery/patch.payload').read_bytes()
        assert z.read('LICENSE')==(ROOT/'LICENSE').read_bytes()
        assert 'licenses/PROJECT-NOTICE.txt' in z.namelist()
        assert not re.search('[\u0400-\u04ff]',z.read('META-INF/com/google/android/update-binary').decode())
report={'passed':True,'payload_format':'D325PT03','payload_sha256':EXPECTED,'xor_bytes':2672,'files':6,'original_icon_restored':True,'license_headers_verified':True,'public_signing_identity_verified':True,'full_images_or_private_keys_in_artifacts':False,'phone_writes':0}
(ROOT/'reports/release-checks.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
