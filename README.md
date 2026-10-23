# D325f-SDR

<img src="docs/icon.svg" width="96" alt="D325f SDR icon">

Experimental SDR platform using the LG D325's built-in cellular modem for IQ
capture and radio reception. Project and modem integration: **Logan Zarov**.

This repository currently contains the Android receiver app and its userspace
native helpers. Sources: **LG modem** and **IQ file playback**. The app supports
spectrum/waterfall display, AM/NFM/WFM/USB/LSB/CW demodulation and recording.
Application code includes RF Analyzer code by Dennis Mantz and contributors;
see [licensing and attribution](LICENSING.md).

[Русский](README-RU.md) · [Build](docs/BUILD.md) · [Device preparation](docs/DEVICE-SETUP.md) ·
[Verification](validation/README.md) · [Provenance](docs/PROVENANCE.md)

## Status

- First public release: **1.0.0**, versionCode **1**, ARMv7, Android API 25+.
- The owner reported the preceding v20058 source-built APK working on their
  modified LG D325. The new publication revision needs a fresh device check.
- Custom native helpers and DSP build from C/C++/ASM; no base ELF is patched.
- The `lg_stream` C is a documented reconstruction, not the missing original C.
- **Installing the APK does not prepare a stock phone.** A complete verified
  setup procedure for a second LG D325 is still outstanding.
- The APK/streamer are not byte-identical to the original reference APK.

## Build and test

Linux x86_64, JDK 17, Python 3 and Android SDK are required:

```sh
sdkmanager "platforms;android-34" "build-tools;35.0.0" \
  "ndk;27.2.12479018" "cmake;3.22.1"
export ANDROID_HOME=/path/to/android-sdk
./gradlew --no-daemon :app:assembleRelease :app:testReleaseUnitTest
```

Output: `app/build/outputs/apk/release/app-release.apk`.
First build needs Google/Maven access. CI builds a test-signed APK and runs focused
host/unit tests. See [build and signing](docs/BUILD.md) before installing an update.

## What is included

Java/Kotlin app, C++ DSP, `lg_stream`, `lg_factory`, `lg_online`, `btcw-guard`,
`sdr-focus`, tests, build scripts and license notices. HackRF/RTL-SDR hardware
drivers are not included. Existing signed/unsigned IQ files remain readable.

Proprietary modem firmware and vendor QMI libraries are separate phone system
components. Full kernel/firmware reproduction is outside this snapshot.

## License

Combined application: **GPL-3.0-or-later**. Upstream source notices and permissive
component licenses are retained; see [LICENSING.md](LICENSING.md) and [LICENSE](LICENSE).
This independent project is not endorsed by LG, Qualcomm or upstream authors.
