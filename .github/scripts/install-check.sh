#!/usr/bin/env bash
# Usage: install-check.sh <apk to check> [published apk to diagnose]
set -u
apk="$1"
published="${2:-}"
package=io.github.ilevs.p2pmusic

adb shell getprop ro.build.version.release
adb shell getprop ro.product.cpu.abilist
adb shell getconf PAGE_SIZE

if [ -n "$published" ] && [ -f "$published" ]; then
  echo "::group::Published APK"
  adb logcat -c
  if ! adb install -r "$published"; then
    adb logcat -d | grep -iE "PackageManager|PackageInstaller|NativeLibrary|NativeHelper" | tail -40
    echo "::warning::The published APK does not install on API $(adb shell getprop ro.build.version.sdk)"
  fi
  adb uninstall "$package" > /dev/null 2>&1 || true
  echo "::endgroup::"
fi

adb logcat -c
if ! adb install -r "$apk"; then
  adb logcat -d | grep -iE "PackageManager|PackageInstaller|NativeLibrary|NativeHelper" | tail -50
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
echo "Installed and running."

# Play a track the way a user does: paste a magnet, go to the player, tap the track. A seeder on
# this host is the only peer: the app fetches the metadata from it, then streams the track.
fail() {
  echo "::endgroup::"
  adb shell pidof "$package" || echo "The app is not running"
  adb logcat -d -b crash
  adb logcat -d | grep -E "AndroidRuntime|$package|libtorrent|ExoPlayer|MediaSession" | tail -60
  echo "Texts on screen:"; adb shell cat /sdcard/ui.xml | grep -o 'text="[^"]*"' | head -40
  adb shell ls -lR "/sdcard/Android/data/$package/files/torrent/downloads" | head -20
  adb shell dumpsys media_session | grep -E "state=|error" | head -10
  # Thread stacks show where playback is stuck; emulator images allow adb root.
  adb root > /dev/null 2>&1; adb wait-for-device; sleep 2
  adb shell kill -3 "$(adb shell pidof "$package")"; sleep 3
  adb shell 'cat $(ls -t /data/anr/* | head -1)' | grep -A20 -E '^"(main|ExoPlayer|Loader|pool|DefaultDispatcher)' | head -200
  echo "::error::$1"
  exit 1
}
echo "::group::Play a track"
here="$(dirname "$0")"
work="$(mktemp -d)"
hash="$(python3 "$here/make-test-torrent.py" "$work")"
python3 "$here/seed.py" "$work/$hash.torrent" "$work/data" > seeder.log 2>&1 &
trap 'kill %1 2>/dev/null; tail -5 seeder.log' EXIT
adb shell am force-stop "$package"
adb logcat -c
adb shell am start -a android.intent.action.VIEW -d "magnet:?xt=urn:btih:$hash\&x.pe=10.0.2.2:6881" "$package/.MainActivity"
sleep 10
python3 "$here/tap.py" "Плеер" || fail "No player tab"
for _ in $(seq 10); do
  sleep 3
  python3 "$here/tap.py" "Тишина" && break
done || fail "No track on the player screen"
adb exec-out screencap -p > screen-tracks.png
# The play button turns into "Пауза" only once the player has data and plays.
for _ in $(seq 10); do
  sleep 3
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null && adb shell cat /sdcard/ui.xml | grep -q 'text="Пауза"' && break
done || fail "The track does not start playing"
adb exec-out screencap -p > screen-playing.png
echo "::endgroup::"
if ! adb shell pidof "$package" > /dev/null; then
  adb logcat -d -b crash
  adb logcat -d | grep -E "AndroidRuntime|$package|ExoPlayer|MediaSession" | tail -80
  echo "::error::The app died after a track was picked"
  exit 1
fi
adb logcat -d -b crash | tail -40
adb logcat -d | grep -iE "ExoPlayerImpl|PlaybackException|TorrentDataSource" | tail -20
echo "Played a track."
