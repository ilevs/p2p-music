package io.github.ilevs.p2pmusic.search

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

    private companion object {
        const val SHOWN = 8
    }
}
