#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
for test in audio_sink capture_policy rx_recovery bt_guard bt_controller; do
    python3 "tests/pipeline/test_${test}.py"
done
