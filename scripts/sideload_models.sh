#!/usr/bin/env bash
# Sideload Gemma 4 E2B LiteRT model to kognis-lite-sar sandbox after install.
# Requires: device connected via ADB, debug build installed, app launched once.
# Model path expected by FieldAssistantService: filesDir/models/<name>
set -e

PKG=io.kognis.lite.sar
TMP=/data/local/tmp

if ! adb get-state >/dev/null 2>&1; then
  echo "FAIL: no device connected. Run: adb devices"
  exit 1
fi

echo "Device: $(adb shell getprop ro.product.model)"
echo "Android: $(adb shell getprop ro.build.version.release)"

if ! adb shell pm list packages --user 0 2>/dev/null | grep -q "package:$PKG"; then
  echo "FAIL: $PKG not installed. Run: adb install -r app/build/outputs/apk/debug/app-debug.apk"
  exit 1
fi

adb shell run-as $PKG mkdir -p files/models || {
  echo "FAIL: run-as denied. App must be debuggable + launched at least once."
  exit 1
}

push_one() {
  local src="$1"
  local name=$(basename "$src")
  if [ ! -f "$src" ]; then
    echo "SKIP: $name not found at $src"
    return
  fi
  echo "PUSH: $name ($(du -h "$src" | cut -f1))"
  adb push "$src" "$TMP/$name" || { echo "FAIL push: $name"; return 1; }
  adb shell "run-as $PKG cp $TMP/$name files/models/$name" || { echo "FAIL cp: $name"; return 1; }
  adb shell "run-as $PKG chmod 600 files/models/$name"
  adb shell rm "$TMP/$name"
  echo "OK: $name"
}

# Gemma 4 E2B LiteRT bundle — the only supported model in Kognis Lite SAR
# Download from: https://huggingface.co/google/gemma-4-2b-it
# Convert with: https://github.com/google-ai-edge/LiteRT-LM
push_one "${1:-./gemma-4-E2B-it.litertlm}"

echo ""
echo "--- Models on device ---"
adb shell run-as $PKG ls -lh files/models/
