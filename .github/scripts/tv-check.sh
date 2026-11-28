#!/usr/bin/env bash
# Usage: tv-check.sh <apk to check>
# The user's path on Android TV with the remote only: D-pad keys and OK, never a tap. Launched from the TV launcher,
# find an album, open it, play a track, pause and resume, seek, reach the settings.
set -u
apk="$1"
here="$(dirname "$0")"
# From the APK itself, so a new applicationId needs no edit here.
package="$("$(ls "$ANDROID_HOME"/build-tools/*/aapt | tail -1)" dump badging "$apk" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
echo "Package $package"

adb shell getprop ro.build.version.release
adb shell getprop ro.product.cpu.abilist
adb shell pm list features | grep -E "leanback|touchscreen" || true

fail() {
  adb exec-out screencap -p > screen-tv-failed.png
  adb shell pidof "$package" || echo "The app is not running"
  adb logcat -d -b crash
  adb logcat -d | grep -E "AndroidRuntime|$package|ExoPlayer" | tail -40
  adb logcat -d -s p2pmusic:I | tail -20
  echo "Texts on screen:"; adb shell cat /sdcard/ui.xml | grep -oE '(text|content-desc)="[^"]+"' | head -40
  echo "::error::$1"
  exit 1
}
# Remote keys: UP, DOWN, LEFT, RIGHT, CENTER, BACK.
key() { python3 "$here/dpad.py" press "$@"; sleep 1; }
dpad() { python3 "$here/dpad.py" "$@"; }
screen() { adb shell uiautomator dump /sdcard/ui.xml > /dev/null; adb shell cat /sdcard/ui.xml; }
metric() {
  echo "METRIC $1=$(($(date +%s) - $2))" | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"
}
# The position line "m:ss / m:ss" in seconds.
position() {
  screen | grep -o 'text="[0-9]*:[0-9]* / [^"]*"' | head -1 | sed -E 's/text="([0-9]+):([0-9]+) .*/\1 \2/' |
    awk '{ print $1 * 60 + $2 }'
}
playing() { screen | grep -q 'content-desc="Пауза"'; }

adb install -r "$apk" || fail "The APK does not install on Android TV"
# The TV launcher lists only apps with a LEANBACK_LAUNCHER activity; monkey starts it the same way.
adb shell cmd package query-activities -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER |
  grep -q "$package" || fail "The app is not in the Android TV launcher"
adb logcat -c
launched=$(date +%s)
adb shell monkey -p "$package" -c android.intent.category.LEANBACK_LAUNCHER 1
for _ in $(seq 20); do
  screen | grep -q 'text="Поиск"' && break
  sleep 1
done || fail "No search screen after launch"
metric tv_launch_to_ui_s "$launched"
adb exec-out screencap -p > screen-tv-start.png

echo "::group::Search with the remote"
dpad "Артист, альбом" DOWN 6 || dpad "Артист, альбом" RIGHT 4 || fail "The search field cannot be reached by remote"
# Focus alone opens the on-screen keyboard; OK opens it when it was closed.
adb shell dumpsys input_method | grep -q "mInputShown=true" || key CENTER
adb shell input text "Pink%sFloyd%sThe%sDark%sSide%sof%sthe%sMoon"
# Back hides the on-screen keyboard; without one it would leave the app.
adb shell dumpsys input_method | grep -q "mInputShown=true" && key BACK
adb exec-out screencap -p > screen-tv-query.png
dpad "Найти" RIGHT 3 || dpad "Найти" DOWN 3 || fail "The search button cannot be reached by remote"
key CENTER
searched=$(date +%s)
for i in $(seq 60); do
  sleep 1
  # MusicBrainz answers 503 to the runners' shared addresses at times: search again, as a user would.
  [ $((i % 15)) -eq 0 ] && key CENTER
  screen | grep -q 'text="Pink Floyd · 1973"' && break
done || fail "The catalog shows no Pink Floyd album of 1973"
metric tv_search_to_albums_s "$searched"
dpad "Pink Floyd · 1973" DOWN 15 || fail "The album row cannot be reached by remote"
adb exec-out screencap -p > screen-tv-album-focused.png
key CENTER
tapped=$(date +%s)
echo "::endgroup::"

echo "::group::Play a track with the remote"
# The album opens the player with its tracks; OK on a track plays it.
for _ in $(seq 90); do
  sleep 1
  screen | grep -qiE 'text="[^"]*(Breathe|Time)' && break
done || fail "The album's tracks do not show"
dpad "Breathe" DOWN 12 || dpad "Breathe" RIGHT 4 || fail "No track can be reached by remote"
key CENTER
for _ in $(seq 90); do
  sleep 1
  playing && break
done || fail "The track picked by remote does not play"
metric tv_album_tap_to_sound_s "$tapped"
metric tv_launch_to_sound_s "$launched"
adb exec-out screencap -p > screen-tv-playing.png
echo "::endgroup::"

echo "::group::Pause, resume and seek with the remote"
dpad "Пауза" UP 12 || fail "The pause button cannot be reached by remote"
key CENTER
sleep 2
screen | grep -q 'content-desc="Играть"' || fail "OK on the pause button does not pause"
paused="$(position)"
sleep 3
[ "$(position)" = "$paused" ] || fail "The position moves while paused"
key CENTER
sleep 2
playing || fail "OK on the play button does not resume"
dpad "class:android.widget.SeekBar" UP 3 || fail "The seek bar cannot be reached by remote"
before="$(position)"
# shellcheck disable=SC2046  # one key per word
key $(printf 'RIGHT %.0s' $(seq 10))
after=
for _ in $(seq 30); do
  sleep 1
  after="$(position)"
  [ "${after:-0}" -ge $((before + 15)) ] && playing && break
done || fail "Right on the seek bar does not seek forward: ${before}s, then ${after}s"
echo "Seek by remote: ${before}s to ${after}s"
adb exec-out screencap -p > screen-tv-seek.png
echo "::endgroup::"

echo "::group::Settings with the remote"
dpad "Настройки" LEFT 4 || dpad "Настройки" DOWN 4 || fail "The settings tab cannot be reached by remote"
key CENTER
dpad "Логин" RIGHT 3 || dpad "Логин" DOWN 3 || fail "The settings fields cannot be reached by remote"
dpad "Сохранить" DOWN 20 || fail "The save button cannot be reached by remote"
adb exec-out screencap -p > screen-tv-settings.png
playing || fail "The track stopped while moving around the settings"
echo "::endgroup::"
adb shell pidof "$package" > /dev/null || fail "The app died on Android TV"
echo "Played and controlled a track with the remote only."
