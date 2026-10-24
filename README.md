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

## Release and requirements

**1.0.0** is the first public release: versionCode **1**, ARMv7, Android 7.1+
(API 25). Logan Zarov tested the published APK on the modified LG D325 and
confirmed it works on **24 October 2026**.

Modem reception requires root access and a prepared phone with the matching
kernel, modem/IQ configuration and vendor QMI libraries. Installing the APK alone
does not prepare a stock phone. See [device setup](docs/DEVICE-SETUP.md) and the
[hardware photographs with B8/B1 identification](docs/HARDWARE.md).

## Modem preparation tools

[Modem Patcher 1.1.0 and TWRP ZIPs 1.2](modem-patcher/README.md) apply and reverse
the modem patch on the phone's own modem partition. They save a verified dump
before the first write and can restore from it. [Downloads](https://github.com/loganzarov-alt/D325f-SDR/releases/tag/modem-patcher-v1.1.0).
Logan Zarov reported that the tools work on his LG D325 on 24 October 2026;
no second phone has been tested. They do not install a kernel or replace the
hardware setup.

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
