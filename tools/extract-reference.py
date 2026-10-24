#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Logan Zarov
# Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
"""Extract the owner's test reference. Never used by the application build."""
from pathlib import Path
import sys, zipfile, hashlib
expected = '02ba7e628d8809d3439cebeb3b1bd618d432dcd2564f73be6b8d52b16c5a90cc'
if len(sys.argv) != 2:
    raise SystemExit('Usage: extract-reference.py ORIGINAL.apk')
with zipfile.ZipFile(sys.argv[1]) as archive:
    data = archive.read('assets/lg_stream')
if hashlib.sha256(data).hexdigest() != expected:
    raise SystemExit('Reference hash mismatch; refusing to use a different helper')
out = Path(__file__).resolve().parents[1] / 'native/from-source/reference/lg_stream-rxfix3'
out.parent.mkdir(parents=True, exist_ok=True)
out.write_bytes(data)
print('Verified reference extracted (git-ignored):', out)
