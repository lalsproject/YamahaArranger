#!/usr/bin/env bash
# Dijalankan otomatis oleh Codespaces (postCreateCommand).
# Memasang Android SDK + NDK + CMake sesuai versi di app/build.gradle.kts.
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"
NDK_VERSION="${ANDROID_NDK_VERSION:-26.3.11579264}"
CMDLINE_TOOLS_ZIP="commandlinetools-linux-11076708_latest.zip"

echo "==> Paket sistem"
sudo apt-get update -qq
sudo apt-get install -y -qq unzip curl zip ninja-build >/dev/null

if [ ! -x "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" ]; then
  echo "==> Mengunduh Android command-line tools"
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  curl -fL "https://dl.google.com/android/repository/$CMDLINE_TOOLS_ZIP" -o /tmp/cmdline-tools.zip
  unzip -q /tmp/cmdline-tools.zip -d /tmp/cmdline-tools-extract
  rm -rf "$ANDROID_HOME/cmdline-tools/latest"
  mv /tmp/cmdline-tools-extract/cmdline-tools "$ANDROID_HOME/cmdline-tools/latest"
  rm -rf /tmp/cmdline-tools.zip /tmp/cmdline-tools-extract
fi

SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"

echo "==> Menerima lisensi SDK"
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true

echo "==> Memasang platform, build-tools, CMake, dan NDK $NDK_VERSION"
"$SDKMANAGER" --install \
  "platform-tools" \
  "platforms;android-34" \
  "build-tools;34.0.0" \
  "cmake;3.22.1" \
  "ndk;${NDK_VERSION}" >/dev/null

# Gradle butuh tahu lokasi SDK
echo "sdk.dir=$ANDROID_HOME" > local.properties

# Env untuk terminal baru (idempotent)
if ! grep -q "YamahaArranger android env" "$HOME/.bashrc" 2>/dev/null; then
  cat >> "$HOME/.bashrc" << RC

# YamahaArranger android env
export ANDROID_HOME="$ANDROID_HOME"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="\$PATH:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools"
RC
fi

chmod +x scripts/*.sh 2>/dev/null || true

echo
echo "==> Selesai. Build armv7 dengan:  ./scripts/build-armv7.sh"
