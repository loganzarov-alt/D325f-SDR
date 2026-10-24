#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Logan Zarov
"""Native engine tests on synthetic FAT images; no modem firmware, no device.

Builds a 64 MiB FAT16 image with two dummy IMAGE/MODEM.* files and a matching
synthetic D325PT03 payload, then checks every guard of modem-toggle on it.
Runs in CI with only a host C compiler and Python 3.
"""
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
        image[at:at + 11] = name
        image[at + 11] = attr
        struct.pack_into('<H', image, at + 26, c)
        struct.pack_into('<I', image, at + 28, size)

    entry(ROOT_DIR, b'IMAGE      ', 16, 2, 0)
    for c in [2, 3, 4]:
        for table in [FAT, FAT + FAT_BYTES]:
            struct.pack_into('<H', image, table + c * 2, 0xffff)
    payload = bytearray(b'D325PT03' + pack(len(FILES)))
    for i, (name, data) in enumerate(FILES):
        c = i + 3
        entry(cluster(2) + i * 32, ('MODEM   B0' + str(1 if i == 0 else 4)).encode(), 0, c, len(data))
        image[cluster(c):cluster(c) + len(data)] = data
        mask = bytes([0x11 + i]) * 7
        changed = data[:7] + bytes(a ^ b for a, b in zip(data[7:14], mask)) + data[14:]
        encoded = name.encode()
        payload += pack(len(encoded)) + encoded + pack(len(data)) + digest(data) + digest(changed)
        payload += pack(1) + pack(7) + pack(len(mask)) + mask
    expected = bytearray(image)
    for i in range(2):
        for k in range(7):
            expected[cluster(i + 3) + 7 + k] ^= 0x11 + i
    return bytes(image), bytes(expected), bytes(payload)


