# Licensing

Modem Patcher source, build/test scripts and documentation are part of
D325f-SDR and are licensed under **GPL-3.0-or-later**; see [LICENSE](LICENSE).
Every project source file carries an `SPDX-License-Identifier: GPL-3.0-or-later`
header. Project: Logan Zarov, 2026.

| Component | Terms | Included material |
| --- | --- | --- |
| Project Java/C code, scripts and documentation | GPL-3.0-or-later | LICENSE and complete source in this folder |
| musl 1.2.5 (statically linked into `modem-toggle`) | MIT plus the individual permissive notices | Original source archive in `third_party/`; COPYRIGHT, ARM memcpy BSD and other source notices in `licenses/` |
| GCC runtime portions | GPLv3 with GCC Runtime Library Exception 3.1, where applicable | `licenses/GCC-GPL-3.txt`, `licenses/GCC-runtime-notices.txt` |
| Proprietary LG/Qualcomm firmware | No license grant from this project | Full firmware images and vendor libraries are not distributed |

The APK and both TWRP ZIPs carry the project LICENSE and all third-party notices;
the APK shows them under **Licenses**. When redistributing binaries, keep these
notices and offer the corresponding source (this folder, or the source archive
attached to the release).

The GCC exception applies to eligible compilation processes under its stated
conditions; it is not a blanket license for all GCC components.

The XOR payload contains masks, file-relative offsets and expected hashes for
six modem files. It is applied to the user's own modem partition and does not
contain a modem image. A delta format does not by itself establish
redistribution rights or transfer ownership of proprietary firmware; this
project license only covers rights held by its contributors.

The public APK is signed with the dedicated release key
"LG D325 Modem Patcher Release". Builds made by others with `build.py` use their
own local key; installing over a differently signed APK requires uninstalling it.

References checked 2026-10-24:
- https://www.gnu.org/licenses/gcc-exception-3.1.en.html
- https://git.musl-libc.org/cgit/musl/tree/COPYRIGHT
- https://musl.libc.org/about.html
