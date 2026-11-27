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
  # The head of a playback error, which the tail above cuts off.
  adb logcat -d -s ExoPlayerImplInternal:E | grep -E -A3 "Playback error|Caused by" | grep -v "^--" | head -40
  echo "Texts on screen:"; adb shell cat /sdcard/ui.xml | grep -o 'text="[^"]*"' | head -40
  # The stats line (peers, trackers, DHT) is at the end of the track list, below every file.
  for _ in $(seq 30); do adb shell input swipe 160 500 160 100 100; done; sleep 1
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null; adb shell cat /sdcard/ui.xml | grep -o 'text="Старт[^"]*"'
  adb shell ls -lR "/sdcard/Android/data/$package/files/torrent/downloads" | head -20
  adb shell dumpsys media_session | grep -E "state=|error" | head -10
  # Thread stacks show where playback is stuck; emulator images allow adb root.
  adb root > /dev/null 2>&1; adb wait-for-device; sleep 2
  adb shell kill -3 "$(adb shell pidof "$package")"; sleep 3
  adb shell 'cat $(ls -t /data/anr/* | head -1)' | grep -A20 -E '^"(main|ExoPlayer|Loader|pool|DefaultDispatcher)' | head -300
  echo "::error::$1"
  exit 1
}
# Opens <magnet>, goes to the player, taps the track named <track> and waits until it plays:
# the play button is described as "Пауза" only once the player has data and plays.
play_track() {
  adb shell am force-stop "$package"
  adb logcat -c
  adb shell am start -a android.intent.action.VIEW -d "$1" "$package/.MainActivity"
  sleep 10
  python3 "$here/tap.py" "Плеер" || fail "No player tab"
  for _ in $(seq 10); do
    sleep 3
    python3 "$here/tap.py" "$2" && break
  done || fail "No track $2 on the player screen"
  for _ in $(seq "$3"); do
    sleep 3
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null && adb shell cat /sdcard/ui.xml | grep -q 'content-desc="Пауза"' && break
  done || fail "The track does not start playing"
}

# Taps the track named <track> in the open player and waits until a track of <length> (m:ss) plays.
switch_track() {
  python3 "$here/tap.py" "$1" || fail "No track $1 on the player screen"
  for _ in $(seq 20); do
    sleep 3
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null
    adb shell cat /sdcard/ui.xml | grep -q "text=\"[0-9:]* / $2\"" &&
      adb shell cat /sdcard/ui.xml | grep -q 'content-desc="Пауза"' && break
  done || fail "Track $1 does not start playing after another track"
}

here="$(dirname "$0")"
echo "::group::Play a track from a local seeder"
work="$(mktemp -d)"
hash="$(python3 "$here/make-test-torrent.py" "$work")"
python3 "$here/seed.py" "$work/$hash.torrent" "$work/data" > seeder.log 2>&1 &
# adb carries the peer connection; the emulator's NAT left dead connections open on the host side.
adb reverse tcp:6882 tcp:6882
trap 'kill %1 2>/dev/null; tail -5 seeder.log' EXIT
play_track "magnet:?xt=urn:btih:$hash\&x.pe=127.0.0.1:6882" "Тишина" 10
adb exec-out screencap -p > screen-playing.png
# Then the next tracks: each has its own length, so the position line shows which one plays. The first track
# downloads in 15 s at the seeder's rate; once it is complete the torrent counts as finished, as with a short
# track on a phone, and seeds drop a finished peer.
sleep 30
switch_track "Ветер" "1:30"
switch_track "Дождь" "2:00"
adb exec-out screencap -p > screen-third.png
echo "::endgroup::"

