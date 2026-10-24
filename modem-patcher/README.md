# LG D325 Modem Patcher 1.0.4 / TWRP Toggle 1.1

Reversible modem-file patching for the LG D325 (w5ds), used with D325f SDR.
Project: Logan Zarov. AI tools assisted with development and documentation.

- Android: `Modem-Patcher-LG-D325-v1.0.4.apk`, versionCode 5, Android 7.1+, root.
- Recovery: `LG-D325-Modem-Toggle-TWRP-v1.1.zip`, TWRP on LG D325.
- Both packages use the same native engine and XOR payload.
- No complete modem image, boot image, calibration backup or signing key is included.

## Use

In Android, start with **Проверить** (Check), which only reads the modem.
**Пропатчить модем** applies the patch; **Восстановить сток** reverses it.
Reboot is a separate action.

The TWRP ZIP toggles the current state: stock → patched → stock.
It does not reboot automatically. Model, unique partition selection, 64 MiB
partition size, FAT layout and hashes of all six target files are checked.
Unsupported firmware, mixed states and fragmented target files are rejected.

Keep a personal modem backup before writing. The reverse patch reconstructs the
supported original files without that backup, but interrupted power can prevent
a successful write or rollback. Start below 48 °C; the engine checks 58 °C before
writing. The tool does not install a kernel, root or a complete phone setup.

## Verification status

**Experimental: writing to a real modem partition and flashing through TWRP are
not confirmed by the supplied test reports.**

The supplied reports record 14 Android checks (real modem read only; writes to
private regular-file copies) and seven ARM/QEMU fixture tests. Their source
reproduction report records matching native code, DEX and payload after rebuild.

Publication review independently verified source-manifest hashes, native/payload/
scanner/license byte identity across source/APK/ZIP, regenerated payload bytes,
and APK v2 signature/content digest. Thirteen new host tests passed on synthetic
FAT files, including exact apply/restore, rejection and rollback behavior.
No firmware images or real devices are needed for those host tests:

```sh
python3 tests/host-synthetic.py
```

This review did not rebuild the Android APK or repeat the supplied device/ARM tests.
Detailed results: [reports/publication-review.json](reports/publication-review.json).

## Build

Requires Python 3, JDK, Android SDK `android.jar`, aapt, zipalign, apksigner,
R8/D8, ARM GCC/binutils and qemu-arm:

```sh
sh recovery/build-runtime.sh
python3 recovery/build-zip.py
ANDROID_JAR=/path/to/android.jar R8_JAR=/path/to/r8.jar python3 build.py
python3 package-sources.py
```

The bundled musl 1.2.5 archive is hash-checked. A local signing key is generated
under `build/` unless `LG_PATCHER_KEYSTORE` is supplied; keep your key for updates.
The uploaded APK retains its original signature and bytes. Source packaging adds
the project license, documentation and synthetic host tests; runtime code and
payload are unchanged.

[Russian instructions](README.ru.txt) · [Licensing](LICENSING.md)
