#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Logan Zarov
"""Build the public APK using an explicitly supplied dedicated release key."""
from pathlib import Path
import hashlib,json,os,re,shutil,subprocess,zipfile
ROOT=Path(__file__).resolve().parent
BUILD=ROOT/'build/app';DIST=ROOT/'dist';BUILD.mkdir(parents=True,exist_ok=True);DIST.mkdir(exist_ok=True)
SDK=Path(os.environ.get('ANDROID_JAR','/usr/lib/android-sdk/platforms/android-34/android.jar'))
R8=Path(os.environ.get('R8_JAR','/usr/share/java/r8.jar'))
if not os.environ.get('LG_PATCHER_KEYSTORE'):raise SystemExit('Set LG_PATCHER_KEYSTORE to your dedicated release keystore; no debug-key fallback is allowed.')
KEY=Path(os.environ['LG_PATCHER_KEYSTORE'])
if not KEY.is_file():raise SystemExit('LG_PATCHER_KEYSTORE does not exist')
if os.environ.get('LG_PATCHER_KS_PASS_FILE'):password='file:'+os.environ['LG_PATCHER_KS_PASS_FILE']
elif os.environ.get('LG_PATCHER_KS_PASS'):password='env:LG_PATCHER_KS_PASS'
else:raise SystemExit('Set LG_PATCHER_KS_PASS_FILE or LG_PATCHER_KS_PASS')
def run(*args):subprocess.run(list(map(str,args)),check=True,cwd=ROOT)
payload=ROOT/'recovery/patch.payload'
if hashlib.sha256(payload.read_bytes()).hexdigest()!='10fc481c411f22153fb570e970b0eecb652f2360ca9d7a79df56a57c377e2ba8':raise SystemExit('The D325PT03 payload changed')
classes=BUILD/'classes'
if classes.exists():shutil.rmtree(classes)
classes.mkdir()
run('javac','--release','8','-Xlint:-options','-classpath',SDK,'-d',classes,*(ROOT/'src').rglob('*.java'))
dex=BUILD/'dex';dex.mkdir(exist_ok=True)
run('java','-cp',R8,'com.android.tools.r8.D8','--min-api','25','--lib',SDK,'--output',dex,*classes.rglob('*.class'))
resource=BUILD/'resources.ap_';run('aapt','package','-f','-M',ROOT/'AndroidManifest.xml','-I',SDK,'-F',resource)
unsigned=BUILD/'unsigned.apk'
with zipfile.ZipFile(resource) as z,zipfile.ZipFile(unsigned,'w',compression=zipfile.ZIP_DEFLATED) as out:
    for name in z.namelist():out.writestr(name,z.read(name))
    out.writestr('classes.dex',(dex/'classes.dex').read_bytes())
    for source,name in [(ROOT/'recovery/tools/modem-toggle','modem-toggle'),(payload,'patch.payload'),(ROOT/'recovery/scan-partition.sh','scan-partition.sh'),(ROOT/'LICENSE','LICENSE')]:
        out.writestr('assets/'+name,source.read_bytes())
    for source in (ROOT/'licenses').glob('*'):out.writestr('assets/licenses/'+source.name,source.read_bytes())
aligned=BUILD/'aligned.apk';run('zipalign','-f','4',unsigned,aligned)
target=DIST/'Modem-Patcher-LG-D325-v1.1.0.apk'
args=['apksigner','sign','--ks',str(KEY),'--ks-pass',password]
if os.environ.get('LG_PATCHER_KEY_ALIAS'):args+=['--ks-key-alias',os.environ['LG_PATCHER_KEY_ALIAS']]
run(*args,'--out',target,aligned)
certificate=subprocess.check_output(['apksigner','verify','--print-certs',str(target)],text=True)
normalized=re.sub('[^a-z0-9]','',certificate.lower())
if 'lgiqlocalbuild' in normalized or 'lgd325localbuild' in normalized or 'androiddebug' in normalized:raise SystemExit('A dedicated public release signing identity is required')
print(certificate)
report={'apk':target.name,'sha256':hashlib.sha256(target.read_bytes()).hexdigest(),'version':'1.1.0',
    'release_certificate':certificate.strip().splitlines(),'payload_sha256':hashlib.sha256(payload.read_bytes()).hexdigest(),
    'dump_and_restore':True,'fresh_direct_block_readback':True,'project_license':'GPL-3.0-or-later',
    'launcher_icon':'@android:drawable/ic_menu_manage (original icon)',
    'device_installation':'not run; user confirmation required','real_modem_writes':0}
(ROOT/'reports').mkdir(exist_ok=True);(ROOT/'reports/build-report.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