# Ubuntu's swarm: thousands of real peers from the internet, legal. The magnet carries no metadata, so the
# file list (an ISO, no audio) appears only after peers sent it; then the stats line counts the peers.
# A free music torrent with real peers was not found: archive.org albums have only web seeds.
echo "::group::Fetch metadata from a live swarm"
page=https://releases.ubuntu.com/26.04/
name="$(curl -sSfL --retry 3 "$page" | grep -o 'ubuntu-[0-9.]*-desktop-amd64\.iso\.torrent' | sort -V | tail -1)"
curl -sSfL --retry 3 -o "$work/live.torrent" "$page$name"
live_hash="$(python3 -c 'import sys, libtorrent as lt; print(lt.torrent_info(sys.argv[1]).info_hash())' "$work/live.torrent")"
echo "Torrent $name $live_hash"
adb shell am force-stop "$package"
adb logcat -c
adb shell am start -a android.intent.action.VIEW \
  -d "magnet:?xt=urn:btih:$live_hash\&tr=https%3A%2F%2Ftorrent.ubuntu.com%2Fannounce" "$package/.MainActivity"
sleep 10
python3 "$here/tap.py" "Плеер" || fail "No player tab"
for _ in $(seq 30); do
  sleep 3
  # The stats line sits below the tracks and the magnet field and can be off a small screen.
  adb shell input swipe 160 500 160 100 100
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  stats="$(adb shell cat /sdcard/ui.xml | grep -o 'text="Старт[^"]*"')"
  peers="$(echo "$stats" | grep -o 'Пиры: [0-9]*' | grep -o '[0-9]*')"
  adb shell cat /sdcard/ui.xml | grep -q 'text="В раздаче нет аудиофайлов"' && [ "${peers:-0}" -ge 5 ] && break
done || fail "No metadata or fewer than 5 peers from the live swarm"
echo "$stats"
adb exec-out screencap -p > screen-live.png
echo "::endgroup::"
# The user's path on the real sites: find an album in the catalog, tap it, and the app picks a release on
# the trackers and plays it. Downloads part of a commercial album, which ilia allowed on 2026-11-27.
echo "::group::Find an album and play it"
adb shell am force-stop "$package"
adb logcat -c
# Seconds since $2 for the step $1 of the user's path, into the job summary. Rough: a screen dump takes seconds.
metric() {
  echo "METRIC $1=$(($(date +%s) - $2))" | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
}
launched=$(date +%s)
adb shell monkey -p "$package" -c android.intent.category.LAUNCHER 1
for _ in $(seq 20); do
  python3 "$here/tap.py" "Поиск" && break
  sleep 1
done || fail "No search tab"
metric launch_to_ui_s "$launched"
python3 "$here/tap.py" "Артист, альбом" || fail "No search field"
adb shell input text "Pink%sFloyd%sThe%sDark%sSide%sof%sthe%sMoon"
python3 "$here/tap.py" "Найти" || fail "No search button"
searched=$(date +%s)
for _ in $(seq 20); do
  sleep 1
  # The row's "artist · year" line: the catalog also lists tributes named after the album.
  python3 "$here/tap.py" "Pink Floyd · 1973" && break
done || fail "The catalog shows no Pink Floyd album of 1973"
metric search_to_albums_s "$searched"
tapped=$(date +%s)
playing=
for _ in $(seq 90); do
  sleep 1
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  if adb shell cat /sdcard/ui.xml | grep -q 'content-desc="Пауза"'; then
    playing=yes
    break
  fi
  # Playback starts on the player tab; an error from the trackers stays on the search screen.
  python3 "$here/tap.py" "Плеер" > /dev/null || true
done
# Not "done || fail": a loop that runs out ends with the status of its last command, which is 0 here.
if [ -z "$playing" ]; then
  adb exec-out screencap -p > screen-album.png
  echo "Texts on the player:"; adb shell cat /sdcard/ui.xml | grep -oE '(text|content-desc)="[^"]+"' | head -60
  python3 "$here/tap.py" "Поиск"; sleep 2; adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  fail "The album found in the catalog does not play"
fi
metric album_tap_to_sound_s "$tapped"
metric launch_to_sound_s "$launched"
adb exec-out screencap -p > screen-album.png
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
