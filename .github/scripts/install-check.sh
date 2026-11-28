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
for i in $(seq 60); do
  sleep 1
  # MusicBrainz answers 503 for minutes to the runners' shared addresses: search again, as a user would.
  [ $((i % 15)) -eq 0 ] && python3 "$here/tap.py" "Найти" > /dev/null
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
# Then another album of the same artist: the player must leave the first and play the second. On ilia's phone
# every album of an artist opened the same release on 2026-11-28. "Shine On You Crazy Diamond" opens the second.
python3 "$here/tap.py" "Поиск" || fail "No search tab"
python3 "$here/tap.py" "Артист, альбом" || fail "No search field"
# shellcheck disable=SC2046  # one key code per word: end of the line, then delete what the field kept
adb shell input keyevent 123 $(printf '67 %.0s' $(seq 60))
adb shell input text "Pink%sFloyd%sWish%sYou%sWere%sHere"
python3 "$here/tap.py" "Найти" || fail "No search button"
for _ in $(seq 20); do
  sleep 1
  # Any release group by the band: the catalog lists several named like the album, and the 1975 one
  # can be below a small screen.
  # The rows of the first search stay until the answer comes: wait for a row titled as the second album.
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  adb shell cat /sdcard/ui.xml | grep -q 'text="Wish You Were Here"' || continue
  # Deezer and iTunes rows carry no year, so the band's row can read just "Pink Floyd".
  python3 "$here/tap.py" "Pink Floyd · " || python3 "$here/tap.py" "=Pink Floyd" && break
done || fail "The catalog shows no Pink Floyd album for Wish You Were Here"
switched=$(date +%s)
playing=
for _ in $(seq 90); do
  sleep 1
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  if adb shell cat /sdcard/ui.xml | grep -q 'content-desc="Пауза"' &&
    adb shell cat /sdcard/ui.xml | grep -qi 'text="[^"]*Shine On'; then
    playing=yes
    break
  fi
done
if [ -z "$playing" ]; then
  adb exec-out screencap -p > screen-second-album.png
  echo "Texts on the player:"; adb shell cat /sdcard/ui.xml | grep -oE '(text|content-desc)="[^"]+"' | head -60
  fail "The second album does not play after the first"
fi
metric album_switch_to_sound_s "$switched"
adb exec-out screencap -p > screen-second-album.png
# Then an album that only NNM-Club had on 2026-11-28 (Rutor and Knaben find nothing), so the release comes
# from the fallback source, by the magnet of its topic. A Latin name: "input text" types ASCII only.
python3 "$here/tap.py" "Поиск" || fail "No search tab"
python3 "$here/tap.py" "Артист, альбом" || fail "No search field"
# shellcheck disable=SC2046  # one key code per word: end of the line, then delete what the field kept
adb shell input keyevent 123 $(printf '67 %.0s' $(seq 60))
adb shell input text "Little%sBig%sAntipositive"
python3 "$here/tap.py" "Найти" || fail "No search button"
for i in $(seq 40); do
  sleep 1
  [ $((i % 15)) -eq 0 ] && python3 "$here/tap.py" "Найти" > /dev/null
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  adb shell cat /sdcard/ui.xml | grep -qi 'text="Antipositive' || continue
  python3 "$here/tap.py" "Little Big · " && break
done || fail "The catalog shows no Little Big album for Antipositive"
tapped=$(date +%s)
playing=
for _ in $(seq 120); do
  sleep 1
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  # The pause button alone could still be the previous album's.
  if adb shell cat /sdcard/ui.xml | grep -q 'content-desc="Пауза"' &&
    ! adb shell cat /sdcard/ui.xml | grep -qi 'text="[^"]*Shine On'; then
    playing=yes
    break
  fi
done
adb exec-out screencap -p > screen-nnm-album.png
echo "Texts with the NNM-Club album:"; adb shell cat /sdcard/ui.xml | grep -oE '(text|content-desc)="[^"]+"' | head -40
[ -n "$playing" ] || fail "The album that only NNM-Club has does not play"
metric nnm_album_tap_to_sound_s "$tapped"
# Which releases the three albums opened and how long each search and each try took.
adb logcat -d -s p2pmusic:I | grep -E "search|open" | tail -30
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

