#!/usr/bin/env python3
"""Research probe: can CI build an offline index of RuTracker music topics from the official API?"""
import gzip, json, re, time, urllib.request
from concurrent.futures import ThreadPoolExecutor

UA = "p2p-music-probe"
BASE = "https://api.rutracker.cc/v1"


def get(url, data=None, headers=None, timeout=30):
    req = urllib.request.Request(url, data=data, headers={"User-Agent": UA, **(headers or {})})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return json.loads(r.read())
        except Exception as e:  # noqa: BLE001
            err = e
            time.sleep(1 + attempt)
    return {"error": str(err)}


# Knaben: what a hit looks like.
k = get("https://api.knaben.org/v1", data=json.dumps({"search_field": "title", "query": "Кино Группа крови",
        "order_by": "seeders", "order_direction": "desc", "size": 3}).encode(), headers={"Content-Type": "application/json"})
print("knaben hit:", json.dumps(k.get("hits", [])[:1], ensure_ascii=False)[:1500])
print("knaben keys:", list(k.keys()))

tree = get(f"{BASE}/static/cat_forum_tree")["result"]
music = []
for cid in ["8", "35", "31", "22", "23"]:  # music categories, without music video
    for parent, subs in tree["tree"].get(cid, {}).items():
        music += [int(parent), *subs]
print("forum names sample:", [tree["f"].get(str(f)) for f in music[:8]])

t0 = time.time()
pvc = list(ThreadPoolExecutor(32).map(lambda f: (f, get(f"{BASE}/static/pvc/f/{f}", timeout=60)), music))
print(f"METRIC index_pvc_wall_32_parallel_s={time.time() - t0:.1f}")
print("pvc sample:", json.dumps({k: v for k, v in list(pvc[0][1].items()) if k != 'result'})[:300],
      json.dumps(list(pvc[0][1].get("result", {}).items())[:3]))
topics = {}
for f, r in pvc:
    for tid, row in (r.get("result") or {}).items():
        topics[tid] = (f, row)
print(f"METRIC index_music_topics={len(topics)}")
seeded = [t for t, (f, row) in topics.items() if isinstance(row, list) and len(row) > 1 and (row[1] or 0) > 0]
print(f"METRIC index_music_topics_seeded={len(seeded)}")

# Titles: 50 ids per call. Time a slice and extrapolate.
ids = seeded[:20000]
batches = [ids[i:i + 50] for i in range(0, len(ids), 50)]
t0 = time.time()
data = list(ThreadPoolExecutor(16).map(lambda b: get(f"{BASE}/get_tor_topic_data?by=topic_id&val={','.join(b)}"), batches))
dt = time.time() - t0
errors = sum(1 for d in data if "result" not in d)
rows = {}
for d in data:
    for tid, v in (d.get("result") or {}).items():
        if v:
            rows[tid] = v
print(f"METRIC index_topic_data_{len(ids)}_s={dt:.1f} errors={errors}")
print(f"METRIC index_full_build_estimate_min={dt * len(seeded) / max(len(ids), 1) / 60:.1f}")
print("topic data sample:", json.dumps(list(rows.items())[:2], ensure_ascii=False)[:800])
lines = "\n".join(f"{tid}\t{v.get('info_hash')}\t{v.get('size')}\t{v.get('seeders')}\t{v.get('topic_title')}" for tid, v in rows.items())
raw = lines.encode()
packed = gzip.compress(raw, 9)
per = len(packed) / max(len(rows), 1)
print(f"METRIC index_bytes_per_topic_gz={per:.1f}")
print(f"METRIC index_full_size_gz_mb={per * len(seeded) / 1e6:.1f}")
# Coverage of the same albums the first probe looked for, inside this slice only (a lower bound).
for a, b in [("кино", "группа крови"), ("земфира", ""), ("би-2", ""), ("король и шут", ""), ("сплин", ""), ("molchat doma", "")]:
    n = sum(1 for v in rows.values() if a in v.get("topic_title", "").lower() and b in v.get("topic_title", "").lower())
    print(f"  slice has {n} topics for {a!r} {b!r}")
