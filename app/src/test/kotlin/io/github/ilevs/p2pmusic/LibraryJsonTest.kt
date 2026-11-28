package io.github.ilevs.p2pmusic

import io.github.ilevs.p2pmusic.catalog.Album
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

class LibraryJsonTest {
    @Test
    fun savedAlbumsSurviveTheRoundTrip() {
        val albums =
            listOf(
                SavedAlbum(Album("1", "Группа крови", "Кино", "1988"), "a".repeat(HASH_LENGTH)),
                SavedAlbum(Album("2", "Untitled", "", ""), "b".repeat(HASH_LENGTH)),
            )
        assertEquals(albums, parseLibrary(JSONArray(albums.map { it.toJson() }).toString()))
    }

    @Test
    fun exportEntriesKeepExtraFields() {
        val saved = SavedAlbum(Album("deezer:1", "A", "B", "2000", "https://e/c.jpg"), "c".repeat(HASH_LENGTH))
        assertEquals(saved, saved.toJson().put("torrent", "ZDE6YWU=").toSavedAlbum())
    }

    @Test
    fun historyKeepsLikedAlbumsPastItsSize() {
        val history = (0..LIBRARY_LIMIT).map { SavedAlbum(Album("$it", "", "", ""), "$it") }
        assertEquals(history, trimHistory(history, setOf("$LIBRARY_LIMIT")))
        assertEquals(history.dropLast(1), trimHistory(history, emptySet()))
    }

    @Test
    fun likedAlbumsSurviveTheRoundTrip() {
        val albums = listOf(Album("1", "Группа крови", "Кино", "1988", "https://e/c.jpg"))
        assertEquals(albums, parseAlbums(JSONArray(albums.map { it.toJson() }).toString()))
    }

    private companion object {
        const val LIBRARY_LIMIT = 100
        const val HASH_LENGTH = 40
    }
}
