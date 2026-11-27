"""Writes a one-track album (a second of silence as WAV) and its .torrent; prints the info hash.

Usage: make-test-torrent.py <out dir>. Creates <out>/data/<album>/<track> and <out>/<hash>.torrent.
"""
import hashlib
import os
import struct
import sys

ALBUM = "Тестовый альбом"
TRACK = "01 Тишина.wav"
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


def wav(seconds=3, rate=44100):
    frames = b"\0\0" * rate * seconds
    header = b"RIFF" + struct.pack("<I", 36 + len(frames)) + b"WAVEfmt "
    header += struct.pack("<IHHIIHH", 16, 1, 1, rate, rate * 2, 2, 16) + b"data" + struct.pack("<I", len(frames))
    return header + frames


out = sys.argv[1]
data = wav()
os.makedirs(os.path.join(out, "data", ALBUM), exist_ok=True)
with open(os.path.join(out, "data", ALBUM, TRACK), "wb") as f:
    f.write(data)
pieces = b"".join(hashlib.sha1(data[i:i + PIECE]).digest() for i in range(0, len(data), PIECE))
info = {"name": ALBUM, "piece length": PIECE, "pieces": pieces, "files": [{"length": len(data), "path": [TRACK]}]}
info_hash = hashlib.sha1(bencode(info)).hexdigest()
with open(os.path.join(out, info_hash + ".torrent"), "wb") as f:
    f.write(bencode({"info": info}))
print(info_hash)
