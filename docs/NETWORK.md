# Network reception — 2.0.2 personal test build

Two installable applications:
- LG: existing D325f SDR application, same application ID and release certificate; local reception, recordings and automations remain available. Streaming exclusively owns the modem.
- Pixel 4: D325f SDR Client, separate application ID, ARM64. Network reception, spectrum/waterfall, demodulation and CU8 recording. No root or LG modem control. LG automation components are disabled in its manifest.

## Connecting

Put the LG and client on the same reachable network. On LG enable streaming in Source, choose port 1234 and start the listener. On Pixel choose RTL-TCP, enter the LG Wi-Fi address and matching port, choose 1.024 MSps, tune and start. A 1.024 MSps CU8 stream needs 16.384 Mbit/s before network overhead; 2.56 MSps needs 40.96 Mbit/s. Higher rates depend on actual LG CPU/Wi-Fi throughput and have not been measured on hardware.

| Program | Network source to select | Evidence |
|---|---|---|
| SDR++ | RTL-TCP, not SDR++ Server | Upstream RTL-TCP client source |
| RF Analyzer | External Server (rtl_tcp) | Official setup and SDR-source manuals |
| SDRangel | Remote TCP Input, RTL0 | Official plugin documentation |
| SatDump | RTL-TCP | Upstream rtltcp_support plugin |
| GNU Radio / Gqrx | gr-osmosdr rtl_tcp=host:port | Upstream rtl_tcp source |
| SDR# | RTL-SDR TCP | Airspy official SDRSharp guide |

These are clients of **one implemented protocol, rtl_tcp**, not six different protocols. Native SDR++ Server, SpyServer, SoapyRemote, USB RTL-SDR driver emulation and SDRangel SDRA extensions are not implemented. App-to-app hardware tests with these programs are still required; protocol tests do not prove every program version works.

## Protocol and controls

The server sends the 12-byte RTL0 greeting before modem initialization, then interleaved CU8. The virtual tuner reports R820T (type 5, 29 gain entries) for clients that require a known gain table; the physical receiver remains the LG modem. Startup commands are collected before tuning. Client requests from 250000 to 7680000 samples/s are converted with a stateful rational FIR; native LG sample rate remains 7680000.

Implemented commands: frequency (0x01), rate (0x02), manual/automatic gain (0x03), software gain in tenths of dB (0x04), signed frequency correction in ppm (0x05), software IF gain (0x06, latest stage request), software AGC (0x08), gain index (0x0d). Gain is digital, not a calibrated analog LNA setting; it can clip and cannot recover lost RF sensitivity. Hardware LG LNA/DVGA remain configured on LG.

Test-mode, direct sampling, offset-tuning, crystal and bias-tee commands have no matching hardware implementation. They are tolerated and logged as unsupported, not represented as operational. Unknown extension commands are also logged. The protocol has no standard command acknowledgement.

Modem loss attempts capture recovery for up to 60 seconds while preserving the connection; network mode never powers off the phone. Connection/command errors are retained beside the listener status; diagnostics are in the private network.log (bounded to approximately 256 KB). On disconnect the modem is closed and the listener accepts the next connection. The first real IQ block gates client readiness, so slow FTM startup does not appear as an empty running spectrum.

## Validation scope

Host tests cover exact sample counts, arbitrary packet boundaries, anti-alias rejection, CU8 zero and signed endpoints, fragmented greetings and commands, signed ppm/gain, rate validation and a paired TCP client/server control exchange with reconnect. CI compiles both Android ABIs. Actual FTM acquisition, sustained Wi-Fi throughput, screen-off behavior and audio on LG/Pixel require the devices.

Sources: https://github.com/osmocom/rtl-sdr/blob/master/src/rtl_tcp.c ; https://github.com/AlexandreRouma/SDRPlusPlus/tree/master/source_modules/rtl_tcp_source ; https://demantz.github.io/RFAnalyzer/setup.html ; https://demantz.github.io/RFAnalyzer/sdr-source.html ; https://github.com/f4exb/sdrangel/blob/master/plugins/samplesource/remotetcpinput/readme.md ; https://github.com/SatDump/SatDump/tree/master/plugins/sdr_sources/rtltcp_support ; https://github.com/osmocom/gr-osmosdr/tree/master/lib/rtl_tcp ; https://airspy.com/downloads/SDRSharp_The_Guide_v3.0_ENG.pdf
