import sys, time, libtorrent as lt
ti = lt.torrent_info(sys.argv[1])
print('piece', ti.piece_length(), 'web', [w['url'] for w in ti.web_seeds()], 'trackers', [t.url for t in ti.trackers()])
s = lt.session({'alert_mask': lt.alert.category_t.all_categories, 'close_redundant_connections': False})
p = lt.add_torrent_params(); p.ti = ti; p.save_path = '/tmp/exp'
p.file_priorities = [0] * ti.num_files()
h = s.add_torrent(p)
time.sleep(3)
h.file_priority(0, 7)
end = time.time() + 90
while time.time() < end:
    for a in s.pop_alerts():
        m = a.message()
        if 'url_seed' in a.what() or 'error' in a.what() or 'archive.org' in m and 'tracker' not in a.what():
            print(a.what(), m[:300])
    st = h.status()
    print('STATUS', st.state, 'peers', st.num_peers, 'rate', st.download_rate, 'done', st.total_wanted_done, [ (pi.ip, pi.client, pi.flags & 1, pi.down_speed) for pi in h.get_peer_info()])
    time.sleep(3)
