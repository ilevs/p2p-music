#!/usr/bin/env python3
"""Research probe: how many music releases each torrent search source returns, and how fast.

Prints METRIC name=value lines. Searches only; downloads no content.
"""
import json, re, time, urllib.parse, urllib.request, gzip

UA = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"
ALBUMS = [
    ("Кино", "Группа крови"), ("Сплин", "Гранатовый альбом"), ("Земфира", "Прости меня моя любовь"),
    ("Би-2", "Мяу кисс ми"), ("Король и Шут", "Акустический альбом"), ("Аквариум", "Радио Африка"),
    ("Pink Floyd", "The Dark Side of the Moon"), ("Radiohead", "OK Computer"), ("Massive Attack", "Mezzanine"),
    ("Daft Punk", "Discovery"), ("Burial", "Untrue"), ("Molchat Doma", "Этажи"),
]


def get(url, data=None, headers=None, timeout=25):
    h = {"User-Agent": UA, "Accept-Encoding": "gzip"}
    h.update(headers or {})
    req = urllib.request.Request(url, data=data, headers=h)
    t = time.time()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            body = r.read()
            if r.headers.get("Content-Encoding") == "gzip":
                body = gzip.decompress(body)
            return r.status, body, time.time() - t
    except urllib.error.HTTPError as e:
        return e.code, e.read(), time.time() - t
    except Exception as e:  # noqa: BLE001
        return f"ERR {type(e).__name__}: {e}"[:120], b"", time.time() - t


def norm(s):
    return " " + re.sub(r"[^\w]+", " ", s.lower().replace("ё", "е")).strip() + " "


def hits(titles, artist, album):
    a, b = norm(artist), norm(album)
    with_artist = [t for t in titles if a in norm(t)]
    return len(titles), len(with_artist), sum(1 for t in with_artist if b in norm(t))


def jacred(q, host):
    code, body, dt = get(f"https://{host}/api/v1.0/torrents?search={urllib.parse.quote(q)}&apikey=")
    try:
        items = json.loads(body)
    except Exception:  # noqa: BLE001
        return code, dt, [], {}
    trackers = {}
    for i in items:
        trackers[i.get("tracker")] = trackers.get(i.get("tracker"), 0) + 1
    return code, dt, [i.get("title", "") for i in items], trackers


def rutor(q):
    code, body, dt = get(f"https://rutor.info/search/0/2/300/0/{urllib.parse.quote(q)}")
    html = body.decode("utf-8", "replace")
    titles = re.findall(r'<a href="/torrent/\d+/[^"]*">([^<]+)</a>', html)
    return code, dt, titles


def rutracker_page():
    code, body, dt = get("https://rutracker.org/forum/tracker.php?nm=test")
    html = body.decode("cp1251", "replace")
    m = re.search(r"<title>([^<]*)", html)
    return code, dt, m.group(1) if m else ""


def apibay(q):
    code, body, dt = get(f"https://apibay.org/q.php?q={urllib.parse.quote(q)}&cat=100")
    try:
        items = [i for i in json.loads(body) if i.get("id") != "0"]
    except Exception:  # noqa: BLE001
        items = []
    return code, dt, [i["name"] for i in items]


def knaben(q):
    payload = json.dumps({"search_type": "100%", "search_field": "title", "query": q, "order_by": "seeders",
                          "order_direction": "desc", "size": 100, "hide_unsafe": False, "hide_xxx": True}).encode()
    code, body, dt = get("https://api.knaben.org/v1", data=payload, headers={"Content-Type": "application/json"})
    try:
        hits_ = json.loads(body).get("hits", [])
    except Exception:  # noqa: BLE001
        hits_ = []
    trackers = {}
    for i in hits_:
        trackers[i.get("tracker")] = trackers.get(i.get("tracker"), 0) + 1
    return code, dt, [i.get("title", "") for i in hits_], trackers


def nnm(q):
    code, body, dt = get("https://nnmclub.to/forum/tracker.php?nm=" + urllib.parse.quote(q.encode("cp1251")))
    html = body.decode("cp1251", "replace")
    titles = re.findall(r'class="genmed topictitle"[^>]*>\s*<a[^>]*><b>([^<]+)</b>', html) or \
        re.findall(r'<a class="genmed topictitle"[^>]*><b>([^<]+)', html)
    return code, dt, titles


