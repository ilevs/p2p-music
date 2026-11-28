package io.github.ilevs.p2pmusic.search

import org.junit.Assert.assertFalse
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
        // The fourth is the album the emulators play from here: only NNM-Club had it on 2026-11-28.
        val queries = listOf("Pink Floyd Wish You Were Here", "Слава КПСС", "Джон Гарик", "Little Big Antipositive")
        for (query in queries) {
            val found = runCatching { nnm.search(query) }
            println("NNM $query: ${found.map { all -> all.take(SHOWN).map { "${it.seeds} ${it.title}" } }}")
            // The story of the album is a film: the search must stay in the music forums.
            assertFalse("A film among $found", found.getOrDefault(emptyList()).any { "BDRip" in it.title })
            val first = found.getOrNull()?.firstOrNull() ?: continue
            println("NNM magnet of ${first.id}: ${runCatching { nnm.magnet(first) }}")
        }
    }

    private companion object {
        const val SHOWN = 8
    }
}
