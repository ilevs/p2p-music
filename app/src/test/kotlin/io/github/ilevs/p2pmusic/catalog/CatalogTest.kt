package io.github.ilevs.p2pmusic.catalog

import io.github.ilevs.p2pmusic.engine.SoulseekFile
import io.github.ilevs.p2pmusic.playback.AudioFile
import io.github.ilevs.p2pmusic.search.SearchResult
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class CatalogTest {
    @Test
    fun searchesReleaseGroups() {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse().setBody(RELEASE_GROUPS))
        val albums = MusicBrainz(OkHttpClient(), server.url("/ws/2/")).searchAlbums("кино группа крови")
        assertEquals(listOf(Album("6a1c", "Группа крови", "Кино & Друзья", "1988")), albums)
        val request = server.takeRequest()
        assertTrue(request.getHeader("User-Agent").orEmpty().startsWith("Peerlude/"))
        assertEquals("/ws/2/release-group", request.requestUrl?.encodedPath)
        server.shutdown()
    }

    @Test
    fun retriesWhenRateLimited() {
        val server = MockWebServer().apply { start() }
        repeat(BUSY_ANSWERS) { server.enqueue(MockResponse().setResponseCode(503)) }
        server.enqueue(MockResponse().setBody(RELEASE_GROUPS))
        val albums = MusicBrainz(OkHttpClient(), server.url("/ws/2/"), retryDelayMs = 0).searchAlbums("кино")
        assertEquals(1, albums.size)
        assertEquals(BUSY_ANSWERS + 1, server.requestCount)
        server.shutdown()
    }

    @Test
    fun retriesWhenThereIsNoAnswer() {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(MockResponse().setBody(RELEASE_GROUPS))
        val impatient = OkHttpClient.Builder().readTimeout(TIMEOUT_MS, TimeUnit.MILLISECONDS).build()
        val albums = MusicBrainz(impatient, server.url("/ws/2/"), retryDelayMs = 0).searchAlbums("кино")
        assertEquals(1, albums.size)
        assertEquals(2, server.requestCount)
        server.shutdown()
    }

    @Test
    fun ranksTheAlbumThenDiscographiesThenTheRestAndSkipsDeadReleases() {
        val album = Album("1", "Группа крови", "Кино", "1988")
        val discography = result("1", "Кино - Дискография (1982-1990) MP3", seeds = 90)
        val albumRelease = result("2", "Кино - Группа крови - 1988 (FLAC)", seeds = 5)
        val dead = result("3", "Кино - Группа крови (remaster)", seeds = 0)
        val other = result("4", "Кинопленка - Группа крови", seeds = 50)
        // The artist's most seeded release is another album: ilia got "Бутер Бродский 2" for
        // "Трудно быть с Богом" on 2026-11-28. It is looked into last, never played as the album.
        val hit = result("5", "Кино - 1989 - Звезда по имени Солнце [FLAC]", seeds = 900)
        assertEquals(
            listOf(albumRelease, discography, hit),
            rankTorrents(album, listOf(hit, discography, albumRelease, dead, other)),
        )
        assertEquals(emptyList<SearchResult>(), rankTorrents(album, listOf(dead, other)))
        // "ё" and "е" are the same letter to a tracker.
        val yo = Album("2", "Чёрный альбом", "Кино", "1990")
        assertTrue(namesAlbum(yo, result("6", "Кино - Черный альбом (1990)", seeds = 1)))
        assertFalse(namesAlbum(yo, hit))
    }

    @Test
    fun picksSoulseekFoldersWithAFreeSlotThenTheFastest() {
        val album = Album("1", "Группа крови", "Кино", "1988")

        fun file(
            user: String,
            path: String,
            slots: Int,
            speed: Int,
        ) = SoulseekFile(user, path, 1, slots, speed)
        val busy = file("busy", "Музыка\\Кино\\1988 Группа крови\\01 Группа крови.mp3", 0, 9_000_000)
        val slow2 = file("slow", "Кино\\Группа крови\\02 Закрой за мной дверь.mp3", 1, 100)
        val slow1 = file("slow", "Кино\\Группа крови\\01 Группа крови.mp3", 1, 100)
        val fast = file("fast", "Кино - Группа крови (1988)\\01.flac", 1, 5_000)
        val cover = file("fast", "Кино - Группа крови (1988)\\cover.jpg", 1, 5_000)
        val otherAlbum = file("other", "Кино\\Звезда по имени Солнце\\01 Группа крови.mp3", 1, 9_000)
        val otherArtist = file("cover", "Tribute\\Группа крови\\01.mp3", 1, 9_000)
        assertEquals(
            listOf(listOf(fast), listOf(slow1, slow2), listOf(busy)),
            soulseekFolders(album, listOf(busy, slow2, slow1, fast, cover, otherAlbum, otherArtist)),
        )
    }

    @Test
    fun keepsOnlyTheAlbumFolderOfADiscography() {
        val first = AudioFile(0, "Кино/1988 - Группа крови/01 Группа крови.mp3", 1)
        val second = AudioFile(1, "Кино/1988 - Группа крови/02 Закрой за мной дверь.mp3", 1)
        val other = AudioFile(2, "Кино/1989 - Звезда по имени Солнце/01 Песня.mp3", 1)
        val all = listOf(first, second, other)
        assertEquals(listOf(first, second), albumFiles("Группа крови", "Кино - Дискография", all))
        // A release named after the album keeps its files whatever its folders are called.
        assertEquals(listOf(other), albumFiles("Чёрный альбом", "Кино - Чёрный альбом (1990)", listOf(other)))
    }

    @Test
    fun playsNothingFromAReleaseWithoutTheAlbum() {
        // ilia tapped "Джон Леннон" (1983) and got the top John Lennon release, another album, on 2026-11-28.
        val imagine = AudioFile(0, "John Lennon - Imagine (2023) FLAC/Disc 1/01. Crippled Inside.flac", 1)
        val release = "John Lennon - Imagine (2023) FLAC"
        assertEquals(emptyList<AudioFile>(), albumFiles("Джон Леннон", release, listOf(imagine)))
        assertEquals("01. Crippled Inside", imagine.title)
    }

    private fun result(
        id: String,
        title: String,
        seeds: Int,
    ) = SearchResult(id, title, "", 0, seeds, 0)

    private companion object {
        const val TIMEOUT_MS = 200L
        const val BUSY_ANSWERS = 4
        val RELEASE_GROUPS =
            """
            {"release-groups": [{
              "id": "6a1c", "title": "Группа крови", "first-release-date": "1988-01-05",
              "artist-credit": [{"name": "Кино", "joinphrase": " & "}, {"name": "Друзья"}]
            }]}
            """.trimIndent()
    }
}
