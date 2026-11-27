package io.github.ilevs.p2pmusic.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException

class AllSourcesTest {
    private class FakeSource(
        override val name: String,
        private val results: () -> List<SearchResult>,
    ) : TorrentSource {
        override fun search(query: String) = results()

        override fun torrentFile(result: SearchResult) = name.toByteArray()
    }

    private val found = SearchResult("1", "Кино", "", 0, 5, 0)
    private val working = FakeSource("Rutor") { listOf(found) }
    private val blocked = FakeSource("RuTracker") { throw IOException("Cloudflare") }

    @Test
    fun skipsAFailingSourceAndTagsResults() {
        val results = AllSources(listOf(blocked, working)).search("Кино")
        assertEquals(listOf(found.copy(source = "Rutor")), results)
    }

    @Test
    fun reportsTheErrorWhenNothingIsFound() {
        val empty = FakeSource("Rutor") { emptyList() }
        val error = assertThrows(IOException::class.java) { AllSources(listOf(blocked, empty)).search("Кино") }
        assertEquals("RuTracker: Cloudflare; Rutor: ничего не найдено", error.message)
    }

    @Test
    fun downloadsFromTheSourceThatFoundTheResult() {
        val sources = AllSources(listOf(blocked, working))
        assertEquals("Rutor", String(sources.torrentFile(found.copy(source = "Rutor"))))
        assertEquals("RuTracker, Rutor", sources.name)
    }

    @Test
    fun namesTheSourceWhenTheTorrentDoesNotDownload() {
        val slow =
            object : TorrentSource {
                override val name = "Rutor"

                override fun search(query: String) = listOf(found)

                override fun torrentFile(result: SearchResult): ByteArray = throw InterruptedIOException("timeout")
            }
        val error =
            assertThrows(IOException::class.java) { AllSources(listOf(slow)).torrentFile(found.copy(source = "Rutor")) }
        assertEquals("Rutor: .torrent не скачался: timeout", error.message)
    }
}
