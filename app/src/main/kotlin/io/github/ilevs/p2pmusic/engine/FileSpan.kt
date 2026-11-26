package io.github.ilevs.p2pmusic.engine

import kotlin.math.max
import kotlin.math.min

// How much audio ahead of the read position gets piece deadlines: ~30 s of FLAC, minutes of MP3.
private const val WINDOW_BYTES = 4L * 1024 * 1024
private const val MIN_WINDOW_PIECES = 2

// Deadlines grow by this step along the window, so the piece under the read head is fetched first.
private const val DEADLINE_STEP_MS = 250

private val AudioExtensions = setOf("mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav")

/** One file inside a torrent: where it starts in the torrent's byte stream, its size and the piece size. */
class FileSpan(
    private val offset: Long,
    val size: Long,
    private val pieceLength: Int,
) {
    init {
        require(size > 0) { "Empty file" }
        require(pieceLength > 0) { "Bad piece length" }
    }

    val lastPiece: Int get() = pieceAt(size - 1)

    /** Piece that holds the byte at [position] in this file. */
    fun pieceAt(position: Long): Int = ((offset + position) / pieceLength).toInt()

    /** File position right after the piece that holds [position]. */
    fun pieceEnd(position: Long): Long = min(size, (pieceAt(position) + 1L) * pieceLength - offset)

    /** Pieces from [fromPiece] that get deadlines, paired with the deadline in milliseconds. */
    fun deadlineWindow(fromPiece: Int): List<Pair<Int, Int>> {
        val count = max(MIN_WINDOW_PIECES.toLong(), (WINDOW_BYTES + pieceLength - 1) / pieceLength).toInt()
        val last = min(lastPiece, fromPiece + count - 1)
        return (fromPiece..last).map { it to (it - fromPiece) * DEADLINE_STEP_MS }
    }
}

fun isAudioFile(path: String): Boolean = path.substringAfterLast('.', "").lowercase() in AudioExtensions
