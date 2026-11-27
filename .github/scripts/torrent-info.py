"""Prints the info hash of a .torrent and the name of the track the player lists first.

Usage: torrent-info.py <file.torrent>. Mirrors the app: audio files sorted by path.
"""
import sys

import libtorrent as lt

AUDIO = {"mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav"}
info = lt.torrent_info(sys.argv[1])
files = info.files()
paths = sorted(files.file_path(i) for i in range(files.num_files()) if files.file_path(i).rsplit(".", 1)[-1].lower() in AUDIO)
print(str(info.info_hash()), paths[0].rsplit("/", 1)[-1])
