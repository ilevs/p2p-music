package io.github.ilevs.p2pmusic.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileSpanTest {
    // A 1000-byte file that starts 300 bytes into the torrent, with 256-byte pieces: pieces 1..5.
    private val span = FileSpan(offset = 300, size = 1000, pieceLength = 256)

    @Test
    fun mapsFilePositionsToTorrentPieces() {
        assertEquals(1, span.pieceAt(0))
        assertEquals(2, span.pieceAt(212))
        assertEquals(5, span.lastPiece)
    }

    @Test
    fun pieceEndStopsAtPieceBoundaryAndFileEnd() {
        assertEquals(212L, span.pieceEnd(0))
        assertEquals(468L, span.pieceEnd(212))
        assertEquals(1000L, span.pieceEnd(999))
    }

    @Test
    fun windowIsClippedToTheFileAndDeadlinesGrow() {
        val window = span.deadlineWindow(4)
        assertEquals(listOf(4, 5), window.map { it.first })
        assertEquals(0, window.first().second)
        assertTrue(window.last().second > window.first().second)
    }

    @Test
    fun bigPiecesStillGetAWindowOfTwo() {
        val big = FileSpan(offset = 0, size = 64L * 1024 * 1024, pieceLength = 16 * 1024 * 1024)
        assertEquals(listOf(0, 1), big.deadlineWindow(0).map { it.first })
    }

    @Test
    fun detectsAudioByExtension() {
        assertTrue(isAudioFile("Artist/Album/01 - Song.FLAC"))
        assertFalse(isAudioFile("Artist/Album/cover.jpg"))
        assertFalse(isAudioFile("README"))
    }
}
