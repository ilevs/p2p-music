package io.github.ilevs.p2pmusic.search

import io.github.ilevs.p2pmusic.useEnglishTexts
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.Charset

class RuTrackerTest {
    init {
        useEnglishTexts()
    }

    private val cp1251 = Charset.forName("windows-1251")
    private val server = MockWebServer().apply { start() }
    private val client = trackerHttpClient(proxy = null, cookieJar = MemoryCookieJar())
    private val tracker =
        RuTracker(
            client = { client },
            credentials = { Credentials("Илья", "секрет") },
            baseUrl = server.url("/forum/"),
        )

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun logsInWhenNeededAndParsesResults() {
        server.enqueue(page("<html>Вход</html>"))
        server.enqueue(page(LOGGED_IN).addHeader("Set-Cookie", "bb_session=abc; Path=/"))
        server.enqueue(page(SEARCH_PAGE))

        val results = tracker.search("Кино")

        assertEquals(2, results.size)
        val first = results[0]
        assertEquals("6235487", first.id)
        assertEquals("Кино - Группа крови - 1988 (FLAC)", first.title)
        assertEquals("Русский рок (lossless)", first.category)
        assertEquals(398_413_824L, first.sizeBytes)
        assertEquals(15, first.seeds)
        assertEquals(2, first.leeches)
        assertEquals(0, results[1].seeds)

        val search = server.takeRequest()
        assertEquals("/forum/tracker.php?nm=%CA%E8%ED%EE", search.path)
        val login = server.takeRequest()
        assertEquals("/forum/login.php", login.path)
        assertTrue(login.body.readUtf8().startsWith("login_username=%C8%EB%FC%FF&login_password="))
        assertEquals("bb_session=abc", server.takeRequest().getHeader("Cookie"))
    }

    @Test
    fun reportsCaptcha() {
        server.enqueue(page("<input name=\"cap_sid\" value=\"x\">"))
        val error = runCatching { tracker.login() }.exceptionOrNull()
        assertTrue(error is TrackerLoginException)
        assertTrue(error?.message.orEmpty().contains("captcha"))
    }

    @Test
    fun reportsTheSiteErrorText() {
        server.enqueue(page("<h4 class=\"warnColor1 tCenter\">неверный пароль</h4><input name=\"login_username\">"))
        assertEquals("RuTracker: неверный пароль", runCatching { tracker.login() }.exceptionOrNull()?.message)
    }

    @Test
    fun tellsAForeignPageFromABadPassword() {
        server.enqueue(page("<title>Доступ ограничен</title>").setResponseCode(403))
        val message = runCatching { tracker.login() }.exceptionOrNull()?.message.orEmpty()
        assertTrue(message, message.contains("“Доступ ограничен” came back (HTTP 403)"))
    }

    @Test
    fun saysThatCloudflareAsksForABrowserNotThatTheProxyIsBroken() {
        // What ilia's working proxy got on 2026-11-28, at login and at search.
        repeat(2) { server.enqueue(page("<title>Just a moment...</title>").setResponseCode(403)) }
        val atLogin = runCatching { tracker.login() }.exceptionOrNull()?.message.orEmpty()
        val atSearch = runCatching { tracker.search("кино") }.exceptionOrNull()?.message.orEmpty()
        assertTrue(atLogin, "Cloudflare" in atLogin && "proxy" !in atLogin)
        assertTrue(atSearch, "Cloudflare" in atSearch)
    }

    @Test
    fun downloadsTorrentFile() {
        server.enqueue(MockResponse().setBody("d8:announce0:e"))
        val bytes = tracker.torrentFile(SearchResult("42", "", "", 0, 0, 0))
        assertEquals("d8:announce0:e", String(bytes))
        assertEquals("/forum/dl.php?t=42", server.takeRequest().path)
    }

    private fun page(html: String): MockResponse = MockResponse().setBody(Buffer().write(html.toByteArray(cp1251)))

    private companion object {
        const val LOGGED_IN = "<a id=\"logged-in-username\" href=\"profile.php\">Илья</a>"
        val SEARCH_PAGE =
            """
            <html><body>$LOGGED_IN
            <table id="tor-tbl"><tbody>
            <tr id="trs-tr-6235487" class="tCenter hl-tr" data-topic_id="6235487">
              <td class="row1 f-name-col"><div class="f-name"><a class="gen f" href="tracker.php?f=1">Русский рок (lossless)</a></div></td>
              <td class="row4 med tLeft t-title-col tt"><div class="wbr t-title">
                <a data-topic_id="6235487" class="med tLink tt-text" href="viewtopic.php?t=6235487">Кино - Группа крови - 1988 (FLAC)</a>
              </div></td>
              <td class="row4 small nowrap tor-size" data-ts_text="398413824"><a class="small tr-dl" href="dl.php?t=6235487">380 MB</a></td>
              <td class="row4 nowrap" data-ts_text="15"><b class="seedmed">15</b></td>
              <td class="row4 leechmed bold">2</td>
            </tr>
            <tr id="trs-tr-1" class="tCenter hl-tr">
              <td class="row4 med tLeft t-title-col tt"><div class="wbr t-title">
                <a class="med tLink tt-text" href="viewtopic.php?t=1">Кино - Звезда по имени Солнце (MP3)</a>
              </div></td>
              <td class="row4 small nowrap tor-size" data-ts_text="90000000"></td>
              <td class="row4 nowrap" data-ts_text="-3"><span class="seedmed">-3 дн.</span></td>
              <td class="row4 leechmed bold">0</td>
            </tr>
            </tbody></table></body></html>
            """.trimIndent()
    }
}
