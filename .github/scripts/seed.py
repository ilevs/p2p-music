"""Seeds a .torrent to the emulator, slowly, so the app streams pieces as they arrive.

Usage: seed.py <file.torrent> <data dir>. The emulator reaches this host at 10.0.2.2:6881.
"""
import sys
import time

import libtorrent as lt

session = lt.session({
    "listen_interfaces": "0.0.0.0:6881",
    "enable_dht": False,
    "enable_lsd": False,
    "enable_upnp": False,
    "enable_natpmp": False,
    # A phone on mobile data gets a track slower than it plays; 64 KB/s makes the player wait for pieces.
    "upload_rate_limit": 65536,
})
session.add_torrent({"ti": lt.torrent_info(sys.argv[1]), "save_path": sys.argv[2]})
while True:
    for handle in session.get_torrents():
        status = handle.status()
        print(f"seeder: {status.state} peers={status.num_peers} sent={status.total_upload}", flush=True)
    time.sleep(5)
