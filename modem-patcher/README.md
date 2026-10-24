# LG D325 Modem Patcher 1.1.0

Android APK and TWRP toggle/recovery installers for LG D325 (`w5ds`).

**Save a dump first and copy it to a PC.** Keep both the `.bin` and `.sha256`
files. A verified dump can recover a partition left MIXED or UNKNOWN by an
interrupted write. A reversible XOR patch alone cannot recover every partial
write, and an in-memory rollback cannot protect against power loss.

## Android application

Install `Modem-Patcher-LG-D325-v1.1.0.apk` and grant root access in your su manager.
This release uses a dedicated public release signing key. **Updating over the
old “LG IQ Local Build” version requires uninstalling it first.** Copy any
important private files out before uninstalling. Dumps in `Internal storage/D325f`
are outside application-private storage.

- **Refresh** reads and classifies the modem partition.
- **Save dump** reads the complete partition twice, with a cache flush between
  reads, compares both readings and saves
  `/data/media/0/D325f/modem-dump-<state>-<sha8>.bin` and `.bin.sha256`.
  Existing dump/checksum names are never overwritten.
- **Apply patch / Restore stock** changes the six supported firmware files.
  Before a write, if no recoverable dump is present in D325f, the application offers to save
  one. You may save it and then confirm the write, cancel, or explicitly continue
  without a dump.
- **Restore from dump** is shown for MIXED/UNKNOWN. Copy a supported
  `modem-dump-*.bin` into `Internal storage/D325f`, select it and confirm.
  Supported STOCK and PATCHED dumps are accepted; a present checksum must match.
- **Licenses** displays GPLv3 and all bundled runtime notices.
- **Reboot phone** appears after a verified write. Rebooting remains a separate
  action. Do not power off while writing.

The launcher icon is the original Android `ic_menu_manage` system resource.
Firmware is processed in a native process, without large Java firmware arrays.

## TWRP installers

`LG-D325-Modem-Toggle-TWRP-v1.2.zip` toggles STOCK to PATCHED and PATCHED to STOCK.
Before toggling it saves a verified persistent dump in `/data/media/0/D325f`
(unless the exact verified dump already exists). Mount/decrypt `/data` first.
After booting, copy this dump and checksum to a PC.

If the partition is MIXED/UNKNOWN, put **exactly one** supported
`modem-dump-*.bin` beside the toggle ZIP. Include its `.sha256` if available.
Flashing the ZIP restores the dump instead of trying to apply XOR to damaged data.

`LG-D325-Modem-Restore-Dump-TWRP-v1.2.zip` always restores the supported dump
beside it, even when the current partition is STOCK or PATCHED. Multiple valid
dumps cause an error; keep only the intended dump beside the ZIP.

The installers identify the modem partition by mounted firmware and partition
labels, require a unique 64 MiB target, check the model and firmware hashes,
and do not bind to a serial number, IMEI or fixed block index. They do not write
boot, recovery, modemst, persist or fsg. They never reboot automatically.
A failure leaves diagnostic logs in the temporary directory printed by TWRP.

## Fresh readback and dump validation

Every fresh block-device read first performs `fsync` and
`ioctl(BLKFLSBUF)`. A separate `O_DIRECT` descriptor then reads through a
4096-byte-aligned buffer. Flush, direct-open and read errors are failures;
there is no cached-read fallback. This applies to pre-write validation,
post-write verification, rollback verification and both partition dump reads.

Regular-file tests use `fsync` and `posix_fadvise(POSIX_FADV_DONTNEED)` before
hashing. `READBACK_VERIFIED` is printed only after the fresh read matches the
expected complete image. On a write/verification failure the tool attempts a
rollback and verifies it with the same fresh-read barriers.

Dump restoration validates the six supported firmware hashes, writes only
sectors differing from the dump and verifies the entire result. It can repair
corrupt FAT metadata. A dump from a different phone is not a substitute for your
own backup: full dump restore also restores that image's unpatched metadata.
Never use a dump containing somebody else's individual data.

## Unchanged patch

`patch.payload` is the original byte-identical D325PT03 payload:

