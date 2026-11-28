#!/usr/bin/env python3
"""Research probe: RuTracker official API shapes, latency and throughput; Knaben hit shape."""
import gzip, json, time, urllib.request
from concurrent.futures import ThreadPoolExecutor

BASE = "https://api.rutracker.cc/v1"


def get(url, data=None, headers=None, timeout=30):
    req = urllib.request.Request(url, data=data, headers={"User-Agent": "p2p-music-probe", **(headers or {})})
    t = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read()), time.time() - t
    except Exception as e:  # noqa: BLE001
        return {"error": str(e)}, time.time() - t


k, dt = get("https://api.knaben.org/v1", data=json.dumps({"search_type": "100%", "search_field": "title",
        "query": "Кино Группа крови", "order_by": "seeders", "order_direction": "desc", "size": 3}).encode(),
        headers={"Content-Type": "application/json"})
print(f"knaben {dt:.2f}s hit:", json.dumps(k.get("hits", [])[:1], ensure_ascii=False)[:1500])

r, dt = get(f"{BASE}/static/pvc/f/1704")  # one music forum
print(f"pvc f/1704 {dt:.2f}s keys={list(r)}", json.dumps({k: v for k, v in r.items() if k != 'result'})[:400])
res = r.get("result") or {}
print("pvc rows:", len(res), json.dumps(list(res.items())[:5]))
ids = list(res)[:50]
r, dt = get(f"{BASE}/get_tor_topic_data?by=topic_id&val={','.join(ids)}")
print(f"METRIC rutracker_api_topic_data_50_s={dt:.2f}")
print("topic data:", json.dumps(list((r.get('result') or {}).items())[:2], ensure_ascii=False)[:900], str(r.get("error"))[:300])
r, dt = get(f"{BASE}/get_tor_hash?by=topic_id&val={','.join(ids[:5])}")
print(f"METRIC rutracker_api_tor_hash_s={dt:.2f}", json.dumps(r)[:300])
r, dt = get(f"{BASE}/get_limit")
print("limit:", json.dumps(r)[:200])

# Throughput with 4 workers over 4000 topics of a few forums.
pool_ids = []
for f in [1704, 1705, 1706, 1707, 1708, 1709, 1710, 1711]:
    rr, _ = get(f"{BASE}/static/pvc/f/{f}")
    pool_ids += list((rr.get("result") or {}))
pool_ids = pool_ids[:4000]
batches = [pool_ids[i:i + 50] for i in range(0, len(pool_ids), 50)]
t0 = time.time()
out = list(ThreadPoolExecutor(4).map(lambda b: get(f"{BASE}/get_tor_topic_data?by=topic_id&val={','.join(b)}")[0], batches))
dt = time.time() - t0
errs = [o.get("error") for o in out if "result" not in o]
rows = {t: v for o in out for t, v in (o.get("result") or {}).items() if v}
print(f"METRIC rutracker_api_topics_per_s_4workers={len(rows) / dt:.0f} batches={len(batches)} errors={len(errs)} {errs[:2]}")
lines = "\n".join(f"{t}\t{v.get('info_hash')}\t{v.get('size')}\t{v.get('seeders')}\t{v.get('topic_title')}" for t, v in rows.items())
if rows:
    print(f"METRIC index_bytes_per_topic_gz={len(gzip.compress(lines.encode(), 9)) / len(rows):.1f}")
    print("titles:", [v.get("topic_title") for v in list(rows.values())[:5]])
