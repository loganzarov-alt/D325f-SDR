# Verification

## First public release 1.0.0 (versionCode 1)

Logan Zarov tested the published APK on the modified LG D325 and confirmed that
it works on **24 October 2026**. APK SHA-256:
`c6acd40b7dce5a4ebb53da4643de93bd6873fbe16d7810b7a3f09c0819f36cc7`.
This is a functional check on that prepared phone; no RF measurement results or
second-device test are recorded here.

- `release-1.0.0-build.log`: clean source build and three source-selection/file-format unit tests.
- `release-1.0.0-host-tests.log`: audio underruns/recovery, partial writes, IQ continuity,
  RX recovery and BT guardian/controller tests.
- `release-1.0.0-oracle.log`: 4,272 native differential cases rerun for publication,
  with mocked hardware calls and a private external reference ELF.
- `release-1.0.0-apk.json`: package/version, APK checksum, signer and packaged helper checks.
- `publication-processing-source.json`: byte comparison of 14 native/DSP source files
  against the owner-tested baseline: 13 unchanged, one differs only by a dated
  modification comment; its processing code is unchanged.

The files above record automated host/build checks separately from the device check.

## Archived v20058 native-source baseline

The following results precede the public app changes:

- `source-only-build.log`: successful release compilation and APK packaging.
- `oracle-integrated.log`: 4,272 native differential cases: 2,500 decode,
  800 HDLC/CRC, 300 ring consume and 672 mocked radio cases.
- `source-*-test.log`: audio, IQ continuity, recovery and BT host tests.
- `packaged-native-verification.json`: all five packaged helpers match compiler output.
- `native-build.json`: native compiler version and output hashes.
- `source-only-signature.log`: APK signature verification.
- `apk-comparison.json`: comparison with the original APK: 423 identical ZIP entries,
  10 changed, 7 missing and 89 added. This is not a comparison of release 1.0.0.

No byte-identical APK or full-program equivalence is claimed. The owner reported
that the v20058 source-built APK works on their LG D325. That report is separate
from host tests; no independent device matrix or all-frequency validation is supplied.
