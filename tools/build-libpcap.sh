#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CACHE_ROOT="${1:-$ROOT/build/i0-cache}"
VERSION="1.10.6"
EXPECTED_SOURCE_SHA256="872dd11337fe1ab02ad9d4fee047c9da244d695c6ddf34e2ebb733efd4ed8aa9"
ARCHIVE="$CACHE_ROOT/libpcap-$VERSION.tar.gz"
SOURCE="$CACHE_ROOT/libpcap-$VERSION"
BUILD="$CACHE_ROOT/libpcap-$VERSION-android-arm64"
PATCH="$ROOT/tools/patches/libpcap-$VERSION-host-null-device.patch"

ANDROID_HOME="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
NDK_HOME="${ANDROID_NDK_HOME:-${ANDROID_NDK_ROOT:-$ANDROID_HOME/ndk/29.0.14206865}}"
CMAKE_BIN="${CMAKE_BIN:-$ANDROID_HOME/cmake/3.31.6/bin/cmake}"
NINJA_BIN="${NINJA_BIN:-$ANDROID_HOME/cmake/3.31.6/bin/ninja}"
TOOLCHAIN="$NDK_HOME/build/cmake/android.toolchain.cmake"

for required in "$ANDROID_HOME" "$NDK_HOME" "$CMAKE_BIN" "$NINJA_BIN" "$TOOLCHAIN"; do
    if [[ ! -e "$required" ]]; then
        echo "Required Android build tool not found: $required" >&2
        exit 1
    fi
done
command -v curl >/dev/null
command -v tar >/dev/null
command -v flex >/dev/null
command -v bison >/dev/null

mkdir -p "$CACHE_ROOT"
if [[ ! -f "$ARCHIVE" ]]; then
    curl --fail --location --retry 3 --silent --show-error \
        "https://www.tcpdump.org/release/libpcap-$VERSION.tar.gz" \
        --output "$ARCHIVE"
fi

ACTUAL_SOURCE_SHA256="$(sha256sum "$ARCHIVE" | awk '{print $1}')"
if [[ "$ACTUAL_SOURCE_SHA256" != "$EXPECTED_SOURCE_SHA256" ]]; then
    echo "libpcap source SHA-256 mismatch: $ACTUAL_SOURCE_SHA256" >&2
    exit 1
fi

rm -rf "$SOURCE" "$BUILD"
tar -xzf "$ARCHIVE" -C "$CACHE_ROOT"
git -C "$SOURCE" apply --check "$PATCH"
git -C "$SOURCE" apply "$PATCH"

"$CMAKE_BIN" -S "$SOURCE" -B "$BUILD" -G Ninja \
    "-DCMAKE_MAKE_PROGRAM=$NINJA_BIN" \
    "-DCMAKE_TOOLCHAIN_FILE=$TOOLCHAIN" \
    -DANDROID_ABI=arm64-v8a \
    -DANDROID_PLATFORM=android-30 \
    -DBUILD_SHARED_LIBS=OFF \
    "-DLEX_EXECUTABLE=$(command -v flex)" \
    "-DYACC_EXECUTABLE=$(command -v bison)" \
    -DCMAKE_BUILD_TYPE=Release

"$CMAKE_BIN" --build "$BUILD" --target pcap_static --parallel

LIBRARY="$(find "$BUILD" -type f -name libpcap.a -print -quit)"
if [[ -z "$LIBRARY" ]]; then
    echo "libpcap.a was not produced" >&2
    exit 1
fi
if [[ "$LIBRARY" != "$BUILD/libpcap.a" ]]; then
    cp "$LIBRARY" "$BUILD/libpcap.a"
fi

echo "libpcap source sha256=$ACTUAL_SOURCE_SHA256"
echo "libpcap static library=$BUILD/libpcap.a"
