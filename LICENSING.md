# Licensing and provenance

The combined D325f SDR Android application is distributed under **GPL-3.0-or-later**;
see `LICENSE`. RF Analyzer code remains available under its original
**GPL-2.0-or-later** notices, preserved in source files and `COPYING`. We exercise
that later-version option for the combined build, including Apache-2.0 dependencies.
This is not a claim of ownership over upstream code.

Project-specific additions and modifications supplied by Logan Zarov, including
custom userspace modem helpers and the new icon, are distributed under
GPL-3.0-or-later unless a file states different terms. Reconstructed source is
identified as such; it is not claimed to be the original missing source text.
This license does not grant rights over proprietary vendor firmware or libraries.

| Component | License / treatment | Provenance |
| --- | --- | --- |
| RF Analyzer-derived app and DSP code | GPL-2.0-or-later; combined build uses GPLv3+ | Dennis Mantz and contributors; original notices retained |
| Project-specific app changes, helper code and original vector icon | GPL-3.0-or-later | Logan Zarov project; recovered native code identified in `docs/PROVENANCE.md` |
| pffft / FFTPACK | permissive FFTPACK terms; full text in `LICENSES/PFFFT-FFTPACK.txt` | Julien Pommier; Paul Swarztrauber; UCAR/NCAR |
| Maven runtime dependencies | component-specific terms, exact versions and notices inventoried | `docs/runtime-dependencies.json`, `LICENSES/runtime-notices/` |
| NDK C++/compiler runtime portions linked into native code | LLVM Apache-2.0 WITH LLVM-exception; legacy MIT/NCSA and other notices preserved in `LICENSES/Android-NDK-LLVM-NOTICE.txt` | Exact NOTICE shipped with NDK 27.2.12479018; full toolchain notice retained, including components not necessarily linked |
| Gradle wrapper/build tooling | Apache-2.0 and tool-specific terms; tooling is not app code | Gradle wrapper, Android SDK/NDK, Kotlin/AGP |
| `tools/prepWAV.py` | unmodified upstream file without its own notice, covered by RF Analyzer's GPL-2.0-or-later grant; standalone utility, not linked into the APK | Dennis Mantz, 2015 |
| Modem Patcher project code and scripts | GPL-3.0-or-later | Separate component in `modem-patcher/`; see its LICENSING.md |
| Modem Patcher static musl/GCC runtime | MIT/BSD notices and GPLv3 with GCC Runtime Library Exception, as applicable | `modem-patcher/licenses/`; original musl source archive included |
| Kernel patch and configuration in `kernel/` | GPL-2.0-only, as the Linux kernel | Patch against LineageOS android_kernel_lge_msm8226; full text in `LICENSES/GPL-2.0.txt` |
| Photographs, IQ plots and demo video in `docs/images/` and `docs/media/` | GPL-3.0-or-later, as part of the project documentation | Logan Zarov; recorded with the app on the test phone |
| Qualcomm/LG firmware and system QMI libraries | proprietary/system components; not distributed here | supplied by the prepared phone |

## Redistribution

When distributing an APK, make the complete corresponding source for that exact
revision available alongside it, including modifications and build scripts.
Keep copyright/license notices and document changes. Do not add restrictions
inconsistent with the applicable GPL. Preserve third-party notices as well.
The generated APK contains full license texts and collected notices accessible
from About without a network connection.

## Branding and removed dependencies

RF Analyzer's upstream README excludes its name, logos and branding from its
GPL grant. The inherited launcher images and `rfanalyzer2.png` were verified
against upstream blob hashes and removed. The replacement vector icon was
created for D325f SDR. The upstream name remains only for factual attribution
and internal historical identifiers. No endorsement is claimed.
Unused vector drawables, including a YouTube logo, were removed; trademarks of
third parties are not used in the app.

HackRF/RTL-SDR device support and `hackrf_android.aar` are not included.
No libhackrf or libusb binary is packaged. Legacy file
format identifiers remain solely to read existing recordings.

The old `lg_stream` ELF is not distributed in the public tree: its exact original
C source is unavailable. It may be extracted locally from the owner's reference
APK for differential tests. Production helpers build from the provided C/ASM.
The kernel changes are published as a patch with the full configuration in
`kernel/`. No kernel image, modem firmware dump or private signing key is
included.

## Audit limits

Dependency license declarations, embedded notices, source headers, asset hashes
and the supplied provenance archive were inspected. The owner's authorship
statements and supplied archives establish the project's claimed provenance;
this review cannot independently prove authorship of every recovered line or
provide trademark/patent clearance. No full vendor firmware source is claimed.

References checked 2026-10-23:
- https://github.com/demantz/RFAnalyzer (GPL grant and branding exception)
- https://www.gnu.org/licenses/gpl-faq.html#v2v3Compatibility
- https://www.apache.org/licenses/GPL-compatibility.html
- https://www.gnu.org/licenses/gpl-3.0.html

## Modem patcher

The separate Modem Patcher component includes XOR masks and expected firmware-file
hashes, not complete proprietary firmware images. Its GPL license covers
project-controlled code; it grants no rights over LG/Qualcomm firmware.
A delta format does not itself establish redistribution permission.
Component terms and retained notices are in [modem-patcher/LICENSING.md](modem-patcher/LICENSING.md).
