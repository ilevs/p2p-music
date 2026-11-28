package io.github.ilevs.p2pmusic.search

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.URLEncoder

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
        // Which form of a Cyrillic query the site understands; a wrong one answers with the newest topics.
        val url = "https://nnmclub.to/forum/tracker.php"
        for (charset in listOf("windows-1251", "UTF-8")) {
            val query = "nm=" + URLEncoder.encode("Кино Группа крови", charset)
            val post = Request.Builder().url(url).post(query.toRequestBody(FormType))
            for ((how, request) in listOf("GET" to Request.Builder().url("$url?$query"), "POST" to post)) {
                val html =
                    client.newCall(request.build()).execute().use {
                        String(checkNotNull(it.body).bytes(), charset("windows-1251"))
                    }
                println("NNM $how $charset: ${parseNnmSearch(html).take(SHOWN).map { it.title }}")
                if (how == "GET") println("NNM row: ${Jsoup.parse(html).selectFirst("tr.prow1")?.outerHtml()}")
            }
        }
    }

    private companion object {
        const val SHOWN = 5
        val FormType = "application/x-www-form-urlencoded".toMediaType()
    }
}
