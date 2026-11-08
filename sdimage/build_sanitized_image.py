#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Logan Zarov
# Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
"""Build a compact, sanitized LG D325 SD image archive.

The output is a separate .d325img ZIP containing a manifest and a gzip stream.
The Android writer verifies both hashes, writes the compact image, moves backup
GPT to the physical end of the SD, removes `grow`, expands p34 `userdata`, and
creates a clean ext4 filesystem there.
"""

from __future__ import annotations

import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import uuid
import zipfile
import zlib


SECTOR = 512
BLOCK = 4096
CHUNK = 4 * 1024 * 1024
FORMAT = "d325-sd-image-v1"
MODEL = "LG-D325"
MODEM_PATCHER_VERSION = "1.1.0"
MODEM_PATCHER_SHA256 = "e504538ef8490b8bfe149c2269f8eacb2dfa4e72679c51c8fb8a652dc5ae31cc"
RELEASE_KERNEL_SHA256 = "bac18e6bd0d8c00e72ff471f76f4c9d1eb7fa64a2d2582f80e932ca02e48dd37"
KNOWN_SD_BOOT_SHA256 = "759606e018c2d850e0a649082c05a702f60999e88683ee753bc70c0228c31f9a"
SANITIZED = (
    "pad", "modemst1", "modemst2", "misc", "persist", "fsg", "fsc",
    "ssd", "DDR", "encrypt", "rct", "drm", "sns", "factory", "fota",
    "mpt", "cust", "eksst", "grow",
)
REQUIRED_BOOT_SOURCES = (
    "modem", "sbl1", "rpm", "tz", "sdi", "aboot", "rpmb", "tzb",
    "abootb", "laf", "boot", "recovery", "sbl1b",
)
EXT4_FEATURES = "none,has_journal,ext_attr,resize_inode,filetype,extent,sparse_super,large_file,uninit_bg"


class BuildError(RuntimeError):
    pass


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(CHUNK), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_sd_boot(path: Path) -> dict:
    boot_sha = sha256_file(path)
    if boot_sha != KNOWN_SD_BOOT_SHA256:
        raise BuildError("boot is not the reviewed SD/EFS repack of GitHub release kernel #6")
    with path.open("rb") as source:
        header = source.read(2048)
        if header[:8] != b"ANDROID!":
            raise BuildError("boot source is not an Android boot image")
        kernel_size = struct.unpack_from("<I", header, 8)[0]
        page_size = struct.unpack_from("<I", header, 36)[0]
        if page_size not in (2048, 4096, 8192, 16384) or kernel_size <= 0:
            raise BuildError("unsupported Android boot header")
        source.seek(page_size)
        kernel = source.read(kernel_size)
        if len(kernel) != kernel_size:
            raise BuildError("short zImage in boot source")
    kernel_sha = hashlib.sha256(kernel).hexdigest()
    if kernel_sha != RELEASE_KERNEL_SHA256:
        raise BuildError("boot zImage does not match GitHub release kernel #6")
    return {"bootSha256": boot_sha, "kernelBytes": kernel_size, "kernelSha256": kernel_sha}


def put_u32(data: bytearray, offset: int, value: int) -> None:
    struct.pack_into("<I", data, offset, value & 0xFFFFFFFF)


def put_u64(data: bytearray, offset: int, value: int) -> None:
    struct.pack_into("<Q", data, offset, value)


def header_crc(header: bytearray) -> None:
    put_u32(header, 16, 0)
    size = struct.unpack_from("<I", header, 12)[0]
    if not 92 <= size <= SECTOR:
        raise BuildError(f"invalid GPT header size {size}")
    put_u32(header, 16, zlib.crc32(header[:size]))


