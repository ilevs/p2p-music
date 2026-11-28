package io.github.ilevs.p2pmusic.search

import okhttp3.Request
import org.jsoup.Jsoup
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Prints what the real nnmclub.to answers, to read in the CI log: the sandbox cannot reach it. */
class NnmClubLiveTest {
    @Test
    fun printsWhatTheSiteAnswers() {
        assumeTrue("LIVE_TESTS is not set", System.getenv("LIVE_TESTS") == "true")
        val client = trackerHttpClient(proxy = null)
        val nnm = NnmClub(client = { client })
        for (query in listOf("Pink Floyd Wish You Were Here", "Слава КПСС", "Джон Гарик")) {
            val found = runCatching { nnm.search(query) }
            println("NNM $query: ${found.map { all -> all.take(SHOWN) }}")
            val first = found.getOrNull()?.firstOrNull() ?: continue
            println("NNM magnet of ${first.id}: ${runCatching { nnm.magnet(first) }}")
        }
        val request = Request.Builder().url("https://nnmclub.to/forum/tracker.php?nm=Pink+Floyd").build()
        val (code, html) =
            client.newCall(request).execute().use {
                it.code to String(checkNotNull(it.body).bytes(), charset("windows-1251"))
            }
        val rows = Jsoup.parse(html).select("tr:has(a[href*=viewtopic.php?t=])")
        println("NNM raw HTTP $code rows=${rows.size} ${rows.firstOrNull()?.outerHtml()?.take(RAW)}")
    }

    private companion object {
        const val SHOWN = 5
        const val RAW = 3000
    }
}
