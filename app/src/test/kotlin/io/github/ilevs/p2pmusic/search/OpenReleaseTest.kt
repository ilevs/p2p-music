package io.github.ilevs.p2pmusic.search

import io.github.ilevs.p2pmusic.engine.TorrentEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentInfo
import java.util.concurrent.TimeUnit

class OpenReleaseTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun doesNotWaitOutAMagnetWithoutPeersWhenTheTorrentFileIsThere() {
        val folder = temp.newFolder("album").apply { resolve("track.bin").writeBytes(ByteArray(SIZE) { it.toByte() }) }
        val bytes =
            TorrentBuilder()
                .path(folder)
                .flags(TorrentBuilder.V1_ONLY)
                .generate()
                .entry()
                .bencode()
        val hash = TorrentInfo.bdecode(bytes).infoHash().toHex()
        val source =
            object : TorrentSource {
                override val name = "fake"

                override fun search(query: String) = emptyList<SearchResult>()

                override fun torrentFile(result: SearchResult) = bytes
            }
        val result = SearchResult("1", "album", "", 0, 1, 0, magnet = "magnet:?xt=urn:btih:$hash")

        TorrentEngine(temp.newFolder("engine")).use { engine ->
            val start = System.nanoTime()
            val opened = source.open(result, engine)
            val ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

            assertEquals(hash, opened.infoHash().toHex())
            // The magnet alone would take its whole 20 s: nobody has this torrent.
            assertTrue("Opened in $ms ms", ms < HALF_THE_MAGNET_WAIT_MS)
        }
    }

    private companion object {
        const val SIZE = 100_000
        const val HALF_THE_MAGNET_WAIT_MS = 10_000
    }
}
