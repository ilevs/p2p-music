package io.github.ilevs.p2pmusic.catalog

import io.github.ilevs.p2pmusic.search.AllSources
import io.github.ilevs.p2pmusic.search.Knaben
import io.github.ilevs.p2pmusic.search.Rutor
import io.github.ilevs.p2pmusic.search.SearchResult
import io.github.ilevs.p2pmusic.search.TorrentSource
import io.github.ilevs.p2pmusic.search.trackerHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * How many albums of a fixed set the search finds, per source and with all of them, searched the way the
 * catalog does: "artist album", then the artist alone. Prints METRIC lines only; live numbers are noisy.
 */
class SearchCoverageLiveTest {
    @Test
    fun countsTheAlbumsFound() {
        assumeTrue("LIVE_TESTS is not set", System.getenv("LIVE_TESTS") == "true")
        val client = trackerHttpClient(proxy = null)
        val rutor = Rutor(client = { client })
        val knaben = Knaben(client = { client })
        // "all" is the app's own setup: Knaben only when the trackers find nothing.
        for (source in listOf(rutor, knaben, AllSources(listOf(rutor), fallback = listOf(knaben)))) {
            val times = mutableListOf<Long>()
            val found =
                Albums.filter { album ->
                    val start = System.nanoTime()
                    val exact = searched(source, "${album.artist} ${album.title}")
                    times += TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                    val hit = holds(album, exact) || holds(album, searched(source, album.artist))
                    if (!hit) println("MISS ${label(source)}: ${album.artist} / ${album.title}")
                    hit
                }
            println("METRIC search_albums_found_${label(source)}=${found.size}/${Albums.size}")
            println("METRIC search_album_median_ms_${label(source)}=${times.sorted()[times.size / 2]}")
        }
    }

    private fun searched(
        source: TorrentSource,
        query: String,
    ) = runCatching { source.search(query) }.getOrDefault(emptyList())

    private fun holds(
        album: Album,
        results: List<SearchResult>,
    ) = rankTorrents(album, results).any { namesAlbum(album, it) }

    private fun label(source: TorrentSource) = if (source is AllSources) "all" else source.name.lowercase()

    private companion object {
        // Russian and Western, popular and niche; the set of the 2026-11-28 source research.
        val Albums =
            listOf(
                "Кино" to "Группа крови",
                "Сплин" to "Гранатовый альбом",
                "Земфира" to "Прости меня моя любовь",
                "Би-2" to "Мяу кисс ми",
                "Король и Шут" to "Акустический альбом",
                "Аквариум" to "Радио Африка",
                "Pink Floyd" to "The Dark Side of the Moon",
                "Radiohead" to "OK Computer",
                "Massive Attack" to "Mezzanine",
                "Daft Punk" to "Discovery",
                "Burial" to "Untrue",
                "Molchat Doma" to "Этажи",
            ).map { (artist, title) -> Album("", title, artist, "") }
    }
}
