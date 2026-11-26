# D325f Radar 0.1-alpha

Experimental standalone Android 7.1+ ARMv7 app for the modified LG D325f SDR. Two tabs: ADS-B 1090 MHz DF17/18 and AIS A+B (161.975 and 162.025 MHz). Signed CI8 7.68 MS/s, offline relative plot and IQ replay. Both AIS channels are decoded together; ADS-B and AIS are mutually exclusive. No network, no ADS-B/AIS transmit.

Build with the original project SDK/NDK requirements:
`./gradlew --no-daemon -PradarApp=true :app:assembleDebug`
Output: app/build/outputs/apk/debug/app-debug.apk. Package: io.github.loganzarov_alt.d325fradar. Standard builds without the flag remain unchanged.

Requires the existing modified hardware, kernel and modem firmware and root. Stop the original SDR app before starting Radar. Experimental, not for navigation; test on hardware. AIS uses the existing BT local oscillator below 1 GHz. IQ replay requires signed interleaved CI8, 7.68 MS/s, center 1090 or 162 MHz. Host tests use synthetic signals and reference messages; live RF performance and real AIS reception remain unverified. The schematic plot centers on received targets, not on the receiver. No geographic basemap. Debug-signed APK is for testing.

Source code is GPL-3.0-or-later; the inherited source, license notices and native helper build are retained.