def main():
    results = []
    cc = os.environ.get('CC', 'cc')
    flags = ['-std=c99', '-O2', '-Wall', '-Wextra', '-Werror']
    with tempfile.TemporaryDirectory(prefix='d325-patcher-synthetic-') as temporary:
        work = Path(temporary)
        tools = {}
        for name, extra in [('normal', []), ('fault', ['-DD325_TESTING'])]:
            tool = work / name
            subprocess.run([cc, *flags, *extra, str(ROOT / 'recovery/modem-toggle.c'),
                            str(ROOT / 'recovery/sha256.c'), '-o', str(tool)], check=True)
            tools[name] = tool
        subprocess.run([str(tools['normal']), '--self-test'], check=True)
        original, expected, payload_bytes = fixture()
        image = work / 'fixture.img'
        payload = work / 'patch.payload'
        dumps = work / 'dumps'
        image.write_bytes(original)
        payload.write_bytes(payload_bytes)

        def run(*args, failure=False, fault=None, target=None):
            env = dict(os.environ)
            env.update(fault or {})
            result = subprocess.run([str(tools['fault' if fault else 'normal']),
                                     '--image', str(target or image), '--payload', str(payload), *args],
                                    capture_output=True, text=True, timeout=60, env=env)
            assert (result.returncode != 0) == failure, result.stdout + result.stderr
            return result.stdout + result.stderr

        def passed(name):
            results.append(name)
            print('PASS', name, flush=True)

        out = run()
        assert 'STATE STOCK' in out and image.read_bytes() == original
        assert 'CACHE_FLUSHED POSIX_FADV_DONTNEED' in out
        passed('read-only classification with cache flush')
        assert 'DRY_RUN_NO_WRITES' in run('--apply', '--dry-run') and image.read_bytes() == original
        passed('dry-run leaves image unchanged')
        assert 'READBACK_VERIFIED' in run('--apply') and image.read_bytes() == expected
        passed('apply writes exactly expected bytes')
        assert 'ALREADY_IN_REQUESTED_STATE' in run('--apply') and image.read_bytes() == expected
        passed('apply is idempotent')
        assert 'READBACK_VERIFIED' in run('--revert') and image.read_bytes() == original
        passed('restore recovers complete original image')
        run('--toggle')
        run('--toggle')
        assert image.read_bytes() == original
        passed('two toggles are byte-exact')

        out = run('--dump', str(dumps) + '/')
        assert 'DUMP_VERIFIED' in out
        dump = next(dumps.glob('modem-dump-stock-*.bin'))
        side = dump.with_name(dump.name + '.sha256')
        assert dump.read_bytes() == original and side.read_text().split()[0] == hashlib.sha256(original).hexdigest()
        passed('dump is verified and has a checksum file')
        assert 'nothing overwritten' in run('--dump', str(dumps) + '/', failure=True)
        assert dump.read_bytes() == original
        passed('existing dump is never overwritten')

        interrupted = {'D325_TEST_FAIL_AFTER': '1', 'D325_TEST_DISABLE_ROLLBACK': '1'}
        run('--toggle', failure=True, fault=interrupted)
        broken = image.read_bytes()
        assert broken not in (original, expected)
        assert 'restore-dump' in run('--toggle', failure=True).lower() and image.read_bytes() == broken
        passed('interrupted write is detected and toggling refused')
        assert 'READBACK_VERIFIED' in run('--restore-dump', str(dump)) and image.read_bytes() == original
        passed('restore from dump repairs an interrupted write')

        bad = work / 'bad-dump.bin'
        bad.write_bytes(original)
        Path(str(bad) + '.sha256').write_text('0' * 64 + '  bad-dump.bin\n')
        image.write_bytes(expected)
        assert 'checksum' in run('--restore-dump', str(bad), failure=True).lower()
        assert image.read_bytes() == expected
        passed('dump with a wrong checksum file is refused')
        foreign = work / 'foreign-dump.bin'
        foreign.write_bytes(bytes(SIZE))
        run('--restore-dump', str(foreign), failure=True)
        assert image.read_bytes() == expected
        passed('unsupported dump is refused')

        relocated = bytearray(original)
        relocated[cluster(7):cluster(7) + CLUSTER] = relocated[cluster(3):cluster(3) + CLUSTER]
        relocated[cluster(3):cluster(3) + CLUSTER] = bytes(CLUSTER)
        struct.pack_into('<H', relocated, cluster(2) + 26, 7)
        for table in [FAT, FAT + FAT_BYTES]:
            struct.pack_into('<H', relocated, table + 3 * 2, 0)
            struct.pack_into('<H', relocated, table + 7 * 2, 0xffff)
        image.write_bytes(relocated)
        run('--toggle')
        run('--toggle')
        assert image.read_bytes() == relocated
        passed('file-relative patch accepts relocated FAT file')
        mixed = bytearray(original)
        mixed[cluster(3):cluster(3) + 1024] = expected[cluster(3):cluster(3) + 1024]
        image.write_bytes(mixed)
        assert 'mixed' in run('--toggle', failure=True).lower() and image.read_bytes() == mixed
        passed('mixed state rejected without writes')
        corrupt = bytearray(original)
        corrupt[cluster(4)] ^= 1
        image.write_bytes(corrupt)
        run('--toggle', failure=True)
        assert image.read_bytes() == corrupt
        passed('unknown file hash rejected without writes')
        image.write_bytes(original)
        payload.write_bytes(payload_bytes + b'bad')
        run('--toggle', failure=True)
        assert image.read_bytes() == original
        passed('malformed payload rejected without writes')
        payload.write_bytes(payload_bytes)
        image.write_bytes(b'wrong size')
        run('--toggle', failure=True)
        assert image.read_bytes() == b'wrong size'
        passed('wrong image size rejected without writes')
        fragmented = bytearray(original)
        for table in [FAT, FAT + FAT_BYTES]:
            struct.pack_into('<H', fragmented, table + 3 * 2, 5)
            struct.pack_into('<H', fragmented, table + 5 * 2, 0xffff)
        image.write_bytes(fragmented)
        run('--toggle', failure=True)
        assert image.read_bytes() == fragmented
        passed('fragmented FAT file rejected without writes')
        image.write_bytes(original)
        assert 'ROLLBACK_VERIFIED' in run('--toggle', failure=True, fault={'D325_TEST_FAIL_AFTER': '1'})
        assert image.read_bytes() == original
        passed('injected write failure restores original image')
    print(json.dumps({'passed': len(results), 'tests': results, 'test_data': 'synthetic FAT files',
                      'host_architecture': os.uname().machine, 'real_device_access': False}, indent=2))


if __name__ == '__main__':
    main()
