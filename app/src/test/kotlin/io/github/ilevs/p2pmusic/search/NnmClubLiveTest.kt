package io.github.ilevs.p2pmusic.search

import io.github.ilevs.p2pmusic.catalog.Album
import io.github.ilevs.p2pmusic.catalog.namesAlbum
import io.github.ilevs.p2pmusic.catalog.rankTorrents
import okhttp3.Request
import org.jsoup.Jsoup
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Prints what the real nnmclub.to answers, to read in the CI log: the sandbox cannot reach it. */
class NnmClubLiveTest {
    @Test
    fun printsWhatTheSiteFinds() {
        assumeTrue("LIVE_TESTS is not set", System.getenv("LIVE_TESTS") == "true")
        val client = trackerHttpClient(proxy = null)
        val nnm = NnmClub(client = { client })
        // The second and third are the artists ilia did not find on 2026-11-28.
        for (query in listOf("Pink Floyd Wish You Were Here", "Слава КПСС", "Джон Гарик")) {
            val found = runCatching { nnm.search(query) }
            println("NNM $query: ${found.map { all -> all.take(SHOWN).map { "${it.seeds} ${it.title}" } }}")
            val first = found.getOrNull()?.firstOrNull() ?: continue
            println("NNM magnet of ${first.id}: ${runCatching { nnm.magnet(first) }}")
        }
    }

    // Probe, removed before the merge: Latin-named albums only NNM-Club has (the emulator types ASCII only),
    // and the form fields that could limit the search to music.
    @Test
    fun printsProbe() {
        assumeTrue("LIVE_TESTS is not set", System.getenv("LIVE_TESTS") == "true")
        val client = trackerHttpClient(proxy = null)
        val sources = listOf(Rutor(client = { client }), Knaben(client = { client }), NnmClub(client = { client }))
        for ((artist, title) in Candidates) {
            val album = Album("", title, artist, "")
            val seeds =
                sources.map { source ->
                    val found = runCatching { source.search("$artist $title") }.getOrDefault(emptyList())
                    rankTorrents(album, found).filter { namesAlbum(album, it) }.maxOfOrNull { it.seeds }
                }
            println("PROBE $artist / $title: rutor, knaben, nnm seeds = $seeds")
        }
        val request = Request.Builder().url("https://nnmclub.to/forum/tracker.php").build()
        val html =
            client.newCall(request).execute().use { String(checkNotNull(it.body).bytes(), charset("windows-1251")) }
        for (select in Jsoup.parse(html).select("select")) {
            val options = select.select("option").map { "${it.attr("value")}=${it.text()}" }
            println("PROBE select ${select.attr("name")} (${options.size}): ${options.take(OPTIONS)}")
            for (group in select.select("optgroup")) {
                val inside = group.select("option").map { "${it.attr("value")}=${it.text()}" }
                println("PROBE group ${group.attr("label")}: $inside")
            }
        }
    }

    private companion object {
        const val SHOWN = 8
        const val OPTIONS = 40
        val Candidates =
            listOf(
                "Little Big" to "Antipositive",
                "Motorama" to "Alps",
                "Motorama" to "Calendar",
                "Pompeya" to "Tropical",
                "Tesla Boy" to "Modern Thrills",
                "Therr Maitz" to "Unicorn",
                "Mujuice" to "Downshifting",
                "Shortparis" to "Easter",
                "Kedr Livanskiy" to "Ariadna",
                "Messer Chups" to "Crazy Price",
                "Burial" to "Untrue",
                "Boards of Canada" to "Geogaddi",
                "Dead Can Dance" to "Spiritchaser",
                "Morphine" to "Cure for Pain",
                "Swans" to "To Be Kind",
                "Can" to "Tago Mago",
            )
    }
}
