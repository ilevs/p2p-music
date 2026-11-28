package io.github.ilevs.p2pmusic.search

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class NnmClubTest {
    private val server = MockWebServer().apply { start() }
    private val client = trackerHttpClient(proxy = null)
    private val nnm = NnmClub(client = { client }, baseUrl = server.url("/forum/"))

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun asksInUtf8AndParsesAWindows1251Page() {
        server.enqueue(page(SEARCH))

        val results = nnm.search("Кино Группа крови")

        assertEquals("Кино Группа крови", server.takeRequest().requestUrl?.queryParameter("nm"))
        val title = "Кино - Группа Крови (1988) Reissue, Remastered, 1996, Moroz Records [OGG Vorbis] <Rock>"
        assertEquals(listOf(SearchResult("1578218", title, "Русский рок", 172_680_341L, 4, 0)), results)
    }

    @Test
    fun takesTheMagnetFromTheTopic() {
        val magnet = "magnet:?xt=urn:btih:F76CD6FA7C0B48814981C4058717B4652D85BF83"
        server.enqueue(page("""<a rel="nofollow" href="$magnet">m</a>"""))

        assertEquals(magnet, nnm.magnet(SearchResult("484794", "", "", 0, 0, 0)))
        assertEquals("/forum/viewtopic.php?t=484794", server.takeRequest().path)
    }

    private fun page(html: String) = MockResponse().setBody(Buffer().write(html.toByteArray(charset("windows-1251"))))

    private companion object {
        // A row of a real answer of 2026-11-28, shortened.
        val SEARCH =
            """
            <table><tr class="prow1">
             <td align="center"><img src="icon_minipost.gif" width="12" height="9" alt="1" title="1"></td>
             <td align="center"><a class="gen" href="tracker.php?f=332&amp;nm=x">Русский рок</a></td>
             <td title="" class="genmed"><a class="genmed topictitle" href="viewtopic.php?t=1578218"><b>Кино -
              Группа Крови (1988) Reissue, Remastered, 1996, Moroz Records [OGG Vorbis] &lt;Rock&gt;</b></a>&nbsp;</td>
             <td align="center"><a class="genmed" href="tracker.php?pid=7439795">enigmatick</a></td>
             <td align="center" nowrap><a href="download.php?id=1231845" class="genmed">[ <b>DL</b> ]</a></td>
             <td align="center" title="" nowrap class="gensmall"><u>172680341</u> 165 MB</td>
             <td align="center" title="Seeders" class="seedmed"><b>4</b></td>
             <td align="center" title="Leechers" class="leechmed"><b>0</b></td>
             <td align="center" title="1235 Просмотров" class="gensmall">0</td>
             <td align="center" nowrap class="gensmall"><u>1663793905</u> 21-09-2022<br>23:58</td>
            </tr></table>
            """.trimIndent()
    }
}
