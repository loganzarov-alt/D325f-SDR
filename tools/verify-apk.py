#!/usr/bin/env python3
from pathlib import Path
import hashlib, json, sys, zipfile
root = Path(__file__).resolve().parents[1]
with zipfile.ZipFile(sys.argv[1]) as z:
    names = z.namelist()
    dex = b''.join(z.read(n) for n in names if n.endswith('.dex'))
    report = {}
    for name in ['lg_stream', 'lg_factory', 'lg_online', 'btcw-guard', 'sdr-focus']:
        data = (root / 'app/build/generated/native-assets' / name).read_bytes()
        assert z.read('assets/' + name) == data, name
        sha = hashlib.sha256(data).hexdigest()
        if name in ['lg_stream', 'lg_factory', 'lg_online']:
            assert sha.encode() in dex, 'Missing install hash for ' + name
        report[name] = sha
    assert 'assets/licenses/NOTICE.html' in names
    assert not any(n.endswith(('libqmi_cci.so', 'libqmiservices.so')) for n in names)
    assert b'Lcom/mantz_it/hackrf_android/' not in dex
    assert b'Lcom/mantz_it/rfanalyzer/source/HackrfSource;' not in dex
    assert b'Lcom/mantz_it/rfanalyzer/source/RtlsdrSource;' not in dex
    assert not any('rfanalyzer2' in n or n.endswith('/ic_launcher.png') for n in names)
    assert 'assets/lg_stream_b1' not in names
print(json.dumps(report, indent=2))
print('PASS: packaged helpers, embedded hashes, notices and removed hardware drivers')
