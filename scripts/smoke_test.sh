#!/usr/bin/env bash
# Smoke test: install APK, launch, capture logcat for LlamaJni init.
set -e

PKG=io.kognis.tactical
APK=app/build/outputs/apk/debug/app-debug.apk
LOG=logcat_smoke.txt

if ! adb get-state >/dev/null 2>&1; then
  echo "FAIL: no device"; exit 1
fi

echo "=== install $APK ==="
adb install -r "$APK"

echo "=== clear logcat ==="
adb logcat -c

echo "=== launch app ==="
adb shell am start -n $PKG/.MainActivity

echo "=== capture logcat 60s ==="
timeout 60 adb logcat -v time \
  LlamaJni:V SovereignCore:V LlamaModelRunner:V kognis:V \
  AndroidRuntime:E *:S | tee "$LOG" || true

echo ""
echo "=== verification checks ==="
grep -E "loadModel|Model loaded|Context created|UnsatisfiedLinkError|page-size compatible" "$LOG" | head -20
echo ""
echo "Log saved: $LOG"
