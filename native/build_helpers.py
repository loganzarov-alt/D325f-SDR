#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Logan Zarov
# Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
"""Build all project-owned native APK assets from C/ASM, never from a base ELF."""
from pathlib import Path
import os,subprocess,hashlib,json
ROOT=Path(__file__).resolve().parents[1]
NDK=Path(os.environ['ANDROID_NDK_HOME']);BIN=NDK/'toolchains/llvm/prebuilt/linux-x86_64/bin'
SRC=ROOT/'native/from-source';OUT=ROOT/'app/build/generated/native-assets';WORK=ROOT/'app/build/native-helpers'
OUT.mkdir(parents=True,exist_ok=True);WORK.mkdir(parents=True,exist_ok=True)
CLANG=str(BIN/'clang');CC=str(BIN/'armv7a-linux-androideabi25-clang')
def run(args):subprocess.run([str(x) for x in args],cwd=ROOT,check=True)
common=[CLANG,'--target=armv7a-linux-androideabi16','-march=armv7-a','-marm','-O2','-fno-builtin','-fomit-frame-pointer','-fPIE','-pie','-nostdlib','-fuse-ld=lld','-Wl,--dynamic-linker,/system/bin/linker','-Wl,-e,_start']
run(common+[SRC/'smem-app-stream.c',SRC/'copy-burst.S','-o',OUT/'lg_stream'])
# Link-only source-built interface stubs. They are NEVER packaged. Runtime resolves
# these symbols from the LG vendor libraries already installed on the phone.
for lib,names in [('qmi_cci',['qmi_client_init_instance','qmi_client_send_raw_msg_sync']),('qmiservices',['dms_get_service_object_internal_v01'])]:
 p=WORK/(lib+'.S');p.write_text('.syntax unified\n.arm\n.text\n'+''.join('.global '+s+'\n.type '+s+',%function\n'+s+':\n udf #0\n' for s in names))
 run([CLANG,'--target=armv7a-linux-androideabi16','-shared','-nostdlib','-fuse-ld=lld','-Wl,-soname,lib'+lib+'.so',p,'-o',WORK/('lib'+lib+'.so')])
for name,mode in [('lg_factory',2),('lg_online',0)]:
 run(common+['-DD325_SET_MODE='+str(mode),SRC/'d325-qmi-dms.c','-L'+str(WORK),'-lqmi_cci','-lqmiservices','-o',OUT/name])
for name,path in [('btcw-guard','tools/btcw/btcw.c'),('sdr-focus','tools/focus/focus.c')]:
 run([CC,'-O2','-fPIE','-pie',ROOT/path,'-o',OUT/name])
hashes={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(OUT.iterdir()) if p.name!='rxfix-build.json'}
report={'build_kind':'C/ASM from source, no base ELF','ndk_version':'27.2.12479018','assets_sha256':hashes,'reference_stream_sha256':'02ba7e628d8809d3439cebeb3b1bd618d432dcd2564f73be6b8d52b16c5a90cc','byte_identical_to_reference':hashes['lg_stream']=='02ba7e628d8809d3439cebeb3b1bd618d432dcd2564f73be6b8d52b16c5a90cc'}
(OUT/'rxfix-build.json').write_text(json.dumps(report,indent=2)+'\n')
p=ROOT/'app/build/generated/source/nativeHelpers/com/mantz_it/rfanalyzer/source/NativeHelperHashes.java';p.parent.mkdir(parents=True,exist_ok=True)
p.write_text('package com.mantz_it.rfanalyzer.source;\npublic final class NativeHelperHashes {\n'+''.join(' public static final String '+k+' = "'+hashes[v]+'";\n' for k,v in [('STREAM','lg_stream'),('FACTORY','lg_factory'),('ONLINE','lg_online')])+' private NativeHelperHashes() {}\n}\n')
print(json.dumps(report,indent=2))
