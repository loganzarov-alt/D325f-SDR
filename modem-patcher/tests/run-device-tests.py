#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Logan Zarov
"""Optional app-UID tests; modem access is read-only, private fixtures are written."""
from pathlib import Path
import argparse,json,os,subprocess,time
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--confirm-device-test',action='store_true',help='permission to run the installed fixture instrumentation and write app-private test files')
if not parser.parse_args().confirm_device_test:raise SystemExit('Device tests require explicit confirmation. No phone access performed.')
ROOT=Path(__file__).resolve().parent.parent
serial=os.environ.get('ANDROID_SERIAL');adb=['adb']+(['-s',serial] if serial else [])
pkg='io.github.loganzarov_alt.d325fmodem';start=time.monotonic();output=ROOT/'reports/device-regression.txt'
with output.open('w') as log:
    p=subprocess.Popen(adb+['shell','am','instrument','-w','-r',pkg+'.tests/'+pkg+'.CacheRegression'],stdout=log,stderr=subprocess.STDOUT,text=True)
    try:p.wait(timeout=300)
    except subprocess.TimeoutExpired:p.terminate();p.wait(timeout=10)
text=output.read_text();passed='REAL_MODEM_WRITES 0' in text and 'INSTRUMENTATION_CODE: -1' in text and '\nFAIL' not in text
report={'passed':passed,'real_modem_writes':0,'duration_seconds':round(time.monotonic()-start,1)}
(ROOT/'reports/device-test-report.json').write_text(json.dumps(report,indent=2)+'\n');print(text)
if not passed:raise SystemExit(1)
