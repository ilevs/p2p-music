"""Seeds a .torrent to the emulator, slowly, so the app streams pieces as they arrive.

Usage: seed.py <file.torrent> <data dir>. The emulator reaches it at 127.0.0.1:6882 through adb reverse.
"""
import sys
import time

import libtorrent as lt

session = lt.session({
    "listen_interfaces": "127.0.0.1:6882",
    "enable_dht": False,
    "enable_lsd": False,
    "enable_upnp": False,
    "enable_natpmp": False,
    # 64 KB/s: the track (16 KB/s of audio) arrives while it plays, piece by piece.
    "upload_rate_limit": 65536,
})
session.add_torrent({"ti": lt.torrent_info(sys.argv[1]), "save_path": sys.argv[2]})
while True:
    for handle in session.get_torrents():
        status = handle.status()
        print(f"seeder: {status.state} peers={status.num_peers} sent={status.total_upload}", flush=True)
        for peer in handle.get_peer_info():
            flags = [name for name in ("remote_interested", "choked") if peer.flags & getattr(lt.peer_info, name)]
            print(f"  peer {peer.ip} {peer.client} {flags} type={peer.connection_type} down={peer.total_download}", flush=True)
    time.sleep(5)
