package io.github.ilevs.p2pmusic.catalog

import io.github.ilevs.p2pmusic.search.SearchResult
import io.github.ilevs.p2pmusic.search.TorrentSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class SearchCacheTest {
    private var calls = 0
    private var failing = false

    private val source =
        object : TorrentSource {
            override val name = "fake"

            override fun search(query: String): List<SearchResult> {
                calls++
                if (failing) throw IOException("down")
                return listOf(SearchResult(query, query, "", 0, 1, 0))
            }

            override fun torrentFile(result: SearchResult) = ByteArray(0)
        }

    @Test
    fun searchesOncePerQueryAndAgainAfterAFailure() {
        val cache = SearchCache(source)
        failing = true
        assertThrows(IOException::class.java) { cache.search("Кино") }
        failing = false

        assertEquals("Кино", cache.search("Кино").single().id)
        cache.search("Кино")
        cache.search("Сплин")

        assertEquals(3, calls)
    }
}