def parse_gpt(template: Path) -> tuple[bytearray, bytearray, list[dict], int, int]:
    with template.open("rb") as source:
        mbr = bytearray(source.read(SECTOR))
        header = bytearray(source.read(SECTOR))
        if header[:8] != b"EFI PART":
            raise BuildError("GPT template has no primary header")
        entries_lba = struct.unpack_from("<Q", header, 72)[0]
        count, width = struct.unpack_from("<II", header, 80)
        if entries_lba != 2 or not 35 <= count <= 128 or width != 128:
            raise BuildError(f"unsupported GPT entry layout: lba={entries_lba}, {count} x {width}")
        entry_bytes = count * width
        entry_sectors = (entry_bytes + SECTOR - 1) // SECTOR
        source.seek(entries_lba * SECTOR)
        entries = bytearray(source.read(entry_sectors * SECTOR))
    if len(mbr) != SECTOR or mbr[510:512] != b"\x55\xaa":
        raise BuildError("GPT template has no protective MBR")
    if zlib.crc32(entries[:entry_bytes]) != struct.unpack_from("<I", header, 88)[0]:
        raise BuildError("GPT template entry CRC mismatch")
    check = bytearray(header)
    expected = struct.unpack_from("<I", check, 16)[0]
    put_u32(check, 16, 0)
    if zlib.crc32(check[:struct.unpack_from("<I", check, 12)[0]]) != expected:
        raise BuildError("GPT template header CRC mismatch")

    partitions = []
    for index in range(count):
        offset = index * width
        entry = entries[offset:offset + width]
        if entry[:16] == bytes(16):
            continue
        first, last = struct.unpack_from("<QQ", entry, 32)
        name = entry[56:128].decode("utf-16le").rstrip("\0")
        partitions.append({
            "number": index + 1,
            "name": name,
            "first": first,
            "last": last,
            "sectors": last - first + 1,
            "entry_offset": offset,
        })
    return mbr, header, partitions, count, width


def compact_gpt(
    template: Path,
    userdata_seed_mib: int,
) -> tuple[int, bytes, bytes, bytes, int, list[dict]]:
    mbr, header, partitions, count, width = parse_gpt(template)
    by_name = {item["name"]: item for item in partitions}
    expected_tail = ["system", "cache", "userdata", "grow"]
    if [item["name"] for item in partitions[-4:]] != expected_tail:
        raise BuildError("unexpected D325 GPT tail")
    userdata = by_name["userdata"]
    if userdata["number"] != 34:
        raise BuildError("userdata is not p34")

    entry_bytes = count * width
    entry_sectors = (entry_bytes + SECTOR - 1) // SECTOR
    compact_sectors = userdata["first"] + userdata_seed_mib * 1024 * 1024 // SECTOR + entry_sectors + 1
    last_lba = compact_sectors - 1
    backup_entries_lba = last_lba - entry_sectors
    last_usable = backup_entries_lba - 1
    if last_usable < userdata["first"] + 131071:
        raise BuildError("compact userdata seed must be at least 64 MiB")

    with template.open("rb") as source:
        source.seek(2 * SECTOR)
        entries = bytearray(source.read(entry_sectors * SECTOR))
    mbr[440:444] = bytes(4)
    primary_guid = uuid.uuid5(
        uuid.NAMESPACE_URL,
        "https://github.com/loganzarov-alt/D325f-SDR/sanitized-sd-v1",
    )
    header[56:72] = primary_guid.bytes_le
    for item in partitions:
        offset = item["entry_offset"]
        unique = uuid.uuid5(primary_guid, f"p{item['number']}:{item['name']}")
        entries[offset + 16:offset + 32] = unique.bytes_le
    user_offset = userdata["entry_offset"]
    put_u64(entries, user_offset + 40, last_usable)
    for item in partitions:
        if item["number"] > userdata["number"]:
            offset = item["entry_offset"]
            entries[offset:offset + width] = bytes(width)
    entries_crc = zlib.crc32(entries[:entry_bytes])

    put_u32(mbr, 458, min(last_lba, 0xFFFFFFFF))
    primary = bytearray(header)
    put_u64(primary, 32, last_lba)
    put_u64(primary, 48, last_usable)
    put_u32(primary, 88, entries_crc)
    header_crc(primary)
    backup = bytearray(primary)
    put_u64(backup, 24, last_lba)
    put_u64(backup, 32, 1)
    put_u64(backup, 72, backup_entries_lba)
    header_crc(backup)
    return compact_sectors, bytes(mbr), bytes(primary), bytes(entries), backup_entries_lba, partitions


def parse_ranges(value: str) -> list[tuple[int, int]]:
    fields = [int(item) for item in value.split(",")]
    if not fields or fields[0] % 2 or fields[0] + 1 != len(fields):
        raise BuildError("invalid OTA transfer range")
    return list(zip(fields[1::2], fields[2::2]))


