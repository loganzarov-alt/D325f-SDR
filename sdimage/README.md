# Sanitized compact SD image

`build_sanitized_image.py` creates the separate image consumed by D325 SD
Writer. It requires a known D325 GPT template, clean boot-chain inputs, a full
LineageOS block OTA, and the exact published Modem Patcher 1.1.0 APK.

The accepted SD `boot.img` is also fixed: its whole-image SHA-256 is
`759606e018c2d850e0a649082c05a702f60999e88683ee753bc70c0228c31f9a`.
Its extracted `zImage` must exactly match GitHub release `kernel-6-source`:
`bac18e6bd0d8c00e72ff471f76f4c9d1eb7fa64a2d2582f80e932ca02e48dd37`.
The whole boot image differs from the release asset because its reviewed
ramdisk/DTB mounts the SD partition layout and uses the sanitized EFS ranges.

Example source map (paths are illustrative):

```json
{
  "modem": "/safe/stock/modem.bin",
  "sbl1": "/safe/stock/sbl1.bin",
  "rpm": "/safe/stock/rpm.bin",
  "tz": "/safe/stock/tz.bin",
  "sdi": "/safe/stock/sdi.bin",
  "aboot": "/safe/sd/aboot-sd-only.bin",
  "rpmb": "/safe/stock/rpmb.bin",
  "tzb": "/safe/stock/tzb.bin",
  "abootb": "/safe/sd/aboot-sd-only.bin",
  "laf": "/safe/stock/laf.bin",
  "boot": "/safe/sd/boot-sd.img",
  "recovery": "/safe/sd/recovery-sd.img",
  "sbl1b": "/safe/stock/sbl1b.bin"
}
```

Run:

```sh
python3 sdimage/build_sanitized_image.py \
  --gpt-template /safe/gpt-preview.img \
  --source-map /safe/source-map.json \
  --lineage-zip /safe/lineage-w5-signed.zip \
  --modem-patcher-apk /safe/Modem-Patcher-LG-D325-v1.1.0.apk \
  --output /safe/D325-sanitized.d325img
```

Calibration-bearing partition names are rejected if they appear in the source
map. The builder reconstructs `/system`, inserts Modem Patcher, creates an empty
compatible `/cache`, verifies every privacy-sensitive range is zero, creates a
deterministic gzip stream, and emits `.report.json` and `.sha256` sidecars.

The raw image ends shortly after the fixed p34 start, so it stays compact when
compressed. The writer relocates backup GPT and formats the final-size userdata;
the image itself never contains a userdata filesystem or previous user files.
