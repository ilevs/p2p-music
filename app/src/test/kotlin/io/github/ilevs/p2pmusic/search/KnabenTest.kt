package io.github.ilevs.p2pmusic.search

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class KnabenTest {
    private val server = MockWebServer().apply { start() }
    private val client = trackerHttpClient(proxy = null)
    private val knaben = Knaben(client = { client }, apiUrl = server.url("/v1"))

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun asksForEveryWordInAudioAndParsesHits() {
        server.enqueue(MockResponse().setBody(ANSWER.replace("LINK", server.url("/dl").toString())))

        val results = knaben.search("Кино Группа крови")

        val request = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("100%", request.getString("search_type"))
        assertEquals("Кино Группа крови", request.getString("query"))
        assertEquals(1_000_000, request.getJSONArray("categories").getInt(0))
        assertEquals(
            listOf(
                SearchResult(
                    server.url("/dl").toString(),
                    "Кино - Группа Крови - 1988, FLAC (tracks)",
                    "RuTracker.org",
                    1_073_055_872L,
                    12,
                    3,
                    magnet = "magnet:?xt=urn:btih:F7CEE995ECC3007D4B592B9618254E1203517F70",
                ),
                SearchResult(server.url("/dl").toString(), "Кино - Группа Крови - 2019", "RuTracker.org", 5L, 2, 0),
            ),
            results,
        )
    }

    @Test
    fun downloadsTheTorrentByTheLink() {
        server.enqueue(MockResponse().setBody("d8:announce0:e"))
        val bytes = knaben.torrentFile(SearchResult(server.url("/dl?path=x").toString(), "", "", 0, 0, 0))
        assertEquals("d8:announce0:e", String(bytes))
        assertEquals("/dl?path=x", server.takeRequest().path)
    }

    private companion object {
        // Trimmed from a real answer of 2026-11-28; the hit without a link is dropped.
        val ANSWER =
            """
            {"hits": [
              {"bytes": 1073055872, "hash": "F7CEE995ECC3007D4B592B9618254E1203517F70", "link": "LINK",
               "peers": 3, "seeders": 12, "title": "Кино - Группа Крови - 1988, FLAC (tracks)",
               "tracker": "RuTracker.org"},
              {"bytes": 5, "hash": null, "link": "LINK", "peers": 0, "seeders": 2,
               "title": "Кино - Группа Крови - 2019", "tracker": "RuTracker.org"},
              {"bytes": 5, "hash": null, "link": null, "seeders": 9, "title": "Кино", "tracker": "RuTracker.org"}
            ]}
            """.trimIndent()
    }
}
