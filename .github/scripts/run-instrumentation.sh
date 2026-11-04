#!/bin/sh
set -u

DIAGNOSTICS_DIR="app/build/ci-diagnostics"
mkdir -p "$DIAGNOSTICS_DIR"

collect_diagnostics() {
  suffix="$1"
  adb shell getprop > "$DIAGNOSTICS_DIR/getprop-$suffix.txt" 2>&1 || true
  adb shell dumpsys -l > "$DIAGNOSTICS_DIR/services-$suffix.txt" 2>&1 || true
  adb logcat -d -v threadtime > "$DIAGNOSTICS_DIR/logcat-$suffix.txt" 2>&1 || true
  adb shell dumpsys activity processes > "$DIAGNOSTICS_DIR/activity-processes-$suffix.txt" 2>&1 || true
  adb shell dumpsys package > "$DIAGNOSTICS_DIR/package-manager-$suffix.txt" 2>&1 || true
}

echo "Waiting for Android boot completion..."
adb wait-for-device
boot_start=$(date +%s)
while :; do
  boot_completed=$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r' || true)
  [ "$boot_completed" = "1" ] && break
  now=$(date +%s)
  if [ $((now - boot_start)) -ge 600 ]; then
    echo "::error::Android emulator did not complete boot within 600 seconds."
    collect_diagnostics "boot-timeout"
    exit 1
  fi
  sleep 5
done

echo "Waiting for PackageManager and ActivityManager services..."
service_start=$(date +%s)
while :; do
  if adb shell cmd package list packages 2>/dev/null | grep -q '^package:' &&
     adb shell dumpsys activity services >/dev/null 2>&1; then
    break
  fi
  now=$(date +%s)
  if [ $((now - service_start)) -ge 300 ]; then
    echo "::error::Android package/activity services did not become responsive."
    collect_diagnostics "service-timeout"
    exit 1
  fi
  sleep 5
done

echo "Android boot and core package services are ready."
gradle connectedDebugAndroidTest
test_status=$?
collect_diagnostics "after-tests"
exit "$test_status"
