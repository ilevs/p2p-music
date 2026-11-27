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

# Play a track the way a user does: open a magnet, go to the player, tap the track. The album is
# already on the device, so no peers are needed: the app checks the files and plays them.
fail() {
  echo "::endgroup::"
  adb shell pidof "$package" || echo "The app is not running"
  adb logcat -d -b crash
  adb logcat -d | grep -E "AndroidRuntime|$package|libtorrent|ExoPlayer|MediaSession" | tail -60
  echo "Texts on screen:"; adb shell cat /sdcard/ui.xml | grep -o 'text="[^"]*"' | head -40
  echo "::error::$1"
  exit 1
}
echo "::group::Play a track"
here="$(dirname "$0")"
work="$(mktemp -d)"
hash="$(python3 "$here/make-test-torrent.py" "$work")"
dir="/sdcard/Android/data/$package/files/torrent"
adb shell mkdir -p "$dir/torrents" "$dir/downloads"
adb push "$work/$hash.torrent" "$dir/torrents/"
adb push "$work/data/." "$dir/downloads/"
adb shell am force-stop "$package"
adb logcat -c
adb shell am start -a android.intent.action.VIEW -d "magnet:?xt=urn:btih:$hash" "$package/.MainActivity"
sleep 10
python3 "$here/tap.py" "Плеер" || fail "No player tab"
sleep 3
adb exec-out screencap -p > screen-tracks.png
python3 "$here/tap.py" "Тишина" || fail "No track on the player screen"
sleep 10
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
