#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

if [[ -z "${JAVA_HOME:-}" ]]; then
  if [[ -x "/usr/libexec/java_home" ]]; then
    JAVA_HOME="$(/usr/libexec/java_home -v 17 2>/dev/null || true)"
  fi
fi
if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "A JDK 17 installation is required. Set JAVA_HOME before running this script." >&2
  exit 1
fi

ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$ANDROID_SDK_ROOT" || ! -d "$ANDROID_SDK_ROOT/platforms/android-35" || ! -d "$ANDROID_SDK_ROOT/build-tools/35.0.0" ]]; then
  echo "An Android SDK with platform android-35 and build-tools 35.0.0 is required. Set ANDROID_SDK_ROOT before running this script." >&2
  exit 1
fi

export JAVA_HOME ANDROID_SDK_ROOT
cd "$SCRIPT_DIR"
"$SCRIPT_DIR/gradlew" --no-daemon --stacktrace assembleDebug

OUTPUT_DIR="$REPO_DIR/build/android"
mkdir -p "$OUTPUT_DIR"
cp "$SCRIPT_DIR/app/build/outputs/apk/debug/app-debug.apk" "$OUTPUT_DIR/FreeFlow-Android-debug.apk"
APK="$OUTPUT_DIR/FreeFlow-Android-debug.apk"
"$ANDROID_SDK_ROOT/build-tools/35.0.0/apksigner" verify "$APK" >/dev/null
"$ANDROID_SDK_ROOT/build-tools/35.0.0/aapt" dump badging "$APK" \
  | grep -F "package: name='com.tuanmedeiros.freeflow'" >/dev/null
echo "Built and verified $APK"
