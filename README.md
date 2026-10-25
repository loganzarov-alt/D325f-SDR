# D325f-SDR

<img src="docs/icon.svg" width="96" alt="D325f SDR icon">

Experimental SDR receiver using the LG D325's built-in cellular modem.
Project and modem integration: **Logan Zarov**.

[Русский](README-RU.md) · [Download 1.0.0](https://github.com/loganzarov-alt/D325f-SDR/releases/tag/v1.0.0) ·
[Build](docs/BUILD.md) · [Device setup](docs/DEVICE-SETUP.md) · [Hardware / B8 and B1](docs/HARDWARE.md)

## Features

- LG modem input and IQ file playback.
- Real-time spectrum and waterfall.
- AM, NFM, WFM, USB, LSB and CW demodulation.
- IQ and demodulated audio recording.

7.68 MS/s CI8 IQ (about 5 MHz wide) straight from the modem's baseband, tuned by
a patch to the modem firmware; below 800 MHz the phone's Bluetooth transmitter
serves as the local oscillator. See [how it works](docs/HOW-IT-WORKS.md) for the
signal path, specifications and limitations.

## Demo

[![FM band, 14.970 MHz shortwave voice, 8.035 MHz and a 1875 MHz downlink on the LG D325](docs/images/demo-poster.jpg)](https://github.com/loganzarov-alt/D325f-SDR/raw/refs/heads/main/docs/media/d325f-sdr-demo.mp4)

[Video, 36 s](https://github.com/loganzarov-alt/D325f-SDR/raw/refs/heads/main/docs/media/d325f-sdr-demo.mp4): FM broadcast band, 14.970 MHz shortwave
voice, 8.035 MHz and a 1875 MHz base-station downlink (1800 MHz band), all received by the phone's own
modem. IQ recordings from the phone (FM stations with stereo pilot and RDS,
an analog TV carrier at 551.25 MHz, shortwave AM) are analysed in
[how it works](docs/HOW-IT-WORKS.md#recorded-examples).
[Download the three original IQ recordings](https://github.com/loganzarov-alt/D325f-SDR/releases/tag/iq-samples-2026-10-23) (signed CI8, 7.68 MS/s; SHA-256 included).

## Getting started

Tested setup: an LG D325 (`w5ds`) with LineageOS 14.1, TWRP and root, stock
baseband M8610B-AAAANAZM-1.0.6047. Step-by-step guide:
[device setup](docs/DEVICE-SETUP.md).

1. **Hardware**: the B8/B1 bypass wires are brought out to the 3.5 mm jack and an
   antenna is connected through an SMA adapter ([hardware](docs/HARDWARE.md)).
2. **Kernel**: flash [boot.img](https://github.com/loganzarov-alt/D325f-SDR/releases/download/kernel-6-source/boot.img) from TWRP
   (kernel build #6, allocates SMEM item 471; [source](kernel/README.md)).
3. **Modem**: with [Modem Patcher 1.1.0](https://github.com/loganzarov-alt/D325f-SDR/releases/tag/modem-patcher-v1.1.0) save a dump,
   copy it to a PC, apply the patch and reboot ([details](modem-patcher/README.md)).
4. **App**: install [D325f SDR 1.0.0](https://github.com/loganzarov-alt/D325f-SDR/releases/tag/v1.0.0) (versionCode 1, ARMv7,
   Android 7.1+) and grant root.

Logan Zarov confirmed on 24 October 2026 that the app and the modem tools work
on the tested phone. The procedure has not yet been repeated on a second phone.
The software steps are reversible: Boot backup, Restore stock or the saved modem dump.

## Build

Linux x86_64, JDK 17, Python 3 and Android SDK:

```sh
sdkmanager "platforms;android-34" "build-tools;35.0.0" \
  "ndk;27.2.12479018" "cmake;3.22.1"
export ANDROID_HOME=/path/to/android-sdk
./gradlew --no-daemon :app:assembleRelease :app:testReleaseUnitTest
```

Output: `app/build/outputs/apk/release/app-release.apk`.
The app, DSP and custom native helpers build from Java/Kotlin/C/C++/assembly
sources. See [build and signing](docs/BUILD.md) and [verification](validation/README.md).
Proprietary phone firmware and vendor libraries are not included.

## Credits and license

The application includes RF Analyzer code by **Dennis Mantz and contributors**.
AI tools assisted with code development and documentation.

Combined application: **GPL-3.0-or-later**. Original copyright notices and
third-party licenses are retained in [LICENSING.md](LICENSING.md), [LICENSE](LICENSE)
and the app's offline notices. See [code provenance](docs/PROVENANCE.md).
This independent project is not endorsed by LG, Qualcomm or the upstream authors.
