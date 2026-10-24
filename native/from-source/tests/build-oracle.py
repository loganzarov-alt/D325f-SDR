#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Logan Zarov
# Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
from pathlib import Path
import re
import os
import subprocess
root=Path(__file__).resolve().parent.parent
source=(root/'smem-app-stream.c').read_text()
names=['transaction','frequency_control','ftm_word','wait_pll','apply_gain_controls']
for name in names:
    source=re.sub(r'\b'+name+r'\(',name+'_hook(',source)
    source=source.replace('static int '+name+'_hook(','static int '+name+'(')
source=source.replace('#include "transport/pll-probe.c"','#include "../transport/pll-probe.c"')
(root/'build').mkdir(exist_ok=True)
(root/'build/oracle-source.c').write_text(source)
subprocess.run([str(Path(os.environ['ANDROID_NDK_HOME'])/'toolchains/llvm/prebuilt/linux-x86_64/bin/armv7a-linux-androideabi25-clang'),'-static','-O2','-fno-builtin',
    '-fomit-frame-pointer','-fno-emulated-tls','-march=armv7-a','-marm',str(root/'tests/oracle.c'),
    str(root/'copy-burst.S'),'-o',str(root/'build/oracle')],check=True)
