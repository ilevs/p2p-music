package io.github.ilevs.p2pmusic.catalog

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class StoreCatalogsTest {
    @Test
    fun searchesDeezer() {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse().setBody(DEEZER))
        val albums = Deezer(OkHttpClient(), server.url("/")).searchAlbums("слава кпсс")
        assertEquals(listOf(Album("deezer:42", "Трудно быть с Богом", "Слава КПСС", "", "https://e/250.jpg")), albums)
        assertEquals("/search/album", server.takeRequest().requestUrl?.encodedPath)
        server.shutdown()
    }

    @Test
    fun failsOnADeezerError() {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse().setBody("""{"error":{"type":"Exception","message":"Quota limit exceeded"}}"""))
        val error = assertThrows(IOException::class.java) { Deezer(OkHttpClient(), server.url("/")).searchAlbums("x") }
        assertEquals("Deezer: Quota limit exceeded", error.message)
        server.shutdown()
    }

    @Test
    fun searchesITunes() {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse().setBody(ITUNES))
        val albums = ITunes(OkHttpClient(), server.url("/")).searchAlbums("pink floyd")
        val dsotm = Album("itunes:7", "The Dark Side of the Moon", "Pink Floyd", "1973", "https://a/250x250bb.jpg")
        assertEquals(listOf(dsotm), albums)
        assertEquals("album", server.takeRequest().requestUrl?.queryParameter("entity"))
        server.shutdown()
    }

    @Test
    fun namesTheAlbumWithoutTheEdition() {
        assertEquals("Discovery", storeTitle("Discovery"))
        assertEquals("OK Computer", storeTitle("OK Computer (Remastered) [Deluxe Edition]"))
        assertEquals("Этажи", storeTitle("Этажи - EP"))
        assertEquals("(What's the Story) Morning Glory?", storeTitle("(What's the Story) Morning Glory?"))
        assertEquals("(Untitled)", storeTitle("(Untitled)"))
    }

    @Test
    fun mergesCatalogsInTurnAndShowsEachAlbumOnce() {
        val mb = listOf(Album("1", "Группа крови", "Кино", "1988"), Album("2", "Tribute", "Разные", ""))
        val itunes = listOf(Album("itunes:3", "Группа Крови", "КИНО", "1988"), Album("itunes:4", "Звезда", "Кино", ""))
        val deezer = listOf(Album("deezer:5", "Чёрный альбом", "Кино", ""))
        assertEquals(listOf("1", "deezer:5", "2", "itunes:4"), mergeAlbums(listOf(mb, itunes, deezer)).map { it.id })
        assertEquals(emptyList<Album>(), mergeAlbums(emptyList()))
        val undated = Album("deezer:6", "The Dark Side of the Moon", "Pink Floyd", "")
        val dated = Album("7", "The Dark Side of the Moon", "Pink Floyd", "1973")
        assertEquals(listOf(undated.copy(year = "1973")), mergeAlbums(listOf(listOf(undated), listOf(dated))))
    }

    private companion object {
        val DEEZER =
            """
            {"data":[{"id":42,"title":"Трудно быть с Богом (Deluxe)","cover_medium":"https://e/250.jpg",
            "artist":{"id":1,"name":"Слава КПСС"},"record_type":"album"}],"total":1}
            """.trimIndent()
        val ITUNES =
            """
            {"resultCount":1,"results":[{"wrapperType":"collection","collectionId":7,
            "collectionName":"The Dark Side of the Moon (2011 Remaster)","artistName":"Pink Floyd",
            "releaseDate":"1973-03-01T08:00:00Z","artworkUrl100":"https://a/100x100bb.jpg"}]}
            """.trimIndent()
    }
}
