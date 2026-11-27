package io.github.ilevs.p2pmusic.catalog

import io.github.ilevs.p2pmusic.search.AllSources
import io.github.ilevs.p2pmusic.search.Rutor
import io.github.ilevs.p2pmusic.search.trackerHttpClient
import okhttp3.OkHttpClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.libtorrent4j.TorrentInfo

/**
 * The user's path on the real sites, up to the .torrent: album search in the catalog, then the
 * release the app would pick on Rutor. Downloads no music; the install check proves playback.
 */
class UserPathLiveTest {
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
        val pick = pickTorrent(album, results) ?: pickTorrent(album, source.search(album.artist))

        assertNotNull("No release with seeds for $album among $results", pick)
        val info = TorrentInfo.bdecode(source.torrentFile(checkNotNull(pick)))
        assertTrue(info.isValid && info.numFiles() > 0)
    }
}