# Soulseek, the fallback source: the tracker proxy points nowhere, so both trackers fail and the catalog album
# must come from Soulseek, the way a user hears an album the trackers do not have. A new account per run and
# emulator: the server drops a session when the same name logs in elsewhere, and a fixed name once stopped
# accepting its password (API 37.0 on 2026-11-28). The server registers an unknown name on its first login.
echo "::group::Play a catalog album from Soulseek"
page="$(adb shell getconf PAGE_SIZE 2>/dev/null | tr -d '\r')"
user="p2pm-${GITHUB_RUN_ID:-$(date +%s)}-${GITHUB_RUN_ATTEMPT:-1}-$(adb shell getprop ro.build.version.sdk | tr -d '\r')-${page:-4096}"
# Taps the element with text $1 (the $2-th, from 0), scrolling down until it shows.
tap_scrolling() {
  for _ in 1 2 3 4 5; do
    python3 "$here/tap.py" "$1" "${2:-0}" > /dev/null && return 0
    adb shell input swipe 160 500 160 150 300
    sleep 1
  done
  return 1
}
# Types $2 into the field labelled $1 (the $3-th).
type_into() {
  tap_scrolling "$1" "${3:-0}" || fail "No field $1 on the settings screen"
  adb shell input text "$2"
  # Back hides the keyboard; without a keyboard it would leave the screen.
  adb shell dumpsys input_method | grep -q "mInputShown=true" && adb shell input keyevent 4
  sleep 1
}
# The tracker step put this album in the library, which plays it from the torrent: start clean.
adb shell pm clear "$package"
adb logcat -c
adb shell monkey -p "$package" -c android.intent.category.LAUNCHER 1
sleep 10
python3 "$here/tap.py" "Настройки" || fail "No settings tab"
type_into "Логин" "$user" 1
type_into "Пароль" "${SOULSEEK_PASSWORD:-p2pmusic-ci-not-secret}" 1
type_into "Адрес" "127.0.0.1"
type_into "Порт" "9"
tap_scrolling "Сохранить" || fail "No save button"
python3 "$here/tap.py" "Поиск" || fail "No search tab"
python3 "$here/tap.py" "Артист, альбом" || fail "No search field"
adb shell input text "Pink%sFloyd%sThe%sDark%sSide%sof%sthe%sMoon"
python3 "$here/tap.py" "Найти" || fail "No search button"
for i in $(seq 20); do
  sleep 3
  [ $((i % 5)) -eq 0 ] && python3 "$here/tap.py" "Найти" > /dev/null
  python3 "$here/tap.py" "Pink Floyd · 1973" && break
done || fail "The catalog shows no Pink Floyd album of 1973"
tapped=$(date +%s)
playing=
for _ in $(seq 180); do
  sleep 1
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  if adb shell cat /sdcard/ui.xml | grep -q 'content-desc="Пауза"'; then
    playing=yes
    break
  fi
  python3 "$here/tap.py" "Плеер" > /dev/null || true
