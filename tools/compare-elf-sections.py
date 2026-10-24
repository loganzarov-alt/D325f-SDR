#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Logan Zarov
# Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
"""Byte-compare sections of two little-endian ELF32 binaries."""
from pathlib import Path
import argparse,struct,hashlib,json
p=argparse.ArgumentParser();p.add_argument('reference');p.add_argument('rebuilt');p.add_argument('-o','--output',default='elf-comparison.json');a=p.parse_args()
def sections(path):
 b=Path(path).read_bytes()
 if b[:6]!=b'\x7fELF\x01\x01':raise ValueError('Expected little-endian ELF32')
 h=struct.unpack_from('<16sHHIIIIIHHHHHH',b)
 ss=[struct.unpack_from('<10I',b,h[6]+i*h[11]) for i in range(h[12])]
 ns=ss[h[13]];names=b[ns[4]:ns[4]+ns[5]]
 return {names[s[0]:].split(b'\0')[0].decode():(s[2],b[s[4]:s[4]+s[5]]) for s in ss if s[1]!=8}
l,r=sections(a.reference),sections(a.rebuilt);rows=[]
for k in sorted(l.keys()|r.keys()):
 lv=l.get(k);rv=r.get(k);lb=lv[1] if lv else None;rb=rv[1] if rv else None
 rows.append(dict(section=k,identical=lb==rb,reference_flags=lv[0] if lv else None,rebuilt_flags=rv[0] if rv else None,reference_size=len(lb) if lb is not None else None,rebuilt_size=len(rb) if rb is not None else None,reference_sha256=hashlib.sha256(lb).hexdigest() if lb is not None else None,rebuilt_sha256=hashlib.sha256(rb).hexdigest() if rb is not None else None))
Path(a.output).write_text(json.dumps(rows,indent=2)+'\n')
print('Identical sections:',sum(x['identical'] for x in rows),'of',len(rows))
