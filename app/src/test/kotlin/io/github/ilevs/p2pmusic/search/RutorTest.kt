package io.github.ilevs.p2pmusic.search

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class RutorTest {
    private val server = MockWebServer().apply { start() }
    private val client = trackerHttpClient(proxy = null)
    private val rutor = Rutor(client = { client }, baseUrl = server.url("/"), downloadUrl = server.url("/download/"))

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun searchesTheMusicSectionAndParsesRows() {
        server.enqueue(MockResponse().setBody(SEARCH_PAGE))

        val results = rutor.search("Кино 1988")

        assertEquals("/search/0/2/300/0/%D0%9A%D0%B8%D0%BD%D0%BE%201988", server.takeRequest().path)
        assertEquals(
            listOf(
                SearchResult("373821", "Кино - Группа крови (1988) FLAC", "Rutor", 841_482_240L, 12, 3),
                SearchResult("449720", "Кино - Звезда по имени Солнце (1989) MP3", "Rutor", 1_589_137_899L, 0, 1),
            ),
            results,
        )
    }

    @Test
    fun downloadsTorrentFile() {
        server.enqueue(MockResponse().setBody("d8:announce0:e"))
        val bytes = rutor.torrentFile(SearchResult("42", "", "", 0, 0, 0))
        assertEquals("d8:announce0:e", String(bytes))
        assertEquals("/download/42", server.takeRequest().path)
    }

    @Test
    fun rejectsAPageInsteadOfATorrent() {
        server.enqueue(MockResponse().setBody("<html>Just a moment...</html>"))
        assertThrows(IOException::class.java) { rutor.torrentFile(SearchResult("42", "", "", 0, 0, 0)) }
    }

    @Test
    fun parsesSizes() {
        assertEquals(841_482_240L, parseSize("802.50 MB"))
        assertEquals(1_536L, parseSize("1.5 KB"))
        assertEquals(0L, parseSize("12"))
    }

    private companion object {
        // Trimmed from a rutor.info search page: a header row, a row without and a row with a comments cell.
        val SEARCH_PAGE =
            """
            <table width="100%"><tr class="backgr"><td width="10px">Добавлен</td><td colspan="2">Название</td>
            <td width="1px">Размер</td><td width="1px">Пиры</td></tr>
            <tr class="gai"><td>23&nbsp;Авг&nbsp;14</td><td colspan="2">
            <a class="downgif" href="//d.rutor.info/download/373821"><img src="/s/t/d.gif" alt="D"></a>
            <a href="magnet:?xt=urn:btih:2a568ddd17e56ce9c655ff99f7eea7022d1bf721"><img src="/s/t/m.png" alt="M"></a>
            <a href="/torrent/373821/kino-gruppa-krovi">Кино - Группа крови (1988) FLAC</a></td>
            <td align="right">802.50&nbsp;MB</td><td align="center"><span class="green"><img src="/s/t/arrowup.gif">&nbsp;12</span>&nbsp;<img src="/s/t/arrowdown.gif"><span class="red">&nbsp;3</span></td></tr>
            <tr class="tum"><td>08&nbsp;Авг&nbsp;15</td><td>
            <a class="downgif" href="//d.rutor.info/download/449720"><img src="/s/t/d.gif" alt="D"></a>
            <a href="magnet:?xt=urn:btih:5fc1d440de45a191f7e284ad4b58156c3acb0e0b"><img src="/s/t/m.png" alt="M"></a>
            <a href="/torrent/449720/kino-zvezda">Кино - Звезда по имени Солнце (1989) MP3</a></td>
            <td align="right">7<img src="/s/t/com.gif"></td>
            <td align="right">1.48&nbsp;GB</td><td align="center"><span class="green"><img src="/s/t/arrowup.gif">&nbsp;0</span>&nbsp;<img src="/s/t/arrowdown.gif"><span class="red">&nbsp;1</span></td></tr>
            </table>
            """.trimIndent()
    }
}
