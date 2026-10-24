#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Logan Zarov
# Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
from pathlib import Path
import runpy
runpy.run_path(str(Path(__file__).resolve().parents[1]/'native/build_helpers.py'),run_name='__main__')
