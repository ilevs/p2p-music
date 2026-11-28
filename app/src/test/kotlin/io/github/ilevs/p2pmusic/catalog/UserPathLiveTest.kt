package io.github.ilevs.p2pmusic.catalog

import io.github.ilevs.p2pmusic.engine.TorrentEngine
import io.github.ilevs.p2pmusic.search.AllSources
import io.github.ilevs.p2pmusic.search.NnmClub
import io.github.ilevs.p2pmusic.search.Rutor
import io.github.ilevs.p2pmusic.search.SearchResult
import io.github.ilevs.p2pmusic.search.TorrentSource
import io.github.ilevs.p2pmusic.search.open
import io.github.ilevs.p2pmusic.search.prefetch
import io.github.ilevs.p2pmusic.search.trackerHttpClient
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.libtorrent4j.TorrentInfo
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
            checkNotNull(rankTorrents(album, results).firstOrNull()) {
                "No release with seeds for $album among $results"
            }

        // What the trackers hold for the album ilia could not open on 2026-11-28: read in the CI log.
        val hard = Album("", "Трудно быть с Богом", "Слава КПСС", "")
        val exact = runCatching { source.search("${hard.artist} ${hard.title}") }
        val wide = runCatching { source.search(hard.artist) }
        println("HARD exact: ${exact.map { found -> found.map { "${it.seeds} ${it.title}" } }}")
        println("HARD artist: ${wide.map { found -> rankTorrents(hard, found).map { "${it.seeds} ${it.title}" } }}")

        val (info, torrentMs) = timed { TorrentInfo.bdecode(source.torrentFile(pick)) }
        assertTrue(info.isValid && info.numFiles() > 0)

        // The magnet alone, for as long as the app waits for it before it takes the .torrent.
        val (magnet, coldMs) =
            TorrentEngine(temp.newFolder()).use { alone ->
                timed { runCatching { alone.openMagnet(pick.magnet, MAGNET_FIRST_MS).infoHash() } }
            }
        // The way the app opens a release: the magnet first, then the .torrent.
        val (hash, openMs) =
            TorrentEngine(temp.newFolder()).use { engine -> timed { source.open(pick, engine).infoHash() } }
        assertEquals(info.infoHash(), hash)
        val warmMs = warmOpenMs(source, pick)

        // NNM-Club opens by its magnet alone: what the prefetch is for. The album only it had on 2026-11-28.
        val nnm = NnmClub(client = { client })
        val nnmPick = nnm.search("Little Big Antipositive").maxByOrNull { it.seeds }
        println("NNM pick: $nnmPick")
        if (nnmPick != null) {
            // Cold and warm in turn, several times: one run each swung 3.5 s against 20 s with the swarm's mood.
            val runs =
                List(NNM_RUNS) {
                    val (_, coldMs) =
                        launched().use { engine -> timed { runCatching { nnm.open(nnmPick, engine) } } }
                    coldMs to warmOpenMs(nnm, nnmPick, launched())
                }
            println("NNM cold/warm ms: $runs")
            println("METRIC nnm_release_open_cold_ms=${runs.map { it.first }.sorted()[NNM_RUNS / 2]}")
            println("METRIC nnm_release_open_warm_ms=${runs.map { it.second }.sorted()[NNM_RUNS / 2]}")
        }

        // Collected into the CI job summary, to compare builds.
        println("METRIC torrent_file_ms=$torrentMs")
        println("METRIC magnet_metadata_cold_ms=${if (magnet.isSuccess) coldMs else "none_in_${MAGNET_FIRST_MS}_ms"}")
        println("METRIC release_open_ms=$openMs")
        println("METRIC release_open_warm_ms=$warmMs")
    }

    // The tap's open after the app prefetched the release while the user looked at the albums for USER_LOOKS_MS.
    private fun warmOpenMs(
        source: TorrentSource,
        pick: SearchResult,
        started: TorrentEngine = TorrentEngine(temp.newFolder()),
    ): Long =
        started.use { engine ->
            source.prefetch(pick, engine)
            Thread.sleep(USER_LOOKS_MS)
            timed { runCatching { source.open(pick, engine) } }.second
        }

    // An engine as the app has it at a tap: the session started at launch and the DHT up.
    private fun launched(): TorrentEngine =
        TorrentEngine(temp.newFolder()).also { engine ->
            engine.setSeeding(true)
            engine.awaitUntil(DHT_WAIT_MS) { engine.dhtNodes > 0 }
        }

    private fun <T> timed(block: () -> T): Pair<T, Long> {
        val start = System.nanoTime()
        return block() to TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
    }

    private companion object {
        const val MAGNET_FIRST_MS = 20_000L

        // shortcut: a guess at how long a user reads the album list; measure it on a device if the numbers matter.
        const val USER_LOOKS_MS = 5_000L
        const val NNM_RUNS = 3
        const val DHT_WAIT_MS = 15_000L
    }
}