def build_system(lineage_zip: Path, modem_patcher_apk: Path, output: Path, capacity: int) -> dict:
    with zipfile.ZipFile(lineage_zip) as archive:
        names = set(archive.namelist())
        if "system.transfer.list" not in names or "system.new.dat" not in names:
            raise BuildError("only a full block OTA with system.new.dat is supported")
        transfer = archive.read("system.transfer.list").decode("utf-8").splitlines()
        if not transfer or transfer[0] not in ("3", "4"):
            raise BuildError(f"unsupported OTA transfer version {transfer[:1]}")
        records = []
        new_blocks = 0
        for line in transfer[4:]:
            if not line:
                continue
            command, ranges = line.split(" ", 1)
            if command not in ("new", "zero", "erase"):
                raise BuildError(f"incremental OTA command is forbidden: {command}")
            for first, last in parse_ranges(ranges):
                if not 0 <= first < last or last * BLOCK > capacity:
                    raise BuildError(f"OTA range outside system partition: {first}:{last}")
                records.append((command, first, last))
                if command == "new":
                    new_blocks += last - first
        if archive.getinfo("system.new.dat").file_size != new_blocks * BLOCK:
            raise BuildError("system.new.dat length does not match transfer list")

        with output.open("w+b", buffering=0) as image:
            image.truncate(capacity)
            with archive.open("system.new.dat") as payload:
                for command, first, last in records:
                    if command != "new":
                        continue
                    remaining = (last - first) * BLOCK
                    offset = first * BLOCK
                    while remaining:
                        chunk = payload.read(min(CHUNK, remaining))
                        if not chunk:
                            raise BuildError("short system.new.dat")
                        if os.pwrite(image.fileno(), chunk, offset) != len(chunk):
                            raise BuildError("short system image write")
                        offset += len(chunk)
                        remaining -= len(chunk)
                if payload.read(1):
                    raise BuildError("extra system.new.dat bytes")
            image.flush()
            os.fsync(image.fileno())

    run_fsck(output, repair=True)
    # The system partition root is mounted at /system at runtime, so image
    # paths start at /app rather than /system/app.
    debugfs(output, "mkdir /app/ModemPatcher", allow_exists=True)
    debugfs(output, f"write {modem_patcher_apk} /app/ModemPatcher/Modem-Patcher.apk")
    debugfs(output, "set_inode_field /app/ModemPatcher/Modem-Patcher.apk mode 0100644")
    stat = debugfs(output, "stat /app/ModemPatcher/Modem-Patcher.apk", write=False)
    if f"Size: {modem_patcher_apk.stat().st_size}" not in stat.replace("  ", " "):
        raise BuildError("Modem Patcher APK was not installed into system image")
    run_fsck(output, repair=True)
    return {"otaSystemNewDatBytes": new_blocks * BLOCK, "modemPatcherDebugfsStat": stat.strip()}


