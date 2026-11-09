#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Logan Zarov
"""Apply the Android 7 / ARMv7 downstream configuration to pinned SatDump sources."""
from pathlib import Path
import re, sys
root=Path(sys.argv[1])
p=root/'android/app/build.gradle'
s=p.read_text(); assert 'minSdkVersion 26' in s
s=s.replace('minSdkVersion 26','minSdkVersion 24')
s=s.replace('applicationId "org.satdump.SatDump"','applicationId "io.github.loganzarov_alt.satdump.android7"')
s=s.replace('        targetSdkVersion 27','        ndk { abiFilters "armeabi-v7a" }\n        targetSdkVersion 27')
s=s.replace('            minifyEnabled false','            signingConfig signingConfigs.debug\n            minifyEnabled false')
s=s.replace('    implementation \'com.github.woheller69:FreeDroidWarn:V1.+\'\n','')
p.write_text(s)
p=root/'android/app/src/main/java/MainActivity.kt';s=p.read_text();s=s.replace('import org.woheller69.freeDroidWarn.FreeDroidWarn;','');s=s.replace('        FreeDroidWarn.showWarningOnUpgrade(this, BuildConfig.VERSION_CODE);','');p.write_text(s)
p=root/'android/app/src/main/AndroidManifest.xml';s=p.read_text().replace('android:label="SatDump"','android:label="SatDump Android 7"').replace('@mipmap/ic_launcher_round','@mipmap/ic_launcher_foreground');p.write_text(s)
p=root/'android/deps/build.sh';s=p.read_text();s='#!/bin/sh\nset -eu\n'+s;s=re.sub(r'^build_\w+ (?:arm64-v8a|x86|x86_64)\s*$', '',s,flags=re.M);s=s.replace('http://www.fftw.org/','https://www.fftw.org/');s=s.replace('make -j`nproc`','make -j2');p.write_text(s)
p=root/'android/gradle.properties';s=p.read_text().replace('-Xmx8g','-Xmx3g');p.write_text(s+'\norg.gradle.workers.max=2\n')
print('Applied Android 7 API24 + ARMv7 configuration. Native compilation and device testing still required.')

# SQLite's configure probe must compile, not execute an ARM binary on the x86 CI host.
probe = root/'android/deps/android7-sqlite.py'
probe.write_text(Path(__file__).with_name('sqlite_probe.py').read_text())
p=root/'android/deps/build.sh'
s=p.read_text().replace('build_sqlite armeabi-v7a', 'git -C sqlite fetch --depth 1 origin 0693caeea623ebfe21556e1035800687eeac7eaa\ngit -C sqlite checkout 0693caeea623ebfe21556e1035800687eeac7eaa\npython3 android7-sqlite.py\nbuild_sqlite armeabi-v7a')
p.write_text(s)