done
# "Пауза" shows only while the player renders audio; the position must also move.
if [ -n "$playing" ]; then
  metric soulseek_album_tap_to_sound_s "$tapped"
  sleep 6
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  position="$(adb shell cat /sdcard/ui.xml | grep -o 'text="[0-9]*:[0-9]* / [^"]*"' | head -1)"
  echo "Position after 6 s: $position"
  if [ -z "$position" ] || echo "$position" | grep -q 'text="0:0[0-2] /'; then playing=; fi
fi
if [ -z "$playing" ]; then
  adb exec-out screencap -p > screen-soulseek.png
  echo "Texts on the player:"; adb shell cat /sdcard/ui.xml | grep -o 'text="[^"]*"' | head -60
  python3 "$here/tap.py" "Поиск"; sleep 2; adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  adb shell cat /sdcard/ui.xml | grep -o 'text="[^"]*Soulseek[^"]*"'
  fail "The album does not play from Soulseek"
fi
adb exec-out screencap -p > screen-soulseek.png
adb shell pidof "$package" > /dev/null || fail "The app died while playing from Soulseek"
echo "::endgroup::"

# The releases tab with the trackers still failing: it must list Soulseek folders, and a tapped folder must play.
echo "::group::Play a Soulseek folder from the releases tab"
python3 "$here/tap.py" "Пауза" > /dev/null
python3 "$here/tap.py" "Поиск" || fail "No search tab"
python3 "$here/tap.py" "Раздачи" || fail "No releases tab"
python3 "$here/tap.py" "Артист, альбом" || fail "No search field"
adb shell input keyevent KEYCODE_MOVE_END
adb shell input keyevent $(for _ in $(seq 60); do printf '67 112 '; done)
adb shell input text "Pink%sFloyd%sWish%sYou%sWere%sHere"
python3 "$here/tap.py" "Найти" || fail "No search button"
searched=$(date +%s)
for _ in $(seq 30); do
  sleep 2
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  adb shell cat /sdcard/ui.xml | grep -q 'text="Soulseek · ' && break
  # The trackers' errors above the list can push it below a small screen.
  adb shell input swipe 160 500 160 150 300
done || {
  adb shell cat /sdcard/ui.xml | grep -oE 'text="[^"]+"' | head -30
  fail "The releases tab lists no Soulseek folder"
}
metric soulseek_releases_search_s "$searched"
# A folder's users can all be busy, and then the app says so and asks for another, as it should; the check does
# what a user would and taps the next folder, so only an app that plays none of them fails.
tried=
playing=
for attempt in 1 2 3 4; do
  row=
  for _ in 1 2 3 4 5; do
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null
    row="$(adb shell cat /sdcard/ui.xml | grep -oE 'text="Soulseek · [^"]+"' | sed 's/^text="//; s/"$//' \
      | grep -vxF -e "${tried:-none}" | head -1 || true)"
    [ -n "$row" ] && break
    adb shell input swipe 160 500 160 150 300
    sleep 2
  done
  [ -n "$row" ] || break
  tried="$tried$row"$'\n'
  echo "Attempt $attempt: $row"
  python3 "$here/tap.py" "$row" > /dev/null
  # A tap while the list still flings from the scroll only stops it: tap again once it settles, if still there.
  sleep 2
  python3 "$here/tap.py" "$row" > /dev/null || true
  tapped=$(date +%s)
  for _ in $(seq 150); do
    sleep 1
    adb shell uiautomator dump /sdcard/ui.xml > /dev/null
    adb shell cat /sdcard/ui.xml > ui.xml
    if grep -q 'content-desc="Пауза"' ui.xml; then
      playing=yes
      break
    fi
    grep -q 'text="Ошибка: ' ui.xml && break
  done
  [ -n "$playing" ] && break
  grep -oE 'text="Ошибка[^"]*"' ui.xml || true
  python3 "$here/tap.py" "Поиск" > /dev/null
  sleep 2
done
echo "METRIC soulseek_folder_attempts=$attempt" | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
if [ -n "$playing" ]; then
  metric soulseek_folder_tap_to_sound_s "$tapped"
  sleep 6
  adb shell uiautomator dump /sdcard/ui.xml > /dev/null
  position="$(adb shell cat /sdcard/ui.xml | grep -o 'text="[0-9]*:[0-9]* / [^"]*"' | head -1)"
  echo "Position after 6 s: $position"
  if [ -z "$position" ] || echo "$position" | grep -q 'text="0:0[0-2] /'; then playing=; fi
fi
if [ -z "$playing" ]; then
  adb exec-out screencap -p > screen-soulseek-folder.png
  echo "Texts on screen:"; adb shell cat /sdcard/ui.xml | grep -oE '(text|content-desc)="[^"]+"' | head -60
  fail "The Soulseek folder from the releases tab does not play"
fi
adb exec-out screencap -p > screen-soulseek-folder.png
adb shell pidof "$package" > /dev/null || fail "The app died while playing a Soulseek folder"
echo "::endgroup::"