def debugfs(image: Path, command: str, write: bool = True, allow_exists: bool = False) -> str:
    args = ["debugfs"]
    if write:
        args.append("-w")
    args.extend(["-R", command, str(image)])
    result = subprocess.run(args, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if result.returncode != 0:
        raise BuildError(f"debugfs failed: {result.stdout}")
    lower = result.stdout.lower()
    if "file not found" in lower or "no such file" in lower or ("already exists" in lower and not allow_exists):
        raise BuildError(f"debugfs command failed: {result.stdout}")
    return result.stdout


def run_fsck(image: Path, repair: bool) -> None:
    result = subprocess.run(["e2fsck", "-fy" if repair else "-fn", str(image)], text=True,
                            stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    allowed = (0, 1) if repair else (0,)
    if result.returncode not in allowed:
        raise BuildError(f"e2fsck failed ({result.returncode}):\n{result.stdout}")


def make_cache(output: Path, capacity: int) -> None:
    with output.open("wb") as target:
        target.truncate(capacity)
    result = subprocess.run([
        "mkfs.ext4", "-F", "-b", "4096", "-m", "0", "-L", "cache",
        "-O", EXT4_FEATURES, str(output),
    ], text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    if result.returncode != 0:
        raise BuildError(f"cache mkfs failed:\n{result.stdout}")
    run_fsck(output, repair=False)


def copy_sparse(source: Path, destination_fd: int, destination_offset: int, capacity: int) -> None:
    size = source.stat().st_size
    if size > capacity:
        raise BuildError(f"{source} ({size}) is larger than partition capacity {capacity}")
    with source.open("rb", buffering=0) as src:
        position = 0
        while position < size:
            try:
                data_start = os.lseek(src.fileno(), position, os.SEEK_DATA)
            except OSError:
                break
            hole = os.lseek(src.fileno(), data_start, os.SEEK_HOLE)
            end = min(hole, size)
            src.seek(data_start)
            output_position = destination_offset + data_start
            remaining = end - data_start
            while remaining:
                chunk = src.read(min(CHUNK, remaining))
                if not chunk:
                    raise BuildError(f"short sparse read from {source}")
                if os.pwrite(destination_fd, chunk, output_position) != len(chunk):
                    raise BuildError(f"short sparse write from {source}")
                output_position += len(chunk)
                remaining -= len(chunk)
            position = hole


def verify_zero_range(fd: int, offset: int, size: int, name: str) -> None:
    position = 0
    while position < size:
        chunk = os.pread(fd, min(CHUNK, size - position), offset + position)
        if len(chunk) == 0:
            raise BuildError(f"short privacy verification read for {name}")
        if any(chunk):
            raise BuildError(f"privacy failure: sanitized partition {name} is nonzero")
        position += len(chunk)


def gzip_image(raw: Path, output: Path) -> tuple[str, int, str]:
    raw_digest = hashlib.sha256()
    with raw.open("rb") as source, output.open("wb") as compressed_file:
        with gzip.GzipFile(filename="", mode="wb", fileobj=compressed_file, compresslevel=6, mtime=0) as compressed:
            for chunk in iter(lambda: source.read(CHUNK), b""):
                raw_digest.update(chunk)
                compressed.write(chunk)
    return raw_digest.hexdigest(), output.stat().st_size, sha256_file(output)


def load_source_map(path: Path) -> dict[str, Path]:
    raw = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict) or not all(isinstance(key, str) and isinstance(value, str) for key, value in raw.items()):
        raise BuildError("source map must be a JSON object of partition names to file paths")
    sources = {name: Path(value).resolve() for name, value in raw.items()}
    forbidden = set(sources).intersection(SANITIZED)
    if forbidden:
        raise BuildError(f"private/calibration sources are forbidden: {sorted(forbidden)}")
    missing = set(REQUIRED_BOOT_SOURCES).difference(sources)
    if missing:
        raise BuildError(f"required boot sources are absent: {sorted(missing)}")
    for name, source in sources.items():
        if not source.is_file():
            raise BuildError(f"missing source for {name}: {source}")
    return sources


def build(args: argparse.Namespace) -> dict:
    template = args.gpt_template.resolve()
    lineage = args.lineage_zip.resolve()
    patcher = args.modem_patcher_apk.resolve()
    output = args.output.resolve()
    for source in (template, lineage, patcher):
        if not source.is_file():
            raise BuildError(f"input is absent: {source}")
    if sha256_file(patcher) != MODEM_PATCHER_SHA256:
        raise BuildError("Modem Patcher APK is not the published v1.1.0 asset")
    sources = load_source_map(args.source_map)
    boot_meta = verify_sd_boot(sources["boot"])

    compact_sectors, mbr, primary, entries, backup_entries_lba, partitions = compact_gpt(
        template, args.userdata_seed_mib,
    )
    by_name = {item["name"]: item for item in partitions}
    for name in sources:
        if name not in by_name or name in ("system", "cache", "userdata", "grow"):
            raise BuildError(f"invalid independently supplied partition: {name}")

    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="d325-image-", dir=args.work_dir) as temporary:
        work = Path(temporary)
        raw = work / "image.img"
        system = work / "system.img"
        cache = work / "cache.img"
        compressed = work / "image.img.gz"

        system_meta = build_system(
            lineage, patcher, system, by_name["system"]["sectors"] * SECTOR,
        )
        make_cache(cache, by_name["cache"]["sectors"] * SECTOR)

        raw_size = compact_sectors * SECTOR
        with raw.open("w+b", buffering=0) as image:
            image.truncate(raw_size)
            os.pwrite(image.fileno(), mbr, 0)
            os.pwrite(image.fileno(), primary, SECTOR)
            os.pwrite(image.fileno(), entries, 2 * SECTOR)
            os.pwrite(image.fileno(), entries, backup_entries_lba * SECTOR)
            backup = bytearray(primary)
            last_lba = compact_sectors - 1
            put_u64(backup, 24, last_lba)
            put_u64(backup, 32, 1)
            put_u64(backup, 72, backup_entries_lba)
            header_crc(backup)
            os.pwrite(image.fileno(), backup, last_lba * SECTOR)

            source_report = {}
            for name, source in sources.items():
                part = by_name[name]
                capacity = part["sectors"] * SECTOR
                copy_sparse(source, image.fileno(), part["first"] * SECTOR, capacity)
                source_report[name] = {
                    "bytes": source.stat().st_size,
                    "sha256": sha256_file(source),
                }
            copy_sparse(system, image.fileno(), by_name["system"]["first"] * SECTOR,
                        by_name["system"]["sectors"] * SECTOR)
            copy_sparse(cache, image.fileno(), by_name["cache"]["first"] * SECTOR,
                        by_name["cache"]["sectors"] * SECTOR)
            image.flush()
            os.fsync(image.fileno())

            for name in SANITIZED:
                if name == "grow":
                    continue
                part = by_name[name]
                verify_zero_range(image.fileno(), part["first"] * SECTOR, part["sectors"] * SECTOR, name)

        raw_sha, gzip_size, gzip_sha = gzip_image(raw, compressed)
        manifest = {
            "format": FORMAT,
            "model": MODEL,
            "rawSize": raw_size,
            "rawSha256": raw_sha,
            "gzipSize": gzip_size,
            "gzipSha256": gzip_sha,
            "minimumTargetBytes": raw_size,
            "sectorSize": SECTOR,
            "userdataPartition": by_name["userdata"]["number"],
            "userdataStartSector": by_name["userdata"]["first"],
            "sanitizedPartitions": list(SANITIZED),
            "bundledModemPatcherVersion": MODEM_PATCHER_VERSION,
            "bundledModemPatcherSha256": MODEM_PATCHER_SHA256,
        }
        manifest_bytes = (json.dumps(manifest, sort_keys=True, indent=2) + "\n").encode("utf-8")
        with zipfile.ZipFile(output, "w", allowZip64=True) as archive:
            manifest_info = zipfile.ZipInfo("manifest.json", (1980, 1, 1, 0, 0, 0))
            manifest_info.compress_type = zipfile.ZIP_DEFLATED
            manifest_info.external_attr = 0o100644 << 16
            archive.writestr(manifest_info, manifest_bytes)
            image_info = zipfile.ZipInfo("image.img.gz", (1980, 1, 1, 0, 0, 0))
            image_info.compress_type = zipfile.ZIP_STORED
            image_info.external_attr = 0o100644 << 16
            with archive.open(image_info, "w", force_zip64=True) as target, compressed.open("rb") as source:
                shutil.copyfileobj(source, target, CHUNK)

        report = {
            "archive": str(output),
            "archiveBytes": output.stat().st_size,
            "archiveSha256": sha256_file(output),
            "manifest": manifest,
            "lineageZip": {"name": lineage.name, "sha256": sha256_file(lineage)},
            "gptTemplateSha256": sha256_file(template),
            "partitionSources": source_report,
            "releaseKernel": boot_meta,
            "privacy": {
                "calibrationSourcesAccepted": False,
                "verifiedZeroPartitions": [name for name in SANITIZED if name != "grow"],
                "growGptEntryRemoved": True,
                "userdataFilesystemIncluded": False,
                "userdataCreatedByWriter": True,
            },
            "system": system_meta,
        }
    report_path = output.with_suffix(output.suffix + ".report.json")
    report_path.write_text(json.dumps(report, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    checksum_path = output.with_suffix(output.suffix + ".sha256")
    checksum_path.write_text(f"{report['archiveSha256']}  {output.name}\n", encoding="ascii")
    return report


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--gpt-template", type=Path, required=True)
    result.add_argument("--source-map", type=Path, required=True)
    result.add_argument("--lineage-zip", type=Path, required=True)
    result.add_argument("--modem-patcher-apk", type=Path, required=True)
    result.add_argument("--output", type=Path, required=True)
    result.add_argument("--work-dir", type=Path, default=None)
    result.add_argument("--userdata-seed-mib", type=int, default=64)
    return result


def main() -> None:
    args = parser().parse_args()
    report = build(args)
    print(json.dumps({
        "archive": report["archive"],
        "bytes": report["archiveBytes"],
        "sha256": report["archiveSha256"],
        "rawBytes": report["manifest"]["rawSize"],
    }, sort_keys=True))


if __name__ == "__main__":
    main()
