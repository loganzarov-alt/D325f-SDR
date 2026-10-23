# 1.0.0 — first public release (2026-10-23)

- Visible name D325f SDR, original vector icon and versionCode 1.
- Separate application ID `io.github.loganzarov_alt.d325fsdr`: installs alongside
  the previous development app; settings and recordings are not automatically imported.
- LG modem and IQ file sources only. Removed HackRF/RTL-SDR hardware drivers,
  USB discovery and the bundled HackRF AAR.
- Explicit numeric mapping for file sample formats, including legacy recordings.
- No debug/logging controls or BT/performance status-query buttons in the release UI.
- Local manual, offline license texts/notices, source-selection unit tests and CI.
- Native helper processing source is unchanged from the working reconstructed
  baseline. DSP code differs only by its dated modification notice. New APK hardware validation is still required.

Modified upstream-derived source files (including changes carried over from the
working baseline) carry dated notices; original copyright/license headers remain.
The publication date identifies this source snapshot, not the date each earlier
change was originally implemented. Compared with upstream snapshot
`bf36c841ef7cc3ef3fbc4c5dcf4fd34a9008783c`:

- `app/src/main/java/com/mantz_it/rfanalyzer/AppModule.kt`
- `app/src/main/java/com/mantz_it/rfanalyzer/RFAnalyzerApplication.kt`
- `app/src/main/java/com/mantz_it/rfanalyzer/analyzer/AnalyzerService.kt`
- `app/src/main/java/com/mantz_it/rfanalyzer/analyzer/AudioSink.java`
- `app/src/main/java/com/mantz_it/rfanalyzer/analyzer/Demodulator.kt`
- `app/src/main/java/com/mantz_it/rfanalyzer/analyzer/FftProcessor.kt`
- `app/src/main/java/com/mantz_it/rfanalyzer/analyzer/Scheduler.kt`
- `app/src/main/java/com/mantz_it/rfanalyzer/database/AppDatabase.kt`
- `app/src/main/java/com/mantz_it/rfanalyzer/database/AppStateRepository.kt`
- `app/src/main/java/com/mantz_it/rfanalyzer/dsp/ComplexFirFilter.java`
- `app/src/main/java/com/mantz_it/rfanalyzer/source/FileIQSource.java`
- `app/src/main/java/com/mantz_it/rfanalyzer/source/IQConverter.java`
- `app/src/main/java/com/mantz_it/rfanalyzer/source/IQSourceInterface.java`
- `app/src/main/java/com/mantz_it/rfanalyzer/source/Signed8BitIQConverter.java`
- `app/src/main/java/com/mantz_it/rfanalyzer/source/Unsigned8BitIQConverter.java`
- `app/src/main/java/com/mantz_it/rfanalyzer/ui/AnalyzerSurface.kt`
- `app/src/main/java/com/mantz_it/rfanalyzer/ui/MainActivity.kt`
- `app/src/main/java/com/mantz_it/rfanalyzer/ui/MainViewModel.kt`
- `app/src/main/java/com/mantz_it/rfanalyzer/ui/RFAnalyzerTheme.kt`
- `app/src/main/java/com/mantz_it/rfanalyzer/ui/composable/AboutTab.kt`
- `app/src/main/java/com/mantz_it/rfanalyzer/ui/composable/AnalyzerTabsComposable.kt`
- `app/src/main/java/com/mantz_it/rfanalyzer/ui/composable/CustomSideDrawerOverlay.kt`
- `app/src/main/java/com/mantz_it/rfanalyzer/ui/composable/DemodulationTab.kt`
- `app/src/main/java/com/mantz_it/rfanalyzer/ui/composable/DisplayTab.kt`
- `app/src/main/java/com/mantz_it/rfanalyzer/ui/composable/HelperComposables.kt`
- `app/src/main/java/com/mantz_it/rfanalyzer/ui/composable/RecordingTab.kt`
- `app/src/main/java/com/mantz_it/rfanalyzer/ui/composable/SettingsTab.kt`
- `app/src/main/java/com/mantz_it/rfanalyzer/ui/composable/SourceTab.kt`
- `app/src/main/java/com/mantz_it/rfanalyzer/ui/screens/AboutScreen.kt`
- `app/src/main/java/com/mantz_it/rfanalyzer/ui/screens/LogFileScreen.kt`
- `app/src/main/java/com/mantz_it/rfanalyzer/ui/screens/MainScreen.kt`
- `app/src/main/java/com/mantz_it/rfanalyzer/ui/screens/ManualScreen.kt`
- `app/src/main/java/com/mantz_it/rfanalyzer/ui/screens/RecordingsScreen.kt`
- `nativedsp/src/main/cpp/nativedsp.cpp`
- `nativedsp/src/main/java/com/mantz_it/nativedsp/NativeDsp.kt`
