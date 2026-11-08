# D325 SD Writer

This is a deliberately separate, root-only Android application for writing a
ordinary raw `.img` (or compact `.d325img`) to a removable SD card. The image
is **not embedded in the APK**. Both forms must decompress to the exact fixed
published sanitized SHA-256. After the image is written, the application:

1. verifies that the selected target is reported as removable and is not
   `mmcblk0`;
2. moves the backup GPT to the physical end of the selected SD card;
3. removes the disposable `grow` entry;
4. extends p34 `userdata` to the last usable sector;
5. creates a new ext4 filesystem across that whole partition and checks it.

The current implementation supports the rooted Redmi build host used by this
project and rooted Pixel 4-class devices using a USB-OTG SD reader. It requires
`su` and Android's `/system/bin/dd`, `blockdev`,
`mke2fs`, `e2fsck`, `umount`, and `sync`. It refuses to continue if any of these
tools is missing.

## Safety flow

The application scans every raw image byte before enabling a write. A compact
archive additionally verifies its gzip stream. It accepts only the hard-coded
published raw size and SHA-256, then requires selection of a removable block
device and the exact confirmation text `ERASE <device-name>`. After writing it
reads the complete image range back and verifies SHA-256. The internal eMMC
name `mmcblk0`, partition paths, and shell metacharacters are rejected by code.

Writing is destructive. This application and the generated image remain
untested on a real LG D325 with working eMMC.

## Image privacy contract

`manifest.json` must declare that `modemst1`, `modemst2`, `persist`, `fsg`,
`fsc`, and `factory` are sanitized. The supplied builder is stricter: it writes
zeros and verifies zeros for those plus `pad`, `misc`, `ssd`, `DDR`, `encrypt`,
`rct`, `drm`, `sns`, `fota`, `mpt`, `cust`, and `eksst`; it removes `grow`; and
it includes no userdata filesystem. Consequently no source calibration or IMEI
is copied into the image. A fresh empty userdata filesystem is made only on the
destination card.

The system image is generated from a clean full LineageOS block OTA and embeds
the published `Modem-Patcher-LG-D325-v1.1.0.apk` at
`/system/app/ModemPatcher/Modem-Patcher.apk`. Its required SHA-256 is
`e504538ef8490b8bfe149c2269f8eacb2dfa4e72679c51c8fb8a652dc5ae31cc`.

## GitHub Actions artifact

The `Build sanitized SD writers` workflow runs JVM tests for the target-name
policy and GPT rewrite, then uploads `D325-SD-Writer-0.2.0-ci-test-signed.apk`.
The CI artifact uses the Android debug key unless publication signing secrets
are configured. The `.d325img` stays separate because its boot images are local
inputs and because no private dump should ever be uploaded to Actions.
