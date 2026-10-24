#!/usr/bin/env python3
from pathlib import Path
import os,shutil,subprocess,zipfile
ROOT=Path(__file__).resolve().parent.parent
BUILD=ROOT/'build/tests-native';BUILD.mkdir(parents=True,exist_ok=True)
SDK=Path(os.environ.get('ANDROID_JAR','/usr/lib/android-sdk/platforms/android-34/android.jar'))
R8=Path(os.environ.get('R8_JAR','/usr/share/java/r8.jar'))
KEY=Path(os.environ.get('LG_PATCHER_KEYSTORE',str(ROOT/'build/local-signing.keystore')))
def run(*args):subprocess.run(list(map(str,args)),check=True)
classes=BUILD/'classes'
if classes.exists():shutil.rmtree(classes)
classes.mkdir()
run('javac','--release','8','-classpath',str(SDK)+os.pathsep+str(ROOT/'build/app/classes'),'-d',classes,ROOT/'tests/CacheRegression.java')
dex=BUILD/'dex';dex.mkdir(exist_ok=True)
run('java','-cp',R8,'com.android.tools.r8.D8','--min-api','25','--lib',SDK,'--classpath',ROOT/'build/app/classes','--output',dex,*classes.rglob('*.class'))
resource=BUILD/'resources.ap_';run('aapt','package','-f','-M',ROOT/'tests/AndroidManifest.xml','-I',SDK,'-F',resource)
unsigned=BUILD/'test-unsigned.apk'
with zipfile.ZipFile(resource) as z,zipfile.ZipFile(unsigned,'w',compression=zipfile.ZIP_DEFLATED) as out:
    for name in z.namelist():out.writestr(name,z.read(name))
    out.writestr('classes.dex',(dex/'classes.dex').read_bytes())
aligned=BUILD/'test-aligned.apk';run('zipalign','-f','4',unsigned,aligned)
run('apksigner','sign','--ks',KEY,'--ks-pass','pass:android','--out',ROOT/'dist/modem-patcher-regression-tests.apk',aligned)
print('Native fixture test APK built; actual modem is read only.')
