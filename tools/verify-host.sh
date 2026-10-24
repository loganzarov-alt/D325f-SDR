#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Logan Zarov
# Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
set -euo pipefail
cd "$(dirname "$0")/.."
for test in audio_sink capture_policy rx_recovery bt_guard bt_controller; do
    python3 "tests/pipeline/test_${test}.py"
done
