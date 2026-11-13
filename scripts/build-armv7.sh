#!/usr/bin/env bash
# Build APK debug YamahaArranger khusus armv7 (armeabi-v7a) di Codespaces.
#
# Pemakaian:
#   ./scripts/build-armv7.sh              # build debug armv7
#   ./scripts/build-armv7.sh --clean      # clean dulu
#   ./scripts/build-armv7.sh --refresh-bass   # unduh ulang SDK BASS/BASSMIDI
#
# Hasil: dist/YamahaArranger-armv7-debug.apk
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$PWD"

ABI="armeabi-v7a"
BASS_ANDROID_URL="${BASS_ANDROID_URL:-https://www.un4seen.com/files/bass24-android.zip}"
BASSMIDI_URL="${BASSMIDI_URL:-https://www.un4seen.com/files/bassmidi24-android.zip}"

CLEAN=0
REFRESH_BASS=0
for arg in "$@"; do
  case "$arg" in
    --clean) CLEAN=1 ;;
    --refresh-bass) REFRESH_BASS=1 ;;
    -h|--help) sed -n '2,10p' "$0"; exit 0 ;;
    *) echo "Argumen tidak dikenal: $arg" >&2; exit 2 ;;
  esac
done

export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties

if [ ! -d "$ANDROID_HOME/ndk" ]; then
  echo "Android SDK/NDK belum terpasang. Jalankan: bash .devcontainer/setup.sh" >&2
  exit 1
fi

# ---------- BASS / BASSMIDI (header + libbass*.so armeabi-v7a) ----------
HDR_DIR="app/src/main/cpp/basssdk"
LIB_DIR="app/src/main/jniLibs/$ABI"

need_bass=0
for f in "$HDR_DIR/bass.h" "$HDR_DIR/bassmidi.h" "$LIB_DIR/libbass.so" "$LIB_DIR/libbassmidi.so"; do
  [ -s "$f" ] || need_bass=1
done
[ "$REFRESH_BASS" = 1 ] && need_bass=1

if [ "$need_bass" = 1 ]; then
  echo "==> Mengunduh BASS & BASSMIDI untuk Android"
  TMP="$(mktemp -d)"
  trap 'rm -rf "$TMP"' EXIT
  mkdir -p "$TMP/bass" "$TMP/bassmidi"
  curl -fL "$BASS_ANDROID_URL"  -o "$TMP/bass.zip"
  curl -fL "$BASSMIDI_URL"      -o "$TMP/bassmidi.zip"
  unzip -q "$TMP/bass.zip"    -d "$TMP/bass"
  unzip -q "$TMP/bassmidi.zip" -d "$TMP/bassmidi"

  BASS_H="$(find "$TMP/bass" -type f -iname 'bass.h' -print -quit)"
  BASSMIDI_H="$(find "$TMP/bassmidi" -type f -iname 'bassmidi.h' -print -quit)"
  BASS_SO="$(find "$TMP/bass" -type f -path "*/$ABI/libbass.so" -print -quit)"
  BASSMIDI_SO="$(find "$TMP/bassmidi" -type f -path "*/$ABI/libbassmidi.so" -print -quit)"

  for v in BASS_H BASSMIDI_H BASS_SO BASSMIDI_SO; do
    if [ -z "${!v}" ]; then
      echo "Gagal: $v tidak ditemukan di arsip un4seen" >&2
      exit 1
    fi
  done

  mkdir -p "$HDR_DIR" "$LIB_DIR"
  cp "$BASS_H"      "$HDR_DIR/bass.h"
  cp "$BASSMIDI_H"  "$HDR_DIR/bassmidi.h"
  cp "$BASS_SO"     "$LIB_DIR/libbass.so"
  cp "$BASSMIDI_SO" "$LIB_DIR/libbassmidi.so"
else
  echo "==> BASS/BASSMIDI sudah ada, lewati unduhan (pakai --refresh-bass untuk paksa)"
fi

# ---------- Gradle ----------
if [ -x ./gradlew ]; then GRADLE=./gradlew; else GRADLE=gradle; fi

[ "$CLEAN" = 1 ] && $GRADLE clean

echo "==> Build assembleDebug (ABI: $ABI)"
$GRADLE assembleDebug -PtargetAbis="$ABI" --stacktrace

# ---------- Hasil ----------
APK="app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || { echo "APK tidak ditemukan: $APK" >&2; exit 1; }

mkdir -p dist
OUT="dist/YamahaArranger-armv7-debug.apk"
cp "$APK" "$OUT"

echo
echo "==> Isi native library di APK:"
unzip -l "$OUT" | awk '/lib\/.*\.so/ {print "   " $4}'

if unzip -l "$OUT" | grep -q 'lib/arm64-v8a/'; then
  echo "PERINGATAN: APK masih memuat arm64-v8a" >&2
fi

echo
echo "Selesai: $ROOT/$OUT ($(du -h "$OUT" | cut -f1))"
echo "Unduh dari Codespaces: klik kanan file tersebut di Explorer -> Download."
