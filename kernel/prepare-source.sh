#!/bin/sh
# SPDX-License-Identifier: GPL-2.0-only
# Copyright (C) 2026 Logan Zarov
# Part of D325f SDR: https://github.com/loganzarov-alt/D325f-SDR
#
# Fetch the LineageOS kernel at the base commit, apply the D325f SDR patch
# and install the kernel configuration. Usage: sh prepare-source.sh [DIR]
set -eu
HERE=$(cd "$(dirname "$0")" && pwd)
DIR=${1:-android_kernel_lge_msm8226}
BASE=bf756279493c2cc4656b784afa29822b30838e98
if [ -e "$DIR" ]; then
    echo "$DIR already exists; choose another directory" >&2
    exit 1
fi
mkdir "$DIR"
cd "$DIR"
git init -q
git remote add origin https://github.com/LineageOS/android_kernel_lge_msm8226.git
git fetch --depth 1 origin "$BASE"
git checkout -q FETCH_HEAD
patch -p1 --forward < "$HERE/d325f-kernel.patch"
cp "$HERE/d325f-kernel.config" .config
echo "Patched source ready in $DIR (base $BASE)."
