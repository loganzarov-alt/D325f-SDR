#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Logan Zarov
# Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
set -euo pipefail
cd "$(dirname "$0")/../.."
mkdir -p tests/pipeline/build
JDK_ROOT="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")}"
SRC=nativedsp/src/main/cpp
gcc -O3 -fPIC -c "$SRC/pffft.c" -o tests/pipeline/build/pffft.o
g++ -O3 -std=c++17 -shared -fPIC -pthread -I"$JDK_ROOT/include" -I"$JDK_ROOT/include/linux" -Itests/pipeline "$SRC/nativedsp.cpp" tests/pipeline/build/pffft.o -o tests/pipeline/build/libpipeline_test.so
javac -d tests/pipeline/build tests/pipeline/TestWaterfallMotion.java app/src/main/java/com/mantz_it/rfanalyzer/ui/WaterfallMotion.java tests/pipeline/com/mantz_it/nativedsp/NativeDsp.java tests/pipeline/TestSpectrumRaster.java tests/pipeline/TestDisplayFrameQueue.java app/src/main/java/com/mantz_it/rfanalyzer/dsp/SpectrumRaster.java app/src/main/java/com/mantz_it/rfanalyzer/dsp/DisplayFrameQueue.java
java -Djava.library.path=tests/pipeline/build -cp tests/pipeline/build com.mantz_it.nativedsp.NativeDsp
java -cp tests/pipeline/build TestSpectrumRaster
java -cp tests/pipeline/build TestDisplayFrameQueue

java -cp tests/pipeline/build TestWaterfallMotion

javac -d tests/pipeline/build tests/pipeline/TestExactBinaryReader.java app/src/main/java/com/mantz_it/rfanalyzer/source/ExactBinaryReader.java
java -cp tests/pipeline/build TestExactBinaryReader


g++ -O3 -std=c++17 -Inativedsp/src/main/cpp tests/pipeline/test_spectrum_math.cpp -o tests/pipeline/build/test_spectrum_math
tests/pipeline/build/test_spectrum_math

g++ -O3 -std=c++17 -Inativedsp/src/main/cpp tests/pipeline/test_raster_throughput.cpp -o tests/pipeline/build/test_raster_throughput
tests/pipeline/build/test_raster_throughput

python3 tests/pipeline/test_bt_guard.py
python3 tests/pipeline/test_bt_controller.py

javac -cp tests/pipeline/build -d tests/pipeline/build tests/pipeline/TestBtReceivePlan.java app/src/main/java/com/mantz_it/rfanalyzer/source/BtReceivePlan.java
java -cp tests/pipeline/build TestBtReceivePlan
python3 tests/pipeline/test_audio_sink.py

g++ -O3 -std=c++17 -Inativedsp/src/main/cpp tests/pipeline/test_bandwidth.cpp -o tests/pipeline/build/test_bandwidth
tests/pipeline/build/test_bandwidth
python3 tests/pipeline/test_focus.py

g++ -O3 -std=c++17 -Inativedsp/src/main/cpp tests/pipeline/bench_bandwidth.cpp tests/pipeline/build/pffft.o -o tests/pipeline/build/bench_bandwidth
tests/pipeline/build/bench_bandwidth

python3 tests/pipeline/test_reclaimer.py


javac -d tests/pipeline/build tests/pipeline/TestAudioHeadroom.java app/src/main/java/com/mantz_it/rfanalyzer/analyzer/AudioHeadroom.java
java -cp tests/pipeline/build TestAudioHeadroom

python3 tests/pipeline/test_capture_policy.py

g++ -O3 -std=c++17 -Inativedsp/src/main/cpp tests/pipeline/test_ci8_mixer.cpp -o tests/pipeline/build/test_ci8_mixer
tests/pipeline/build/test_ci8_mixer
if command -v arm-linux-gnueabihf-g++ >/dev/null && command -v qemu-arm >/dev/null; then
  arm-linux-gnueabihf-g++ -O3 -std=c++17 -static -mfpu=neon -Inativedsp/src/main/cpp tests/pipeline/test_ci8_mixer.cpp -o tests/pipeline/build/test_ci8_mixer_arm
  qemu-arm -cpu cortex-a7 tests/pipeline/build/test_ci8_mixer_arm
else
  echo "SKIP: ARM execution test requires arm-linux-gnueabihf-g++ and qemu-arm"
fi
python3 tests/pipeline/test_rx_recovery.py
