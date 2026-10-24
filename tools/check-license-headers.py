#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 Logan Zarov
# Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
"""Fail if a tracked source file carries no license notice.

Accepted: an SPDX identifier, or a retained upstream RF Analyzer GPL notice
(Dennis Mantz). Third-party trees with their own notices are skipped.
"""
import re
import subprocess
import sys

SOURCE = re.compile(r'\.(java|kt|kts|c|cpp|h|py|sh|S)$')
SKIP = ('gradle/', 'gradlew', 'LICENSES/', 'modem-patcher/third_party/',
        'modem-patcher/licenses/', 'nativedsp/src/main/cpp/pffft.')
# Unmodified upstream files that never had a notice; covered by RF Analyzer's
# GPL-2.0-or-later grant (see LICENSING.md).
UPSTREAM_UNMODIFIED = {'tools/prepWAV.py'}
NOTICE = re.compile(r'SPDX-License-Identifier:|GNU General Public License|Copyright \(C\) 20\d\d Dennis Mantz')

files = subprocess.check_output(['git', 'ls-files'], text=True).split()
missing = []
for name in files:
    if not SOURCE.search(name) or name.startswith(SKIP) or name.endswith('.gradle.kts') or name in UPSTREAM_UNMODIFIED:
        continue
    with open(name, encoding='utf-8', errors='ignore') as f:
        head = f.read(4000)
    if not NOTICE.search(head):
        missing.append(name)
if missing:
    print('Source files without a license notice:')
    print('\n'.join('  ' + m for m in missing))
    sys.exit(1)
print('License notices present in all tracked source files')
