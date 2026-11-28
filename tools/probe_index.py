#!/usr/bin/env python3
"""Research probe: the shape of a Knaben hit, and its answer to a few query forms."""
import json, time, urllib.request

def knaben(body):
    req = urllib.request.Request("https://api.knaben.org/v1", data=json.dumps(body).encode(),
                                 headers={"Content-Type": "application/json", "User-Agent": "p2p-music-probe"})
    t = time.time()
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.loads(r.read()), time.time() - t

for body in [
    {"search_type": "100%", "search_field": "title", "query": "Кино Группа крови", "order_by": "seeders",
     "order_direction": "desc", "size": 100, "hide_unsafe": False, "hide_xxx": True},
    {"query": "Кино Группа крови", "size": 50},
    {"query": "Кино Группа крови", "search_field": "title", "size": 50, "categories": [2000000]},
    {"query": "Земфира", "search_field": "title", "order_by": "seeders", "order_direction": "desc", "size": 300},
]:
    r, dt = knaben(body)
    hits = r.get("hits", [])
    print(f"{dt:.2f}s total={r.get('total')} hits={len(hits)} body={json.dumps(body, ensure_ascii=False)}")
    for h in hits[:3]:
        print("   ", json.dumps(h, ensure_ascii=False)[:900])
