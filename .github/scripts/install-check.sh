#!/usr/bin/env bash
# Usage: install-check.sh <apk to check> [published apk to diagnose]
set -u
apk="$1"
published="${2:-}"
package=io.github.ilevs.p2pmusic

adb shell getprop ro.build.version.release
adb shell getprop ro.product.cpu.abilist

if [ -n "$published" ] && [ -f "$published" ]; then
  echo "::group::Published APK"
  adb install -r "$published" || echo "::warning::The published APK does not install on API $(adb shell getprop ro.build.version.sdk)"
  adb uninstall "$package" > /dev/null 2>&1 || true
  echo "::endgroup::"
fi

adb logcat -c
if ! adb install -r "$apk"; then
  adb logcat -d -s PackageManager PackageInstaller | tail -50
  echo "::error::The APK does not install"
  exit 1
fi

adb shell monkey -p "$package" -c android.intent.category.LAUNCHER 1
sleep 15
if ! adb shell pidof "$package" > /dev/null; then
  adb logcat -d -b crash
  adb logcat -d | grep -E "AndroidRuntime|$package" | tail -80
  echo "::error::The app is not running 15 s after launch"
  exit 1
fi
adb logcat -d -b crash | tail -40
echo "Installed and running."
