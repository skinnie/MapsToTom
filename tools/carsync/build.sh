#!/bin/sh
# Builds m2tsync for the TomTom with the Android NDK's clang: build.sh <ndk root> <out>
CC="$1/toolchains/llvm/prebuilt/linux-x86_64/bin/clang"
exec "$CC" --target=armv4t-none-eabi -mcpu=arm920t -marm -Os -ffreestanding -fno-builtin -fno-stack-protector -nostdlib -static \
  -fuse-ld=lld -Wl,-N -Wl,-e,_start -Wl,--gc-sections -s -o "$2" "$(dirname "$0")/m2tsync.c"
