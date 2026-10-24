#!/usr/bin/env python3
"""Test native patch/restore guards on synthetic FAT files, without modem firmware."""
from pathlib import Path
import hashlib
import json
import os
import struct
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
SIZE = 64 * 1024 * 1024
CLUSTER = 16384
FAT = 512
FAT_BYTES = 8192
ROOT_DIR = FAT + 2 * FAT_BYTES
DATA = ROOT_DIR + 16384
FILES = [('IMAGE/MODEM.B01', b'A' * 1024), ('IMAGE/MODEM.B04', b'B' * 1024)]
pack = lambda n: struct.pack('<I', n)
digest = lambda b: hashlib.sha256(b).digest()

def cluster(c):
    return DATA + (c - 2) * CLUSTER

def fixture():
    image = bytearray(SIZE)
    struct.pack_into('<H', image, 11, 512)
    image[13] = 32
    struct.pack_into('<H', image, 14, 1)
    image[16] = 2
    struct.pack_into('<H', image, 17, 512)
    struct.pack_into('<H', image, 22, 16)
    image[510:512] = b'\x55\xaa'
    def entry(at, name, attr, c, size):
        image[at:at+11] = name
        image[at+11] = attr
        struct.pack_into('<H', image, at+26, c)
        struct.pack_into('<I', image, at+28, size)
    entry(ROOT_DIR, b'IMAGE      ', 16, 2, 0)
    for c in [2, 3, 4]:
        for table in [FAT, FAT+FAT_BYTES]:
            struct.pack_into('<H', image, table+c*2, 0xffff)
    payload = bytearray(b'D325PT03' + pack(len(FILES)))
    expected = bytearray(image)
    for i, (name, data) in enumerate(FILES):
        c = i + 3
        entry(cluster(2)+i*32, ('MODEM   B0' + str(1 if i == 0 else 4)).encode(), 0, c, len(data))
        image[cluster(c):cluster(c)+len(data)] = data
        mask = bytes([0x11+i])*7
        changed = data[:7] + bytes(a^b for a,b in zip(data[7:14],mask)) + data[14:]
        encoded = name.encode()
        payload += pack(len(encoded)) + encoded + pack(len(data)) + digest(data) + digest(changed)
        payload += pack(1) + pack(7) + pack(len(mask)) + mask
    expected = bytearray(image)
    for i in range(2):
        for k in range(7):
            expected[cluster(i+3)+7+k] ^= 0x11+i
    return bytes(image), bytes(expected), bytes(payload)

def main():
    results = []
    with tempfile.TemporaryDirectory(prefix='d325-patcher-synthetic-') as temporary:
        work = Path(temporary)
        tools = {}
        for name, flags in [('normal', []), ('fault', ['-DD325_TESTING'])]:
            tool = work/name
            subprocess.run([os.environ.get('CC','cc'), '-O2', '-Wall', '-Wextra',
                '-Wno-misleading-indentation', *flags, str(ROOT/'recovery/modem-toggle.c'),
                str(ROOT/'recovery/sha256.c'), '-o', str(tool)], check=True)
            tools[name] = tool
        subprocess.run([str(tools['normal']), '--self-test'], check=True)
        original, expected, payload_bytes = fixture()
        image = work/'fixture.img'
        payload = work/'patch.payload'
        image.write_bytes(original)
        payload.write_bytes(payload_bytes)
        def run(*args, failure=False, fault=False):
            assert image.is_file() and not image.is_symlink()
            env = dict(os.environ)
            if fault:
                env['D325_TEST_FAIL_AFTER'] = '1'
            result = subprocess.run([str(tools['fault' if fault else 'normal']),
                '--image', str(image), '--payload', str(payload), *args],
                capture_output=True, text=True, timeout=30, env=env)
            assert (result.returncode != 0) == failure, result.stdout+result.stderr
            return result.stdout+result.stderr
        def passed(name):
            results.append(name)
            print('PASS', name, flush=True)
        assert 'STATE STOCK' in run() and image.read_bytes() == original
        passed('read-only classification')
        assert 'DRY_RUN_NO_WRITES' in run('--apply','--dry-run') and image.read_bytes() == original
        passed('dry-run leaves image unchanged')
        assert 'READBACK_VERIFIED' in run('--apply') and image.read_bytes() == expected
        passed('apply writes exactly expected bytes')
        assert 'ALREADY_IN_REQUESTED_STATE' in run('--apply') and image.read_bytes() == expected
        passed('apply is idempotent')
        assert 'READBACK_VERIFIED' in run('--revert') and image.read_bytes() == original
        passed('restore recovers complete original image')
        run('--toggle'); run('--toggle')
        assert image.read_bytes() == original
        passed('two toggles are byte-exact')
        relocated = bytearray(original)
        relocated[cluster(7):cluster(7)+CLUSTER] = relocated[cluster(3):cluster(3)+CLUSTER]
        relocated[cluster(3):cluster(3)+CLUSTER] = bytes(CLUSTER)
        struct.pack_into('<H',relocated,cluster(2)+26,7)
        for table in [FAT,FAT+FAT_BYTES]:
            struct.pack_into('<H',relocated,table+3*2,0)
            struct.pack_into('<H',relocated,table+7*2,0xffff)
        image.write_bytes(relocated); run('--toggle'); run('--toggle')
        assert image.read_bytes() == relocated
        passed('file-relative patch accepts relocated FAT file')
        mixed = bytearray(original)
        mixed[cluster(3):cluster(3)+1024] = expected[cluster(3):cluster(3)+1024]
        image.write_bytes(mixed)
        assert 'mixed' in run('--toggle',failure=True).lower() and image.read_bytes() == mixed
        passed('mixed state rejected without writes')
        corrupt = bytearray(original); corrupt[cluster(4)] ^= 1
        image.write_bytes(corrupt); run('--toggle',failure=True)
        assert image.read_bytes() == corrupt
        passed('unknown file hash rejected without writes')
        image.write_bytes(original); payload.write_bytes(payload_bytes+b'bad')
        run('--toggle',failure=True); assert image.read_bytes() == original
        passed('malformed payload rejected without writes')
        payload.write_bytes(payload_bytes); image.write_bytes(b'wrong size')
        run('--toggle',failure=True); assert image.read_bytes() == b'wrong size'
        passed('wrong image size rejected without writes')
        fragmented = bytearray(original)
        for table in [FAT,FAT+FAT_BYTES]:
            struct.pack_into('<H',fragmented,table+3*2,5)
            struct.pack_into('<H',fragmented,table+5*2,0xffff)
        image.write_bytes(fragmented); run('--toggle',failure=True)
        assert image.read_bytes() == fragmented
        passed('fragmented FAT file rejected without writes')
        image.write_bytes(original)
        assert 'ROLLBACK_VERIFIED' in run('--toggle',failure=True,fault=True)
        assert image.read_bytes() == original
        passed('injected write failure restores original image')
    print(json.dumps({'passed':len(results),'tests':results,'test_data':'synthetic FAT files',
        'host_architecture':os.uname().machine,'real_device_access':False},indent=2))

if __name__ == '__main__':
    main()
