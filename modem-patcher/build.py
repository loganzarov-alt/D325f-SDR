#!/usr/bin/env python3
"""Build the application from source; no original APK or firmware is needed."""
from pathlib import Path
import hashlib,json,os,shutil,subprocess,zipfile
ROOT=Path(__file__).resolve().parent
BUILD=ROOT/'build/app';DIST=ROOT/'dist';BUILD.mkdir(parents=True,exist_ok=True);DIST.mkdir(exist_ok=True)
SDK=Path(os.environ.get('ANDROID_JAR','/usr/lib/android-sdk/platforms/android-34/android.jar'))
R8=Path(os.environ.get('R8_JAR','/usr/share/java/r8.jar'))
KEY=Path(os.environ.get('LG_PATCHER_KEYSTORE',str(ROOT/'build/local-signing.keystore')))
def run(*args):subprocess.run(list(map(str,args)),check=True,cwd=ROOT)
if not KEY.exists():
    KEY.parent.mkdir(parents=True,exist_ok=True)
    run('keytool','-genkeypair','-keystore',KEY,'-storepass','android','-keypass','android','-alias','local','-keyalg','RSA','-keysize','2048','-validity','10000','-dname','CN=LG D325 Local Build')
classes=BUILD/'classes'
if classes.exists():shutil.rmtree(classes)
classes.mkdir()
java=[p for p in (ROOT/'src').rglob('*.java') if p.name!='ReversiblePatch.java']
run('javac','--release','8','-classpath',SDK,'-d',classes,*java)
dex=BUILD/'dex';dex.mkdir(exist_ok=True)
run('java','-cp',R8,'com.android.tools.r8.D8','--min-api','25','--lib',SDK,'--output',dex,*classes.rglob('*.class'))
resource=BUILD/'resources.ap_'
run('aapt','package','-f','-M',ROOT/'AndroidManifest.xml','-I',SDK,'-F',resource)
unsigned=BUILD/'unsigned.apk'
with zipfile.ZipFile(resource) as z,zipfile.ZipFile(unsigned,'w',compression=zipfile.ZIP_DEFLATED) as out:
    for name in z.namelist():out.writestr(name,z.read(name))
    out.writestr('classes.dex',(dex/'classes.dex').read_bytes())
    for source,name in [(ROOT/'recovery/tools/modem-toggle','modem-toggle'),(ROOT/'recovery/patch.payload','patch.payload'),(ROOT/'recovery/scan-partition.sh','scan-partition.sh')]:
        out.writestr('assets/'+name,source.read_bytes())
    for source in (ROOT/'licenses').glob('*'):out.writestr('assets/licenses/'+source.name,source.read_bytes())
aligned=BUILD/'aligned.apk';run('zipalign','-f','4',unsigned,aligned)
target=DIST/'Modem-Patcher-LG-D325-v1.0.4.apk'
run('apksigner','sign','--ks',KEY,'--ks-pass','pass:android','--out',target,aligned)
run('apksigner','verify','--print-certs',target)
report={'apk':str(target),'sha256':hashlib.sha256(target.read_bytes()).hexdigest(),
    'version':'1.0.4','source_complete':True,'java_firmware_buffers':False,
    'root_cache_labels':'files are created by app UID before root writes',
    'payload':'XOR masks, offsets and file SHA256 values; no full firmware image',
    'inverse_without_backup':True,'serial_or_block_index_binding':False,
    'real_modem_write_test':False}
(DIST/'build-report.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps(report,indent=2))
