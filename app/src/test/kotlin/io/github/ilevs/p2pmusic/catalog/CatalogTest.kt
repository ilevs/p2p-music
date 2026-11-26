package io.github.ilevs.p2pmusic.catalog

import io.github.ilevs.p2pmusic.playback.AudioFile
import io.github.ilevs.p2pmusic.search.SearchResult
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTest {
    @Test
    fun searchesReleaseGroups() {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse().setBody(RELEASE_GROUPS))
        val albums = MusicBrainz(OkHttpClient(), server.url("/ws/2/")).searchAlbums("кино группа крови")
        assertEquals(listOf(Album("6a1c", "Группа крови", "Кино & Друзья", "1988")), albums)
        val request = server.takeRequest()
        assertTrue(request.getHeader("User-Agent").orEmpty().startsWith("P2PMusic/"))
        assertEquals("/ws/2/release-group", request.requestUrl?.encodedPath)
        server.shutdown()
    }

    @Test
    fun picksTheAlbumOverADiscographyAndSkipsDeadReleases() {
        val album = Album("1", "Группа крови", "Кино", "1988")
        val discography = result("1", "Кино - Дискография (1982-1990) MP3", seeds = 90)
        val albumRelease = result("2", "Кино - Группа крови - 1988 (FLAC)", seeds = 5)
        val dead = result("3", "Кино - Группа крови (remaster)", seeds = 0)
        val other = result("4", "Кинопленка - Группа крови", seeds = 50)
        assertEquals(albumRelease, pickTorrent(album, listOf(discography, albumRelease, dead, other)))
        assertEquals(discography, pickTorrent(album, listOf(discography, dead, other)))
        assertNull(pickTorrent(album, listOf(dead, other)))
    }

    @Test
    fun keepsOnlyTheAlbumFolderOfADiscography() {
        val first = AudioFile(0, "Кино/1988 - Группа крови/01 Группа крови.mp3", 1)
        val second = AudioFile(1, "Кино/1988 - Группа крови/02 Закрой за мной дверь.mp3", 1)
        val other = AudioFile(2, "Кино/1989 - Звезда по имени Солнце/01 Песня.mp3", 1)
        assertEquals(listOf(first, second), albumFiles("Группа крови", listOf(first, second, other)))
        assertEquals(listOf(other), albumFiles("Чёрный альбом", listOf(other)))
    }

    private fun result(
        id: String,
        title: String,
        seeds: Int,
    ) = SearchResult(id, title, "", 0, seeds, 0)

    private companion object {
        val RELEASE_GROUPS =
            """
            {"release-groups": [{
              "id": "6a1c", "title": "Группа крови", "first-release-date": "1988-01-05",
              "artist-credit": [{"name": "Кино", "joinphrase": " & "}, {"name": "Друзья"}]
            }]}
            """.trimIndent()
    }
}
