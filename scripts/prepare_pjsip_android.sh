#!/usr/bin/env bash
set -euo pipefail

PJ_TAG="2.17"
ROOT_DIR="$(pwd)"
WORK_DIR="$ROOT_DIR/.third_party/pjproject"
OUT_DIR="$ROOT_DIR/pjsua2/generated"
NDK_VERSION="28.2.13676358"
ANDROID_API="29"

ANDROID_NDK_ROOT="${ANDROID_NDK_ROOT:-${ANDROID_HOME:?ANDROID_HOME is required}/ndk/$NDK_VERSION}"
export ANDROID_NDK_ROOT

if [[ ! -d "$ANDROID_NDK_ROOT" ]]; then
  echo "Android NDK not found at $ANDROID_NDK_ROOT" >&2
  exit 1
fi

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR/java" "$OUT_DIR/jniLibs"

if [[ ! -d "$WORK_DIR/.git" ]]; then
  rm -rf "$WORK_DIR"
  mkdir -p "$(dirname "$WORK_DIR")"
  git clone --depth 1 --branch "$PJ_TAG" https://github.com/pjsip/pjproject.git "$WORK_DIR"
fi

cat > "$WORK_DIR/pjlib/include/pj/config_site.h" <<'EOF'
#define PJ_CONFIG_ANDROID 1
#define PJMEDIA_HAS_VIDEO 0
#include <pj/config_site_sample.h>
EOF

# PJSIP 2.17 generates the Android PJSUA2 module here. The old
# java/android/app/.../jniLibs/armeabi path is not the 2.17 output layout.
PJSUA2_MAIN="$WORK_DIR/pjsip-apps/src/swig/java/android/pjsua2/src/main"
PJSUA2_JAVA="$PJSUA2_MAIN/java/org/pjsip/pjsua2"

for ABI in arm64-v8a armeabi-v7a x86_64; do
  echo "==> Building PJSIP $PJ_TAG for $ABI (API $ANDROID_API)"
  cd "$WORK_DIR"
  make distclean >/dev/null 2>&1 || true

  # NDK r27+ plus these linker/compiler flags keeps the native library usable
  # on Android 15+ devices with 16 KiB pages while preserving Android 10 minSdk.
  APP_PLATFORM="android-$ANDROID_API" \
  TARGET_ABI="$ABI" \
  CFLAGS="-D__BIONIC_NO_PAGE_SIZE_MACRO -fPIC" \
  LDFLAGS="-Wl,-z,max-page-size=16384" \
    ./configure-android --use-ndk-cflags

  # This sequence matches the official Android build guide.
  make dep
  make clean
  make -j2

  cd "$WORK_DIR/pjsip-apps/src/swig"
  make clean >/dev/null 2>&1 || true
  make -j2

  GENERATED_JNI="$PJSUA2_MAIN/jniLibs/$ABI"
  test -d "$PJSUA2_JAVA"
  test -f "$GENERATED_JNI/libpjsua2.so"

  if [[ ! -d "$OUT_DIR/java/org/pjsip/pjsua2" ]]; then
    mkdir -p "$OUT_DIR/java/org/pjsip"
    cp -R "$PJSUA2_JAVA" "$OUT_DIR/java/org/pjsip/"
  fi

  mkdir -p "$OUT_DIR/jniLibs/$ABI"
  # Copy all libraries generated for the ABI (at minimum libpjsua2 and
  # libc++_shared). This avoids hard-coding a legacy armeabi output path.
  cp "$GENERATED_JNI"/*.so "$OUT_DIR/jniLibs/$ABI/"

  test -f "$OUT_DIR/jniLibs/$ABI/libpjsua2.so"
  test -f "$OUT_DIR/jniLibs/$ABI/libc++_shared.so"
done

cd "$ROOT_DIR"
COUNT_JAVA="$(find "$OUT_DIR/java/org/pjsip/pjsua2" -type f -name '*.java' | wc -l | tr -d ' ')"
COUNT_SO="$(find "$OUT_DIR/jniLibs" -type f -name 'libpjsua2.so' | wc -l | tr -d ' ')"
COUNT_CXX="$(find "$OUT_DIR/jniLibs" -type f -name 'libc++_shared.so' | wc -l | tr -d ' ')"

echo "PJSUA2 prepared: $COUNT_JAVA Java bindings, $COUNT_SO PJSUA2 ABIs, $COUNT_CXX C++ runtimes"
test "$COUNT_JAVA" -gt 100
test "$COUNT_SO" -eq 3
test "$COUNT_CXX" -eq 3
