# D325f SDR 2.0.0 — personal validation build

Version name 2.0.0, version code 20000. No GitHub Release is published by the workflow.
The workflow builds and checks a temporary test-signed APK. The distributable is
re-signed outside GitHub with the private distribution key matching the 1.0.0
certificate. Neither the keystore nor its passwords belong in git or CI artifacts.

## IQ orientation

Source contains a persisted inversion switch, enabled by default, locked while
capture/recording/streaming is active. The extra user inversion is combined with
the existing BT downconversion reversal. Orientation is applied once to signed
CI8 bytes before the consumers split. DSP no longer applies another conjugation.
The native operation exchanges I and Q: `j * conjugate(z)`. This reverses frequency
without clipping the signed -128 endpoint; the constant 90-degree rotation does
not change spectral placement. File playback does not silently invert already
corrected recordings.

Host tests check known positive/negative offsets, all BT/inversion combinations,
zero-filled gaps, signed endpoints and a double application. They do not prove
the physical modem/BT orientation. Test a known transmitter both above and below
the tuned centre on the actual LG before publishing.

## One-time tasks

Automatizations stores local start time, duration, centre frequency and a snapshot
of source gain/profile/bandwidth/inversion/BT parameters. Overlaps are rejected.
The alarm uses exact wake-up scheduling and capture uses a foreground service and
partial CPU wake lock. A task missed across boot is skipped; running tasks and
thermal monitoring never resume after a reboot. Only one capture owns the modem.
If it is already occupied, the new task is marked skipped.

No file is opened before the first real IQ packet. Once recording starts, an
outage is represented by zero CI8 samples in the same file. The end time never
moves. Recovery repeats ordinary starts, using ONLINE -> FACTORY after two failed
starts. Any real packet resets the five-minute failure deadline. An unresolved
failure still powers off when its deadline expires even if the task has ended.
This unconditional failure shutdown applies only to automated capture.

After stopping and closing the source and recording, the optional reboot is
performed first. Otherwise optional temperature monitoring checks battery and
CPU sensors. A 3-degree drop on all available baseline sensors within ten minutes
disarms monitoring; lack of cooling powers off at ten minutes. Reaching 70 degrees
after capture causes immediate power-off. Temperature power-off is disabled while
the task is running. No sensors causes a reboot. Manual cancellation cancels all
subsequent power actions and thermal monitoring. Temperature readings are kept in
the bounded app-private automation-temperature.csv log.

The recording folder can be chosen from storage locations or entered explicitly.
An explicitly selected folder does not silently fall back to another device.
Automated recordings use the selected 7.68, 2.56 or 1.28 MS/s rate (native FIR
filter for decimated rates) and are registered in Recordings. Legacy manual raw
recording remains 7.68 MS/s, with the corrected orientation.

## Wi-Fi streaming

Enable streaming in Source, use TCP port 1234 (or a chosen port), then connect the
PC to the displayed LG IP on the same Wi-Fi network. Select an RTL-TCP source in
the client and 2.56 MS/s as the initial test rate. Supported rates are exactly
1.28, 2.56 and 7.68 MS/s. Other rate requests close the connection instead of
misrepresenting its sample rate. Wire format is CU8, RTL0 greeting, big-endian
frequency/rate commands. Gain is configured on the LG; unsupported tuner commands
are ignored. While streaming, local FFT, waterfall processing and recording are
not started. Streaming is not an automation target.

Protocol framing targets SDR++, SDR#, gr-osmosdr/GNU Radio and SatDump RTL-TCP
clients. End-to-end interoperability and throughput on the LG Wi-Fi have not yet
been verified. 7.68 MS/s requires 15.36 MB/s before network overhead; do not assume
the phone can sustain that rate wirelessly.

## BT and language

Default TX power is 7. Saved choices are retained. Levels 8–9 show a warning
based on the owner's measurements above 10 mW. Below 100 MHz, channels above 30
are reset to 0 before tuning; channel 30 remains valid. Task snapshots include BT
channel and power.

Automatic system language with optional persisted manual selection: English,
Russian, Spanish, Portuguese, French, German, Italian, simplified/traditional
Chinese, Japanese, Korean, Arabic and Hindi. Android RTL layout is enabled.
Technical identifiers and original third-party license texts are retained.

## Required physical checks before publication

- Upgrade installation with the same release certificate, preserving settings.
- Known-frequency IQ orientation: live, recording, TCP; direct and BT paths.
- Screen-off scheduled start and stop, no initial file on failed start, zero gap
  insertion and recovery, storage-full and selected microSD failures.
- Five-minute failure power-off, one-packet timer reset, manual cancellation,
  post-stop cooling and reboot behavior with real sensor readings.
- RTL-TCP connections, tuning and sustained rates in each target PC program.

## 2.0.1 personal update

Version code 20001. BT RX defaults to 1000 MHz; an explicitly saved upper_hz
value is retained. Automation restores the same shared default. The remaining
controls, dialogs, parameterized messages, accessibility labels and offline manual
are localized for all 13 selectable languages. Technical identifiers, user file
names, original license texts and low-level diagnostic output are not translated.
The localization catalog in tools/localization generates Android resources and
offline pages, checks locale row counts and format parameters, and is verified
for reproducibility in CI. Manual language selection also controls the manual.
