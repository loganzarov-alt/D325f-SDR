# Provenance and frozen working baseline

- Upstream application: RF Analyzer, Dennis Mantz,
  https://github.com/demantz/RFAnalyzer.
- LG modifications: Logan Zarov.
- Recovered project baseline: `loganzarov-alt/Phone-sdr`, commit
  `b75f62bb56b12bb5f6202b2615207cefd54e78ee`.
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
The frozen v20058 APK remains the owner-tested baseline, not the publication APK. The validation directory distinguishes the frozen baseline from fresh 1.0.0
host/build checks. CI runs have their own logs and artifacts; device behavior
must not be inferred from compilation or mocked tests.

The owner identifies the custom modem/SDR work as their project. The reconstructed
C and its transport/ASM files were traced to the supplied recovery archive.
The original binary test fixture is excluded from the public tree. No license
is asserted over Qualcomm firmware or system libraries. Historical kernel/modem
experiment archives were not published as a complete, reproducible firmware source.
