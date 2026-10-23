#!/usr/bin/env python3
"""Compare every uncompressed APK entry byte-for-byte, including missing files."""
import argparse, hashlib, json, zipfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('reference');p.add_argument('rebuilt');p.add_argument('-o','--output',default='apk-comparison.json');a=p.parse_args()
def digest(b):return hashlib.sha256(b).hexdigest()
with zipfile.ZipFile(a.reference) as left, zipfile.ZipFile(a.rebuilt) as right:
 l=set(left.namelist());r=set(right.namelist());rows=[]
 for name in sorted(l|r):
  lb=left.read(name) if name in l else None;rb=right.read(name) if name in r else None
  status='identical' if lb==rb else 'changed' if lb is not None and rb is not None else 'missing' if rb is None else 'added'
  rows.append(dict(path=name,status=status,reference_size=None if lb is None else len(lb),rebuilt_size=None if rb is None else len(rb),reference_sha256=None if lb is None else digest(lb),rebuilt_sha256=None if rb is None else digest(rb)))
result=dict(reference_sha256=digest(Path(a.reference).read_bytes()),rebuilt_sha256=digest(Path(a.rebuilt).read_bytes()),counts={s:sum(x['status']==s for x in rows) for s in ['identical','changed','missing','added']},files=rows)
Path(a.output).write_text(json.dumps(result,indent=2)+'\n');print(json.dumps(result['counts']))
