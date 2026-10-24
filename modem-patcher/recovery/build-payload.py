#!/usr/bin/env python3
"""Encode the distributable XOR spec. Neither stock nor patched firmware is needed."""
from pathlib import Path
import json,struct
ROOT=Path(__file__).resolve().parent.parent
spec=json.loads((ROOT/'assets/d325-rx-42ec.xor.json').read_text())
assert spec['format']=='d325-xor-patch/3'
blob=bytearray(b'D325PT03'+struct.pack('<I',len(spec['files'])))
for name,file in spec['files'].items():
    encoded=name.encode('ascii');blob+=struct.pack('<I',len(encoded))+encoded+struct.pack('<I',file['size'])
    blob+=bytes.fromhex(file['stock_sha256'])+bytes.fromhex(file['patched_sha256'])+struct.pack('<I',len(file['runs']))
    for run in file['runs']:
        mask=bytes.fromhex(run['xor']);blob+=struct.pack('<II',run['offset'],len(mask))+mask
(ROOT/'recovery/patch.payload').write_bytes(blob)
print('XOR payload:',len(blob),'bytes')
