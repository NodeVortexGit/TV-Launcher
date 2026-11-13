#!/usr/bin/env bash
# Build the optimised release APK and install it: over adb if the TV is connected, otherwise over Wi-Fi.
set -euo pipefail
cd "$(dirname "$0")/.."
JAVA_HOME=${JAVA_HOME:-/opt/android-studio/jbr} ./gradlew -q assembleRelease
APK=app/build/outputs/apk/release/app-release.apk
if adb get-state >/dev/null 2>&1; then
  adb install -r "$APK"
  adb shell appops set com.nodevortex.aura REQUEST_INSTALL_PACKAGES allow   # allows later Wi-Fi updates
  echo "AOT-compiling (speed)…"; adb shell cmd package compile -m speed -f com.nodevortex.aura
else
  tools/push-update.sh "$@"
fi
