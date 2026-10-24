# Licensing

Project-specific patcher source, build/test scripts and documentation are
published as part of D325f-SDR under **GPL-3.0-or-later**; see [LICENSE](LICENSE).
Project: Logan Zarov, 2026.

| Component | Terms | Included material |
| --- | --- | --- |
| Project Java/C code, scripts and documentation | GPL-3.0-or-later | LICENSE and complete source |
| musl 1.2.5 | MIT plus the individual permissive notices | Original source archive in third_party; COPYRIGHT, ARM memcpy BSD and other source notices in licenses |
| GCC runtime portions | GPLv3 with GCC Runtime Library Exception 3.1, where applicable | GCC-GPL-3.txt and GCC-runtime-notices.txt |
| Proprietary LG/Qualcomm firmware | No license grant from this project | Full firmware images and vendor libraries are not distributed |

The GCC exception applies to eligible compilation processes under its stated
conditions; it is not a blanket license for all GCC components. The supplied
build uses ARM GCC with musl. Keep the bundled third-party notices when
redistributing binaries or source.

The XOR payload contains masks, file-relative offsets and expected hashes for
six modem files. A delta format does not by itself establish redistribution
rights or transfer ownership of proprietary firmware. This project license only
covers rights held by its contributors.

The attached Android APK and TWRP ZIP retain their original runtime notices.
The source publication adds this explicit project license; it does not change
their code, signatures or payload. The original archive left the project-code
license unspecified; this repository publication uses D325f-SDR's GPL-3.0-or-later.

References checked 2026-10-24:
- https://www.gnu.org/licenses/gcc-exception-3.1.en.html
- https://git.musl-libc.org/cgit/musl/tree/COPYRIGHT
- https://musl.libc.org/about.html
