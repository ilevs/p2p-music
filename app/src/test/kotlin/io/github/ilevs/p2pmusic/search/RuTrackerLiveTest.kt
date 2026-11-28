package io.github.ilevs.p2pmusic.search

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.libtorrent4j.TorrentInfo

private val FormType = "application/x-www-form-urlencoded".toMediaType()

/**
 * Talks to the real rutracker.org with the account from the RUTRACKER_LOGIN and
 * RUTRACKER_PASSWORD environment variables (GitHub secrets in CI). Skipped without them.
 */
class RuTrackerLiveTest {
    // What each address answers to a library and to a browser-like client, to read in the CI log:
    // ilia's proxy got Cloudflare's "Just a moment..." (HTTP 403) on 2026-11-28.
    @Test
    fun printsWhatTheSiteAnswers() {
        assumeTrue("LIVE_TESTS is not set", System.getenv("LIVE_TESTS") == "true")
        val plain = OkHttpClient()
        val browserLike = trackerHttpClient(proxy = null)
        // The pages the app uses: Cloudflare can guard the login and the search apart from the front page.
        val pages = listOf("index.php", "tracker.php?nm=test", "login.php")
        for (host in listOf("rutracker.org", "rutracker.net")) {
            for ((label, client) in listOf("plain" to plain, "browser-like" to browserLike)) {
                for (page in pages) {
                    val get = Request.Builder().url("https://$host/forum/$page")
                    println("ANSWER $host $label GET $page: ${answer(client, get)}")
                }
                val form = "login_username=nobody&login_password=nothing&login=x".toRequestBody(FormType)
                val post = Request.Builder().url("https://$host/forum/login.php").post(form)
                println("ANSWER $host $label POST login.php: ${answer(client, post)}")
            }
        }
    }

    private fun answer(
        client: OkHttpClient,
        request: Request.Builder,
    ): String =
        runCatching {
            client.newCall(request.build()).execute().use {
                val title = Jsoup.parse(String(checkNotNull(it.body).bytes(), Charsets.ISO_8859_1)).title()
                "HTTP ${it.code}, cf-mitigated=${it.header("cf-mitigated")}, server=${it.header("server")}, $title"
            }
        }.getOrElse { it.toString() }

    @Test
    fun searchesAndDownloadsATorrent() {
        val login = System.getenv("RUTRACKER_LOGIN").orEmpty()
        val password = System.getenv("RUTRACKER_PASSWORD").orEmpty()
        assumeTrue("No RuTracker account in the environment", login.isNotEmpty() && password.isNotEmpty())
        val client = trackerHttpClient(proxy = null, cookieJar = MemoryCookieJar())
        val tracker = RuTracker(client = { client }, credentials = { Credentials(login, password) })

        val results = tracker.search("Pink Floyd FLAC")
        assertTrue("No results", results.isNotEmpty())
        val best = results.maxBy { it.seeds }
        val info = TorrentInfo.bdecode(tracker.torrentFile(best))
        assertTrue(info.isValid && info.numFiles() > 0)
    }
}
