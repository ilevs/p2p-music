package io.github.ilevs.p2pmusic.catalog

import io.github.ilevs.p2pmusic.engine.TorrentEngine
import io.github.ilevs.p2pmusic.search.AllSources
import io.github.ilevs.p2pmusic.search.Rutor
import io.github.ilevs.p2pmusic.search.trackerHttpClient
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.libtorrent4j.TorrentInfo
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The user's path on the real sites, up to the .torrent: album search in the catalog, then the
 * release the app would pick on Rutor. Downloads no music; the install check proves playback.
 */
class UserPathLiveTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun findsAReleaseForACatalogAlbum() {
        assumeTrue("LIVE_TESTS is not set", System.getenv("LIVE_TESTS") == "true")
        // The catalog ranks tributes too; the user taps the one by Pink Floyd.
        val album =
            MusicBrainz(OkHttpClient())
                .searchAlbums("Pink Floyd The Dark Side of the Moon")
                .first { it.artist == "Pink Floyd" }
        val client = trackerHttpClient(proxy = null)
        val source = AllSources(listOf(Rutor(client = { client })))

        val results = source.search("${album.artist} ${album.title}")
        println("$album: ${results.size} releases, top ${results.sortedByDescending { it.seeds }.take(3)}")
        val pick =
            checkNotNull(pickTorrent(album, results) ?: pickTorrent(album, source.search(album.artist))) {
                "No release with seeds for $album among $results"
            }

        val (info, torrentMs) = timed { TorrentInfo.bdecode(source.torrentFile(pick)) }
        assertTrue(info.isValid && info.numFiles() > 0)

        // The way the app opens it: the magnet from the search page, metadata from peers.
        val cold = temp.newFolder()
        val (handle, coldMs) = timed { TorrentEngine(cold).openMagnet(pick.magnet) }
        assertEquals(info.infoHash(), handle.infoHash())

        // A later launch: the DHT nodes of the last run are kept, the .torrent cache is not (a new album).
        val warm = temp.newFolder()
        File(cold, "session.dat").copyTo(File(warm, "session.dat"))
        val (_, warmMs) = timed { TorrentEngine(warm).openMagnet(pick.magnet) }
        // Collected into the CI job summary, to compare builds.
        println("METRIC torrent_file_ms=$torrentMs")
        println("METRIC magnet_metadata_cold_ms=$coldMs")
        println("METRIC magnet_metadata_saved_dht_ms=$warmMs")
    }

    private fun <T> timed(block: () -> T): Pair<T, Long> {
        val start = System.nanoTime()
        return block() to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
    }
}