- SHA-256: `10fc481c411f22153fb570e970b0eecb652f2360ca9d7a79df56a57c377e2ba8`
- Payload container: 5,014 bytes; six files; 223 runs; 2,672 XOR bytes.
- Normal patching changes 20 sectors and preserves all unrelated bytes.

The pristine stock image SHA-256 begins `1d6d32af` and ends `f455`. The six
patched file hashes match the working `42ecd065…227e` image. Whole-image hashes
can differ because FAT metadata and unrelated bytes are preserved. Two toggles
of an accepted image reproduce that starting image exactly. File fragmentation
is rejected. This release does not add firmware versions or frequency changes.

## Build

Dependencies: Python 3, JDK, Android SDK `android.jar`, `aapt`, `zipalign`,
`apksigner`, R8/D8 JAR, ARM GCC/binutils and `qemu-arm`. Install them separately.
The unmodified musl 1.2.5 source archive and its SHA-256 are supplied.

```sh
sh recovery/build-runtime.sh
python3 recovery/build-zip.py
export ANDROID_JAR=/path/to/android.jar
export R8_JAR=/path/to/r8.jar
export LG_PATCHER_KEYSTORE=/private/path/public-release.keystore
export LG_PATCHER_KS_PASS_FILE=/private/path/keystore-password.txt
python3 build.py
```

`LG_PATCHER_KS_PASS` may be used instead of the password file. Optional
`LG_PATCHER_KEY_ALIAS` selects the signing alias. The build requires an explicitly
supplied release keystore and rejects the old local/debug signing identities.
No key or password is generated or embedded by the build. Preserve your release
key privately for future updates. The supplied APK uses a dedicated release
certificate; its fingerprint is recorded in `reports/build-report.json`.

## Command line

The partition must be selected explicitly (the APK and installers do this by
validated labels/mounts):

```sh
modem-toggle --block /dev/block/SELECTED_MODEM --payload patch.payload
modem-toggle --block /dev/block/SELECTED_MODEM --payload patch.payload --dump /data/media/0/D325f
modem-toggle --block /dev/block/SELECTED_MODEM --payload patch.payload --restore-dump /path/to/modem-dump.bin --allow-block-write
```

Android writes also require `--android-root`; TWRP writes require root and the
TWRP environment. `--dump FILE` saves to an explicit new filename; `--dump DIR/`
uses the canonical state/hash filename. A directory must exist unless it is the
standard D325f directory. `--image FILE` restricts the target to a 64 MiB regular
file for offline testing. `--dry-run` validates an operation without writing.

## Tests and device confirmation

```sh
STOCK_IMAGE=/private/stock.bin WORKING_IMAGE=/private/patched.bin python3 recovery/test-toggle.py
D325_HOST_TESTS=1 STOCK_IMAGE=/private/stock.bin WORKING_IMAGE=/private/patched.bin python3 recovery/test-toggle.py
```

These tests use only regular image fixtures. They cover dumps, no-overwrite,
MIXED/UNKNOWN recovery, interrupted writes with rollback disabled, rollback,
cache-barrier failure and ordering, aligned direct reads, unsupported dumps,
and changed FAT placement. Failure-injection controls are compiled only into
test binaries. The production native tool does not contain them.

Device instrumentation is optional: build `tests/build-tests.py` with the same
release signing settings, obtain permission before installation/running, then
use `tests/run-device-tests.py --confirm-device-test`. It writes app-private
fixtures while accessing the real modem only for reading. This release has not
been installed or flashed on the phone: **confirmation is required before any
phone writes.** See `reports/` for the actual offline checks performed.

## Licenses and archive contents

- Project's own code: **GPL-3.0-or-later**, Copyright (C) 2026 Logan Zarov.
  The complete GPLv3 text is in `LICENSE`.
- musl: **MIT**, with compatible third-party permissive notices.
- GCC runtime: **GCC Runtime Library Exception 3.1**; notices and GPL text are
  included in `licenses/`.

APK and TWRP ZIPs include these notices. SDK/JDK/R8/compiler tools are not
redistributed. Archives contain no full modem image, boot image, individual
calibration/IMEI data, keystore or signing password. The XOR payload is supplied
as patch data; its format does not itself remove third-party licensing duties.
