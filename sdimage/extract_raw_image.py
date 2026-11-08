#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Logan Zarov
# Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
"""Extract an ordinary sparse .img from a verified D325 .d325img archive."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import zipfile


READ_CHUNK = 4 * 1024 * 1024
SPARSE_BLOCK = 4096


def extract(archive_path: Path, output_path: Path) -> dict:
    with zipfile.ZipFile(archive_path) as archive:
        if archive.namelist() != ["manifest.json", "image.img.gz"]:
            raise ValueError("unexpected D325 image archive layout")
        manifest = json.loads(archive.read("manifest.json"))
        output_path.parent.mkdir(parents=True, exist_ok=True)
        digest = hashlib.sha256()
        logical_position = 0
        allocated_payload = 0
        fd = os.open(output_path, os.O_RDWR | os.O_CREAT | os.O_TRUNC, 0o644)
        try:
            os.ftruncate(fd, manifest["rawSize"])
            with archive.open("image.img.gz") as compressed, gzip.GzipFile(fileobj=compressed) as raw:
                while True:
                    chunk = raw.read(READ_CHUNK)
                    if not chunk:
                        break
                    digest.update(chunk)
                    for offset in range(0, len(chunk), SPARSE_BLOCK):
                        block = chunk[offset:offset + SPARSE_BLOCK]
                        if any(block):
                            if os.pwrite(fd, block, logical_position + offset) != len(block):
                                raise OSError("short sparse image write")
                            allocated_payload += len(block)
                    logical_position += len(chunk)
            os.fsync(fd)
        finally:
            os.close(fd)
    if logical_position != manifest["rawSize"]:
        raise ValueError("raw size mismatch")
    raw_hash = digest.hexdigest()
    if raw_hash != manifest["rawSha256"]:
        raise ValueError("raw SHA-256 mismatch")
    checksum = output_path.with_suffix(output_path.suffix + ".sha256")
    checksum.write_text(f"{raw_hash}  {output_path.name}\n", encoding="ascii")
    return {
        "output": str(output_path),
        "logicalBytes": logical_position,
        "nonzeroPayloadBytes": allocated_payload,
        "sha256": raw_hash,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    print(json.dumps(extract(args.archive.resolve(), args.output.resolve()), sort_keys=True))


if __name__ == "__main__":
    main()