def kinozal(q):
    code, body, dt = get("https://kinozal.tv/browse.php?c=3&s=" + urllib.parse.quote(q.encode("cp1251")))
    html = body.decode("cp1251", "replace")
    titles = re.findall(r'<a href="/details\.php\?id=\d+" class="r\d">([^<]+)</a>', html)
    return code, dt, titles


def rutracker_api():
    base = "https://api.rutracker.cc/v1"
    code, body, dt = get(f"{base}/static/cat_forum_tree")
    print(f"rutracker_api cat_forum_tree: HTTP {code} {dt:.2f}s {len(body)}B")
    try:
        tree = json.loads(body)["result"]
    except Exception:  # noqa: BLE001
        print("  body:", body[:200])
        return
    cats = tree.get("c", {})
    forums = tree.get("f", {})
    music_cats = [cid for cid, name in cats.items() if re.search(r"музык|рок|джаз|поп|электрон|рэп|шансон|metal|классическ", name, re.I)]
    print("  music categories:", {c: cats[c] for c in music_cats})
    music_forums = []
    for cid in music_cats:
        for parent, subs in tree.get("tree", {}).get(cid, {}).items():
            music_forums.append(int(parent))
            music_forums.extend(subs)
    print(f"METRIC rutracker_api_music_forums={len(music_forums)}")
    total, t_all = 0, 0.0
    sample_ids = []
    for f in music_forums:
        c, b, d = get(f"{base}/static/pvc/f/{f}", timeout=60)
        t_all += d
        try:
            res = json.loads(b)["result"]
            total += len(res)
            if len(sample_ids) < 100:
                sample_ids += list(res.keys())[: 100 - len(sample_ids)]
        except Exception:  # noqa: BLE001
            pass
    print(f"METRIC rutracker_api_music_topics={total}")
    print(f"METRIC rutracker_api_pvc_all_music_s={t_all:.1f}")
    c, b, d = get(f"{base}/get_tor_topic_data?by=topic_id&val={','.join(sample_ids)}")
    try:
        data = json.loads(b)["result"]
        ok = [v for v in data.values() if v]
        print(f"METRIC rutracker_api_topic_data_100_s={d:.2f}")
        print("  sample:", json.dumps(ok[:2], ensure_ascii=False)[:600])
    except Exception:  # noqa: BLE001
        print("  topic data:", c, b[:200])


def main():
    code, dt, title = rutracker_page()
    print(f"rutracker.org tracker.php: HTTP {code} {dt:.2f}s title={title!r}")
    sums = {}
    for artist, album in ALBUMS:
        print(f"\n== {artist} / {album}")
        for name, fn in [
            ("jacred_xyz", lambda q: jacred(q, "jacred.xyz")), ("jac_red", lambda q: jacred(q, "jac.red")),
            ("rutor", rutor), ("apibay", apibay), ("knaben", knaben), ("nnm", nnm), ("kinozal", kinozal),
        ]:
            for label, q in [("artist", artist), ("exact", f"{artist} {album}")]:
                r = fn(q)
                code, dt, titles = r[0], r[1], r[2]
                n, na, nb = hits(titles, artist, album)
                extra = f" trackers={r[3]}" if len(r) > 3 else ""
                print(f"  {name:10} {label:6} HTTP {code} {dt:5.2f}s results={n} with_artist={na} with_album={nb}{extra}")
                s = sums.setdefault((name, label), [0, 0, 0.0, 0])
                s[0] += na; s[1] += 1 if nb else 0; s[2] += dt; s[3] += 1
    print()
    for (name, label), (na, found, t, k) in sums.items():
        print(f"METRIC probe_{name}_{label}_with_artist_total={na}")
        print(f"METRIC probe_{name}_{label}_albums_found={found}/{k}")
        print(f"METRIC probe_{name}_{label}_avg_s={t / k:.2f}")
    rutracker_api()


main()
