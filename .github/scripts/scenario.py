"""The listening scenario of ilia (2026-11-28): how soon a user hears album after album of several artists, and
what a quick hop between albums costs, and going back to an album heard before. Run by install-check.sh with the app open and the trackers working.

Prints METRIC lines. A sequential album that gives no sound in time is a miss, counted in scenario_albums_played,
not a failure: the trackers do not hold every album. A quick hop that plays another album than the last tapped
one fails, since the album the user picked must be the one that plays.
"""
import os
import re
import statistics
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

# Latin names: "adb shell input text" types ASCII only.
ARTISTS = ["Radiohead", "Daft Punk", "Massive Attack", "Metallica", "Pink Floyd"]
ALBUMS_PER_ARTIST = int(os.environ.get("SCENARIO_ALBUMS", "3"))
HOPS = 4
HOP_PAUSE_S = 2
SOUND_WAIT_S = 90
LIST_WAIT_S = 30
# Texts of the search screen itself, which can stand right before a row's "artist" line.
UI_TEXTS = ["Артист, альбом", "Найти", "Альбомы", "Раздачи", "Мои альбомы"]


def adb(*args):
    return subprocess.run(["adb", *args], check=False, capture_output=True, text=True).stdout


def nodes():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    try:
        return list(ET.fromstring(adb("shell", "cat", "/sdcard/ui.xml")).iter("node"))
    except ET.ParseError:
        return []


def tap_node(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds")))
    adb("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def tap(text, exact=False):
    for node in nodes():
        label = node.get("text", "") + node.get("content-desc", "")
        if (label == text) if exact else (text in label):
            tap_node(node)
            return True
    return False


def album_titles(artist):
    """Titles of the album rows by [artist], top to bottom: each row is a title, then "artist · year"."""
    texts = [t for t in (n.get("text", "") for n in nodes()) if t]
    titles = []
    for before, details in zip(texts, texts[1:]):
        is_details = details == artist or details.startswith(artist + " · ")
        # A row's own "artist · year" line is not the title of the row after it.
        after_details = before.startswith(artist + " · ")
        if is_details and not after_details and before not in UI_TEXTS + titles:
            titles.append(before)
    return titles


def search(artist):
    tap("Поиск", exact=True)
    # The catalog's albums, not the trackers' releases.
    tap("Альбомы", exact=True)
    tap("Артист, альбом")
    # The field keeps its text across tabs: delete both ways from the end, as install-check.sh does.
    adb("shell", "input", "keyevent", "123", *["67", "112"] * 60)
    adb("shell", "input", "text", artist.replace(" ", "%s"))
    tap("Найти", exact=True)
    for i in range(LIST_WAIT_S):
        time.sleep(1)
        # MusicBrainz answers 503 at times to the runners' shared addresses: search again, as a user would.
        if i % 15 == 14:
            tap("Найти", exact=True)
        titles = album_titles(artist)
        if titles:
            return titles
    print(f"No album rows by {artist}; texts on screen: {[n.get('text') for n in nodes() if n.get('text')][:40]}")
    adb("shell", "screencap", "-p", "/sdcard/scenario.png")
    subprocess.run(["adb", "pull", "/sdcard/scenario.png", f"screen-scenario-{artist.replace(' ', '-')}.png"], check=False)
    return []


def opened_name(title):
    """The release the app opened for the album [title], from the log, or None."""
    log = adb("logcat", "-d", "-s", "p2pmusic:I")
    found = re.findall("album «" + re.escape(title) + "»: «(.*)»", log)
    return found[-1] if found else None


def plays(title):
    """Whether the player plays the release opened for [title] now."""
    name = opened_name(title)
    if name is None:
        return False
    playing = re.findall("playing «(.*)»", adb("logcat", "-d", "-s", "p2pmusic:I"))
    return bool(playing) and playing[-1] == name and any(n.get("content-desc") == "Пауза" for n in nodes())


def wait_sound(title):
    """Seconds from now until [title] plays, or None after SOUND_WAIT_S."""
    start = time.time()
    while time.time() - start < SOUND_WAIT_S:
        if plays(title):
            return time.time() - start
        time.sleep(1)
    return None


def listen_in_turn():
    """Plays ALBUMS_PER_ARTIST albums of each artist in turn; returns the albums that played, by artist."""
    started = time.time()
    times, tried, played = [], 0, {}
    for artist in ARTISTS:
        titles = search(artist)[:ALBUMS_PER_ARTIST]
        print(f"{artist}: {titles}")
        for title in titles:
            tried += 1
            tap("Поиск", exact=True)
            adb("logcat", "-c")
            if not tap(title, exact=True):
                print(f"  no row «{title}»")
                continue
            seconds = wait_sound(title)
            print(f"  «{title}»: " + (f"sound in {seconds:.0f} s, release «{opened_name(title)}»" if seconds else "no sound"))
            if seconds is not None:
                times.append(seconds)
                played.setdefault(artist, []).append(title)
    print(f"METRIC scenario_albums_played={len(times)}/{tried}")
    if times:
        print(f"METRIC scenario_album_tap_to_sound_median_s={statistics.median(times):.0f}")
        print(f"METRIC scenario_album_tap_to_sound_max_s={max(times):.0f}")
    print(f"METRIC scenario_total_s={time.time() - started:.0f}")
    return played


def come_back(played):
    """The user changes their mind: back to an album of an artist heard before, after other artists."""
    artist = next((a for a in ARTISTS[1:] if a in played), None)
    if artist is None:
        print("METRIC return_to_album_sound_s=none")
        return
    title = played[artist][0]
    started = time.time()
    search(artist)
    tap("Поиск", exact=True)
    adb("logcat", "-c")
    tapped = time.time()
    seconds = wait_sound(title) if tap(title, exact=True) else None
    print(f"Back to «{title}»: search {tapped - started:.0f} s")
    print(f"METRIC return_to_album_sound_s={seconds:.0f}" if seconds is not None else "METRIC return_to_album_sound_s=none")


def hop_quickly(played):
    """Taps two albums that played, of one artist, back and forth HOPS times HOP_PAUSE_S apart; the last one tapped
    must be the one that plays."""
    artist = next((a for a in ARTISTS if len(played.get(a, [])) >= 2), None)
    if artist is None:
        print("Quick hops: no artist with two albums that played")
        print("METRIC hop_last_album_to_sound_s=none")
        return True
    search(artist)
    titles = [played[artist][i % 2] for i in range(HOPS)]
    adb("logcat", "-c")
    for title in titles:
        tap("Поиск", exact=True)
        tap(title, exact=True)
        time.sleep(HOP_PAUSE_S)
    last = titles[-1]
    seconds = wait_sound(last)
    # Earlier taps may finish after the last; whichever plays at the end must be the last one tapped.
    time.sleep(5)
    right = plays(last) if seconds is not None else None
    print(adb("logcat", "-d", "-s", "p2pmusic:I")[-3000:])
    print(f"METRIC hop_last_album_to_sound_s={seconds:.0f}" if seconds is not None else "METRIC hop_last_album_to_sound_s=none")
    if right is False:
        print(f"::error::After quick hops the player plays another album than the last tapped «{last}»")
    return right is not False


if __name__ == "__main__":
    heard = listen_in_turn()
    right = hop_quickly(heard)
    come_back(heard)
    sys.exit(0 if right else 1)
