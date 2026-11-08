#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Logan Zarov
# Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
"""Independently verify a generated D325 .d325img and its embedded APK."""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import tempfile
import zipfile
import zlib


SECTOR = 512
CHUNK = 4 * 1024 * 1024
REQUIRED_ZERO = {
    "pad", "modemst1", "modemst2", "misc", "persist", "fsg", "fsc",
    "ssd", "DDR", "encrypt", "rct", "drm", "sns", "factory", "fota",
    "mpt", "cust", "eksst",
}


def hash_stream(source) -> tuple[int, str]:
    digest = hashlib.sha256()
    size = 0
    for chunk in iter(lambda: source.read(CHUNK), b""):
        digest.update(chunk)
        size += len(chunk)
    return size, digest.hexdigest()


def hash_file(path: Path) -> str:
    with path.open("rb") as source:
        return hash_stream(source)[1]


def parse_primary(prefix: bytes) -> tuple[dict, dict[str, dict]]:
    header = prefix[SECTOR:2 * SECTOR]
    if header[:8] != b"EFI PART":
        raise ValueError("primary GPT is missing")
    header_size = struct.unpack_from("<I", header, 12)[0]
    check = bytearray(header)
    expected_header_crc = struct.unpack_from("<I", check, 16)[0]
    struct.pack_into("<I", check, 16, 0)
    if zlib.crc32(check[:header_size]) != expected_header_crc:
        raise ValueError("primary GPT header CRC mismatch")
    entries_lba = struct.unpack_from("<Q", header, 72)[0]
    count, width, expected_entries_crc = struct.unpack_from("<III", header, 80)
    entries = prefix[entries_lba * SECTOR:entries_lba * SECTOR + count * width]
    if len(entries) != count * width or zlib.crc32(entries) != expected_entries_crc:
        raise ValueError("primary GPT entry CRC mismatch")
    partitions = {}
    for index in range(count):
        entry = entries[index * width:(index + 1) * width]
        if entry[:16] == bytes(16):
            continue
        first, last = struct.unpack_from("<QQ", entry, 32)
        name = entry[56:128].decode("utf-16le").rstrip("\0")
        if name in partitions:
            raise ValueError(f"duplicate partition name {name}")
        partitions[name] = {
            "number": index + 1,
            "first": first,
            "last": last,
            "bytes": (last - first + 1) * SECTOR,
        }
    return {
        "backup": struct.unpack_from("<Q", header, 32)[0],
        "lastUsable": struct.unpack_from("<Q", header, 48)[0],
        "entryCount": count,
        "entryWidth": width,
    }, partitions


def verify_backup(tail: bytes, raw_size: int, primary: dict) -> None:
    header = tail[-SECTOR:]
    last_lba = raw_size // SECTOR - 1
    if header[:8] != b"EFI PART" or struct.unpack_from("<Q", header, 24)[0] != last_lba:
        raise ValueError("backup GPT is not at compact image end")
    if primary["backup"] != last_lba:
        raise ValueError("primary GPT does not point to backup GPT")
    header_size = struct.unpack_from("<I", header, 12)[0]
    check = bytearray(header)
    expected = struct.unpack_from("<I", check, 16)[0]
    struct.pack_into("<I", check, 16, 0)
    if zlib.crc32(check[:header_size]) != expected:
        raise ValueError("backup GPT header CRC mismatch")
    array_bytes = primary["entryCount"] * primary["entryWidth"]
    array_sectors = (array_bytes + SECTOR - 1) // SECTOR
    entries = tail[-(array_sectors + 1) * SECTOR:-SECTOR][:array_bytes]
    if zlib.crc32(entries) != struct.unpack_from("<I", header, 88)[0]:
        raise ValueError("backup GPT entry CRC mismatch")


def load_forbidden_tokens(paths: list[Path]) -> set[bytes]:
    tokens = set()
    for path in paths:
        text = path.read_text(encoding="utf-8", errors="ignore")
        tokens.update(match.group(0).encode("ascii") for match in re.finditer(r"(?<!\d)\d{15}(?!\d)", text))
        # Android binder dumps often show the IMEI as UTF-16LE bytes in groups
        # of eight hexadecimal characters rather than as visible decimal text.
        parcel = bytearray()
        for line in text.splitlines():
            if ":" not in line:
                continue
            hexadecimal = line.split(":", 1)[1].split("'", 1)[0]
            for word in re.findall(r"(?<![0-9A-Fa-f])[0-9A-Fa-f]{8}(?![0-9A-Fa-f])", hexadecimal):
                # Parcel's hexdump prints each uint32 in host integer order;
                # reverse the displayed word to recover its in-memory bytes.
                parcel.extend(bytes.fromhex(word)[::-1])
        for match in re.finditer(rb"(?:[0-9]\x00){15}", parcel):
            tokens.add(match.group(0)[::2])
        for match in re.finditer(rb"(?:\x00[0-9]){15}", parcel):
            tokens.add(match.group(0)[1::2])
    return tokens


