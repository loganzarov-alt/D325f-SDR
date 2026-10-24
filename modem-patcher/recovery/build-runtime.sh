#!/bin/sh
# Build musl from the bundled, unmodified upstream source archive.
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
TASK_CC=${CROSS_CC:-arm-linux-gnueabi-gcc}
TASK_AR=${CROSS_AR:-arm-linux-gnueabi-ar}
TASK_RANLIB=${CROSS_RANLIB:-arm-linux-gnueabi-ranlib}
ARCHIVE="$ROOT/third_party/musl-1.2.5.tar.gz"
echo 'a9a118bbe84d8764da0ea0d28b3ab3fae8477fc7e4085d90102b8596fc7c75e4  '"$ARCHIVE" | sha256sum -c -
mkdir -p "$ROOT/toolchain"
tar -xzf "$ARCHIVE" -C "$ROOT/toolchain"
cd "$ROOT/toolchain/musl-1.2.5"
./configure --target=arm-linux-musleabi --prefix="$ROOT/toolchain/musl-arm" CC="$TASK_CC" AR="$TASK_AR" RANLIB="$TASK_RANLIB" CFLAGS='-Os -march=armv7-a'
make -j2 AR="$TASK_AR" RANLIB="$TASK_RANLIB"
make install AR="$TASK_AR" RANLIB="$TASK_RANLIB"
