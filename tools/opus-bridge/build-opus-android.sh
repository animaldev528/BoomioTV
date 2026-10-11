#!/bin/bash
# Build libboomioopus.so for every ABI the two clients ship.
#
# libopus (reference implementation, BSD-3) is compiled to a static archive per
# ABI, then linked together with our JNI bridge into ONE shared object that
# contains both. Neither client needs libc++_shared: libopus and the bridge are
# both plain C.
#
# Run this on the build box (.56). It is reproducible: fixed libopus version,
# fixed NDK revision, no downloads inside the build.
#
# Usage:  bash build-opus-android.sh [outdir]
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
OUT="${1:-$HERE/out}"

SDK=/home/kyle/Android/Sdk
NDK="$SDK/ndk/29.0.14206865"
CMAKE="$SDK/cmake/3.22.1/bin/cmake"
NINJA="$SDK/cmake/3.22.1/bin/ninja"
TOOLCHAIN="$NDK/build/cmake/android.toolchain.cmake"

OPUS_VER=1.5.2
TARBALL="$HERE/opus-${OPUS_VER}.tar.gz"
SRC="$HERE/opus-${OPUS_VER}"

ANDROID_PLATFORM=android-24          # matches both clients' minSdk
MAX_PAGE_SIZE=16384                  # 16 KB page devices (Android 15+)

for p in "$NDK" "$CMAKE" "$TOOLCHAIN" "$TARBALL"; do
  [ -e "$p" ] || { echo "FATAL: missing $p" >&2; exit 1; }
done

echo "=== libopus $OPUS_VER -> $OUT ==="
mkdir -p "$OUT"

if [ ! -d "$SRC" ]; then
  echo "--- extracting (untrusted source; built, never executed) ---"
  tar xzf "$TARBALL" -C "$HERE"
fi

# ABI table: <android abi> <clang wrapper prefix>
ABIS="arm64-v8a:aarch64-linux-android armeabi-v7a:armv7a-linux-androideabi x86:i686-linux-android x86_64:x86_64-linux-android"

for entry in $ABIS; do
  ABI="${entry%%:*}"
  TRIPLE="${entry##*:}"
  BLD="$HERE/build-$ABI"
  CC="$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/${TRIPLE}${ANDROID_PLATFORM#android-}-clang"

  echo
  echo "================ $ABI ================"

  if [ ! -f "$BLD/CMakeCache.txt" ]; then
    "$CMAKE" -S "$SRC" -B "$BLD" -G Ninja \
      -DCMAKE_MAKE_PROGRAM="$NINJA" \
      -DCMAKE_TOOLCHAIN_FILE="$TOOLCHAIN" \
      -DANDROID_ABI="$ABI" \
      -DANDROID_PLATFORM="$ANDROID_PLATFORM" \
      -DCMAKE_BUILD_TYPE=Release \
      -DCMAKE_POSITION_INDEPENDENT_CODE=ON \
      -DOPUS_BUILD_SHARED_LIBRARY=OFF \
      -DOPUS_BUILD_TESTING=OFF \
      -DOPUS_BUILD_PROGRAMS=OFF \
      -DOPUS_FIXED_POINT=OFF \
      -DOPUS_STATIC_RUNTIME=OFF \
      > "$HERE/cmake-$ABI.log" 2>&1 \
      || { echo "configure FAILED for $ABI -- see $HERE/cmake-$ABI.log"; tail -30 "$HERE/cmake-$ABI.log"; exit 1; }
  fi

  "$CMAKE" --build "$BLD" --target opus -j "$(nproc)" \
    > "$HERE/make-$ABI.log" 2>&1 \
    || { echo "build FAILED for $ABI -- see $HERE/make-$ABI.log"; tail -30 "$HERE/make-$ABI.log"; exit 1; }

  LIBOPUS_A="$(find "$BLD" -name 'libopus.a' -print -quit)"
  [ -n "$LIBOPUS_A" ] || { echo "FATAL: libopus.a not found under $BLD"; exit 1; }
  echo "  libopus.a: $(stat -c %s "$LIBOPUS_A") bytes"

  [ -x "$CC" ] || { echo "FATAL: no compiler at $CC"; exit 1; }

  mkdir -p "$OUT/$ABI"

  # --- -lm IS LOAD-BEARING: do not read it as redundant and delete it -----------------
  # bionic folded the math functions into libc.so for years, so a .so calling exp()/
  # cos()/log() with no libm in DT_NEEDED still loaded. Android 15 moved them back out
  # into libm.so, and on API 35+ that same .so now dies at dlopen:
  #   "dlopen failed: cannot locate symbol "exp" referenced by .../libboomioopus.so"
  # The only symptom is a bare UnsatisfiedLinkError from the first JNI call, because
  # System.loadLibrary's throwable is swallowed by the caller.
  # Measured 2026-10-11: the phone (Android 16) could not load the arm64 slice while the
  # Shield (Android 11) loaded its byte-identical-in-spirit v7a twin fine -- one ABI on
  # two OS releases, which is what made it look ABI-specific. Linking libm.so is harmless
  # on every older release: the stub exists and the symbols still resolve through libc.
  "$CC" -shared -O2 -fPIC -fvisibility=hidden \
    -DNDEBUG \
    -I"$SRC/include" \
    -o "$OUT/$ABI/libboomioopus.so" \
    "$HERE/jni_bridge.c" \
    "$LIBOPUS_A" \
    -llog \
    -lm \
    -Wl,--gc-sections \
    -Wl,-z,max-page-size=$MAX_PAGE_SIZE \
    -Wl,-z,relro,-z,now \
    -Wl,--build-id=sha1

  echo "  libboomioopus.so: $(stat -c %s "$OUT/$ABI/libboomioopus.so") bytes"
done

echo
echo "=== result ==="
for entry in $ABIS; do
  ABI="${entry%%:*}"
  f="$OUT/$ABI/libboomioopus.so"
  printf '  %-14s %9s bytes  %s\n' "$ABI" "$(stat -c %s "$f")" \
    "$(readelf -h "$f" | awk -F: '/Machine/{gsub(/^ +/,"",$2); print $2}')"
done

echo
echo "=== DT_NEEDED (libm.so MUST be here or API 35+ cannot dlopen this) ==="
if readelf -dW "$OUT/arm64-v8a/libboomioopus.so" | command grep -q 'NEEDED.*libm\.so'; then
  echo "  libm.so present -- OK"
else
  echo "  FATAL: libm.so missing from DT_NEEDED; the client will fail to load this." >&2
  exit 1
fi

echo
echo "=== exported JNI symbols (arm64-v8a) ==="
"$NDK/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-nm" -D --defined-only \
  "$OUT/arm64-v8a/libboomioopus.so" 2>/dev/null | command grep -i 'Java_com_boomio' || echo "  NONE -- symbol export is broken"
