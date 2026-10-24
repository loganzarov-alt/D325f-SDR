# Code provenance

- Upstream application: RF Analyzer, Dennis Mantz,
  https://github.com/demantz/RFAnalyzer.
- LG modifications: Logan Zarov.
- Latest behavior reference: `RF-Decoder-from-LG-v20058-20261023.apk`.
- Native C reconstruction input:
  `LG-D325-lg_stream-kernel-modem-sources-20261023.tar.gz` supplied by the owner.

| Artifact | SHA-256 |
| --- | --- |
| Original reference APK | `3ecc910e44c25052d89d8f88ebfade0472d02efd1fe16873839eedee5b0b00a5` |
| Source-built APK reported working by owner | `6e8c626f8b2c2c43afcbf81b5935f4334b10fc484385c21055300502e3483abd` |
| Frozen native-source archive before GitHub documentation cleanup | `c5bd98adbaf5abe8a6d14d5fcd29727eb2dd18c913db6d784351d0099b5c19c3` |
| Original streamer | `02ba7e628d8809d3439cebeb3b1bd618d432dcd2564f73be6b8d52b16c5a90cc` |
| Recompiled streamer | `07bf1df32b2f9b16b9530b22765aee3f3bbc0315779a7f5205da3f5ab541dc1d` |

Working APK filename: `RF-Decoder-v20058-native-source.apk`.
Its v2 signing certificate SHA-256 is
`7051aee81f03d038fa4e078f70bd6b540e8a97d4d4b2a60e19ab08f5b2fe2534`.
The original certificate differs. The key is not included in this repository.

The publication revision 1.0.0 removes HackRF/RTL-SDR hardware sources, replaces
branding and adds offline licensing. File-format switching uses an explicit sample
format mapping. LG native helper C/ASM remains unchanged; DSP processing code is unchanged,
with only a dated source modification notice added.
The published **1.0.0** APK was tested on the modified LG D325 by Logan Zarov,
who confirmed it works on **24 October 2026**. Its SHA-256 is
`c6acd40b7dce5a4ebb53da4643de93bd6873fbe16d7810b7a3f09c0819f36cc7`.
The release tag `v1.0.0` identifies the corresponding application source.
The validation directory retains the earlier v20058 results and the 1.0.0
build/test records. Later documentation updates do not change the published APK.

The owner identifies the custom modem/SDR work as their project. The reconstructed
C and its transport/ASM files were traced to the supplied recovery archive.
The original binary test fixture is excluded from the public tree. No license
is asserted over Qualcomm firmware or system libraries. Historical kernel/modem
experiment archives were not published as a complete, reproducible firmware source.

## Modem preparation tools

Modem Patcher 1.1.0 / TWRP ZIPs 1.2 is a separate component under
`modem-patcher/`. Supplied source archive SHA-256:
`121b385c2a5060f9eec3f1955e223ac8549f21495d07f7dd6c5c274de43cd9ef`; its files
are listed in `modem-patcher/SOURCE-MANIFEST.sha256` and checked by CI.
The repository adds `tests/host-synthetic.py` (synthetic-image CI test) and
`LICENSING.md`; application/native source, the XOR payload and third-party
runtime files are unchanged from that archive.

| Release artifact | SHA-256 |
| --- | --- |
| `Modem-Patcher-LG-D325-v1.1.0.apk` | `e504538ef8490b8bfe149c2269f8eacb2dfa4e72679c51c8fb8a652dc5ae31cc` |
| `LG-D325-Modem-Toggle-TWRP-v1.2.zip` | `6140ae1530e5c2c433cfeb2e8cd416eeefaa66bd6008412b6f87cbaad6566d23` |
| `LG-D325-Modem-Restore-Dump-TWRP-v1.2.zip` | `09766098fb5bcc5d1d4bde8cdb8c1235c2a2cf2105851223f2795cdc386efde8` |

The patch payload (`10fc481c…7e2ba8`, 6 files, 2,672 bytes) is unchanged since
1.0.4. Applied to the stock modem partition `1d6d32af…f455`, it reproduces all 65
modem files of the image used with D325f SDR 1.0.0 (`42ecd065…227e`) byte for byte.