def verify(path: Path, forbidden_tokens: set[bytes] | None = None) -> dict:
    forbidden_tokens = forbidden_tokens or set()
    archive_hash = hash_file(path)
    with zipfile.ZipFile(path) as archive:
        if archive.namelist() != ["manifest.json", "image.img.gz"]:
            raise ValueError("unexpected image archive members or order")
        manifest = json.loads(archive.read("manifest.json"))
        with archive.open("image.img.gz") as compressed:
            gzip_size, gzip_hash = hash_stream(compressed)
        if gzip_size != manifest["gzipSize"] or gzip_hash != manifest["gzipSha256"]:
            raise ValueError("gzip payload hash/size mismatch")

        with tempfile.TemporaryDirectory(prefix="verify-d325-") as temporary:
            temporary = Path(temporary)
            system_path = temporary / "system.img"
            apk_path = temporary / "Modem-Patcher.apk"
            raw_hash = hashlib.sha256()
            raw_size = 0
            prefix = bytearray()
            tail = bytearray()
            primary = None
            partitions = None
            nonzero_private = set()
            cache_magic = None
            forbidden_found = False
            token_carry = b""
            system_fd = None
            try:
                with archive.open("image.img.gz") as compressed, gzip.GzipFile(fileobj=compressed) as raw:
                    for chunk in iter(lambda: raw.read(CHUNK), b""):
                        chunk_start = raw_size
                        chunk_end = chunk_start + len(chunk)
                        raw_hash.update(chunk)
                        raw_size = chunk_end
                        token_window = token_carry + chunk
                        if any(token in token_window for token in forbidden_tokens):
                            forbidden_found = True
                        token_carry = token_window[-14:]
                        if len(prefix) < 34 * SECTOR:
                            prefix.extend(chunk[:34 * SECTOR - len(prefix)])
                            if len(prefix) == 34 * SECTOR:
                                primary, partitions = parse_primary(prefix)
                                if "grow" in partitions or partitions["userdata"]["number"] != 34:
                                    raise ValueError("grow remains or userdata is not p34")
                                if not REQUIRED_ZERO.issubset(partitions):
                                    raise ValueError("required privacy partitions are absent from GPT")
                                system = partitions["system"]
                                system_path.touch()
                                system_path.write_bytes(b"")
                                with system_path.open("r+b") as system_file:
                                    system_file.truncate(system["bytes"])
                                system_fd = os.open(system_path, os.O_RDWR)
                        if partitions is not None:
                            for name in REQUIRED_ZERO:
                                part = partitions[name]
                                start = part["first"] * SECTOR
                                end = start + part["bytes"]
                                left, right = max(start, chunk_start), min(end, chunk_end)
                                if left < right and any(chunk[left - chunk_start:right - chunk_start]):
                                    nonzero_private.add(name)
                            system = partitions["system"]
                            start = system["first"] * SECTOR
                            end = start + system["bytes"]
                            left, right = max(start, chunk_start), min(end, chunk_end)
                            if left < right:
                                payload = chunk[left - chunk_start:right - chunk_start]
                                if any(payload):
                                    os.pwrite(system_fd, payload, left - start)
                            cache_super = partitions["cache"]["first"] * SECTOR + 1024 + 56
                            if chunk_start <= cache_super and cache_super + 2 <= chunk_end:
                                cache_magic = chunk[cache_super - chunk_start:cache_super - chunk_start + 2]
                        tail = (tail + chunk)[-34 * SECTOR:]
            finally:
                if system_fd is not None:
                    os.close(system_fd)

            if raw_size != manifest["rawSize"] or raw_hash.hexdigest() != manifest["rawSha256"]:
                raise ValueError("raw image hash/size mismatch")
            if nonzero_private:
                raise ValueError(f"private partitions are nonzero: {sorted(nonzero_private)}")
            if forbidden_found:
                raise ValueError("a forbidden personal 15-digit token is present in the raw image")
            if cache_magic != b"\x53\xef":
                raise ValueError("cache ext4 magic is absent")
            verify_backup(tail, raw_size, primary)

            fsck = subprocess.run(["e2fsck", "-fn", str(system_path)], text=True,
                                  stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            if fsck.returncode != 0:
                raise ValueError(f"system e2fsck failed ({fsck.returncode}): {fsck.stdout}")
            dump = subprocess.run([
                "debugfs", "-R", f"dump /app/ModemPatcher/Modem-Patcher.apk {apk_path}", str(system_path),
            ], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
            if dump.returncode != 0 or not apk_path.is_file():
                raise ValueError(f"embedded Modem Patcher is absent: {dump.stdout}")
            apk_hash = hash_file(apk_path)
            if apk_hash != manifest["bundledModemPatcherSha256"]:
                raise ValueError("embedded Modem Patcher hash mismatch")

    return {
        "archive": str(path),
        "archiveBytes": path.stat().st_size,
        "archiveSha256": archive_hash,
        "gzipSha256": gzip_hash,
        "rawBytes": raw_size,
        "rawSha256": raw_hash.hexdigest(),
        "zeroPartitions": sorted(REQUIRED_ZERO),
        "userdataStartSector": partitions["userdata"]["first"],
        "modemPatcherSha256": apk_hash,
        "systemFsck": "clean",
        "cacheExt4": True,
        "primaryAndBackupGpt": "valid",
        "forbiddenPersonalTokenCount": len(forbidden_tokens),
        "forbiddenPersonalTokensFound": False,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image", type=Path)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--forbidden-token-file", action="append", type=Path, default=[])
    args = parser.parse_args()
    report = verify(args.image.resolve(), load_forbidden_tokens(args.forbidden_token_file))
    text = json.dumps(report, sort_keys=True, indent=2) + "\n"
    if args.report:
        args.report.write_text(text, encoding="utf-8")
    print(text, end="")


if __name__ == "__main__":
    main()
