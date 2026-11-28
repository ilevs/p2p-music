package io.github.ilevs.p2pmusic.catalog

import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * How many albums of a fixed set each catalog lists when searched for "artist album", the way the user searches,
 * and with all catalogs merged as the app shows them. Prints METRIC lines only; live numbers are noisy.
 */
class CatalogCoverageLiveTest {
    @Test
    fun countsTheAlbumsListed() {
        assumeTrue("LIVE_TESTS is not set", System.getenv("LIVE_TESTS") == "true")
        val client = OkHttpClient()
        val catalogs =
            listOf(
                "musicbrainz" to MusicBrainz(client)::searchAlbums,
                "itunes" to ITunes(client)::searchAlbums,
                "deezer" to Deezer(client)::searchAlbums,
            )
        val answers =
            catalogs.map { (name, search) ->
                val times = mutableListOf<Long>()
                val lists =
                    Albums.map { (artist, title) ->
                        val start = System.nanoTime()
                        val found = runCatching { search("$artist $title") }
                        times += TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
                        found.exceptionOrNull()?.let { println("FAIL $name: $artist / $title: $it") }
                        found.getOrDefault(emptyList())
                    }
                report(name, lists)
                println("METRIC catalog_search_median_ms_$name=${times.sorted()[times.size / 2]}")
                lists
            }
        report("all", Albums.indices.map { i -> mergeAlbums(answers.map { it[i] }) })
        // Fresh Russian rap the user asked about on 2026-11-28, by artist alone: which albums each catalog has.
        for ((name, search) in catalogs) {
            val found = runCatching { search("Джон Гарик") }.map { list -> list.map { "${it.artist} / ${it.title}" } }
            println("ARTIST $name «Джон Гарик»: $found")
        }
    }

    private fun report(
        name: String,
        lists: List<List<Album>>,
    ) {
        val listed =
            Albums.indices.count { i ->
                val (artist, title) = Albums[i]
                val hit =
                    lists[i].any {
                        normalize(artist) in normalize(it.artist) && normalize(title) in normalize(it.title)
                    }
                if (!hit) println("MISS $name: $artist / $title")
                hit
            }
        println("METRIC catalog_albums_listed_$name=$listed/${Albums.size}")
    }

    private companion object {
        // The set of SearchCoverageLiveTest, then fresher and niche Russian albums that a volunteer catalog may lack.
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
                "Слава КПСС" to "Трудно быть с Богом",
                "Oxxxymiron" to "Красота и уродство",
                "Монеточка" to "Раскраски для взрослых",
                "Скриптонит" to "Уроборос",
                "Little Big" to "Antipositive",
            )
    }
}
