"""Writes a three-track album (silence as WAV, 1:00, 1:30 and 2:00) and its .torrent; prints the info hash.

Usage: make-test-torrent.py <out dir>. Creates <out>/data/<album>/<tracks> and <out>/<hash>.torrent.
"""
import hashlib
import os
import struct
import sys

ALBUM = "Тестовый альбом"
# Pieces span track boundaries, as in real albums.
TRACKS = {"01 Тишина.wav": 60, "02 Ветер.wav": 90, "03 Дождь.wav": 120}
PIECE = 16384


def bencode(value):
    if isinstance(value, int):
        return b"i%de" % value
    if isinstance(value, str):
        value = value.encode()
    if isinstance(value, bytes):
        return b"%d:%s" % (len(value), value)
    if isinstance(value, list):
        return b"l" + b"".join(bencode(v) for v in value) + b"e"
    keys = sorted(value, key=lambda k: k.encode())
    return b"d" + b"".join(bencode(k) + bencode(value[k]) for k in keys) + b"e"


def wav(seconds=60, rate=8000):
    frames = b"\0\0" * rate * seconds
    header = b"RIFF" + struct.pack("<I", 36 + len(frames)) + b"WAVEfmt "
    header += struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16) + b"data" + struct.pack("<I", len(frames))
    return header + frames


out = sys.argv[1]
os.makedirs(os.path.join(out, "data", ALBUM), exist_ok=True)
data = b""
for track, seconds in TRACKS.items():
    with open(os.path.join(out, "data", ALBUM, track), "wb") as f:
        f.write(wav(seconds))
    data += wav(seconds)
pieces = b"".join(hashlib.sha1(data[i:i + PIECE]).digest() for i in range(0, len(data), PIECE))
files = [{"length": len(wav(seconds)), "path": [track]} for track, seconds in TRACKS.items()]
info = {"name": ALBUM, "piece length": PIECE, "pieces": pieces, "files": files}
info_hash = hashlib.sha1(bencode(info)).hexdigest()
with open(os.path.join(out, info_hash + ".torrent"), "wb") as f:
    f.write(bencode({"info": info}))
print(info_hash)
