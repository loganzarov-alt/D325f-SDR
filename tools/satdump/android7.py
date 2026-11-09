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

# The upstream unused AppCompat theme depended on the removed warning library.
p=root/'android/app/src/main/res/values/styles.xml'
s=p.read_text().replace('Theme.AppCompat.Light.DarkActionBar', 'android:style/Theme.Material.Light.DarkActionBar')
for name in ('colorPrimary', 'colorPrimaryDark', 'colorAccent'):
    s=s.replace('name="'+name+'"', 'name="android:'+name+'"')
p.write_text(s)

# Preserve corresponding dependency sources, including patched SQLite, before cleanup.
p=root/'android/deps/build.sh'
s=p.read_text().replace('# Build packages', 'mkdir -p dependency-sources\n# Build packages')
s=re.sub(r'^rm -rf (wolfssl|curl|cpu_features|volk|zlib|libpng|fftw3|nng|sqlite|zstd|libtiff|hdf5)$',
    lambda m: 'tar --exclude=.git -czf dependency-sources/'+m[1]+'.tar.gz '+m[1]+'\n'+m[0], s, flags=re.M)
p.write_text(s)

# Android 7 has no AAudio. Oboe's OpenSL ES backend supports API 16 and newer.
audio=root/'plugins/audio_sinks/aaudio_audio_sink'
for extension in ('h', 'cpp'):
    (audio/('aaudio_sink.'+extension)).write_text(Path(__file__).with_name('android7_audio.'+extension).read_text())
p=audio/'CMakeLists.txt'
s=p.read_text().replace('target_link_libraries(aaudio_audio_sink PUBLIC satdump_core aaudio)',
    'add_subdirectory(${CMAKE_SOURCE_DIR}/deps/oboe ${CMAKE_BINARY_DIR}/oboe)\n    target_link_libraries(aaudio_audio_sink PUBLIC satdump_core oboe)')
p.write_text(s)
