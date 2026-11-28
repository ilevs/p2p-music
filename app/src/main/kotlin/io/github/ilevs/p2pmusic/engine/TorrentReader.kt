package io.github.ilevs.p2pmusic.engine

import org.libtorrent4j.Priority
import org.libtorrent4j.TorrentHandle
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

private const val PRIORITY_TIMEOUT_MS = 2_000L

/**
 * Reads one file of a torrent while it downloads, starting at [startPosition]. Blocking.
 *
 * Bytes come from the file libtorrent writes on disk. Before reading a piece we wait for it,
 * and the pieces right ahead of the read position get deadlines so libtorrent fetches them
 * first, from the fastest peers. A seek is a new reader, so the window simply moves.
 */
class TorrentReader(
    private val engine: TorrentEngine,
    ref: TorrentRef,
    startPosition: Long,
) : Closeable {
    private val handle = engine.handle(ref.hash)
    private val span: FileSpan
    private val path: String
    private var position = startPosition
    private var file: RandomAccessFile? = null
    private var windowStart = -1
    private val deadlinePieces = mutableSetOf<Int>()

    val size: Long get() = span.size

    init {
        val info = handle.torrentFile()
        val files = info.files()
        if (ref.fileIndex >= files.numFiles() || files.fileSize(ref.fileIndex) <= 0) {
            throw IOException("No such file or empty: ${ref.fileIndex}")
        }
        span = FileSpan(files.fileOffset(ref.fileIndex), files.fileSize(ref.fileIndex), info.pieceLength())
        path = files.filePath(ref.fileIndex, handle.savePath())
        val wasIdle = handle.status().isFinished
        // Playing a file downloads all of it in the background, then the rest of its album folder: a torrent
        // with nothing left to fetch loses its unchoke slot at the seeds, and the next track would wait for one.
        // Other folders (albums of a discography, scans) stay untouched.
        handle.filePriority(ref.fileIndex, Priority.TOP_PRIORITY)
        val album = File(files.filePath(ref.fileIndex)).parent
        for (i in 0 until files.numFiles()) {
            val isAlbumTrack = File(files.filePath(i)).parent == album && isAudioFile(files.fileName(i))
            // Below DEFAULT, not only IGNORE: the prefetch leaves the files it touched at LOW.
            if (isAlbumTrack && handle.filePriority(i) < Priority.DEFAULT) handle.filePriority(i, Priority.DEFAULT)
        }
        // With nothing selected the torrent announced itself as complete, and trackers send no seeds to a seed.
        if (wasIdle) {
            handle.forceReannounce()
            handle.forceDHTAnnounce()
        }
        // The tail holds ID3v1/APE tags and the M4A index; players read it right after the header.
        setDeadline(span.lastPiece, 0)
        if (position < span.size) moveWindow(span.pieceAt(position))
    }

    /** Reads up to [length] bytes, never across a piece boundary. Returns -1 at the end of the file. */
    fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (position >= span.size) return -1
        val piece = span.pieceAt(position)
        moveWindow(piece)
        engine.awaitPiece(handle, piece)
        val toRead = minOf(length.toLong(), span.pieceEnd(position) - position).toInt()
        val input = file ?: RandomAccessFile(path, "r").also { file = it }
        input.seek(position)
        val read = input.read(buffer, offset, toRead)
        if (read < 0) throw IOException("Piece $piece is verified but the file ends early")
        position += read
        return read
    }

    override fun close() {
        if (handle.isValid) {
            deadlinePieces.filterNot(handle::havePiece).forEach(handle::resetPieceDeadline)
        }
        deadlinePieces.clear()
        file?.close()
        file = null
    }

    private fun moveWindow(piece: Int) {
        if (piece == windowStart) return
        windowStart = piece
        span.deadlineWindow(piece).forEach { (index, deadlineMs) -> setDeadline(index, deadlineMs) }
    }

    private fun setDeadline(
        piece: Int,
        deadlineMs: Int,
    ) {
        if (!handle.havePiece(piece) && deadlinePieces.add(piece)) {
            handle.setPieceDeadline(piece, deadlineMs)
        }
    }
}

/**
 * Fetches what a player reads first from [ref], the reader's window at its start and its tail, and nothing else
 * of the file, so an album the user skipped past costs a few MB, not a download. True once they are on disk, false
 * after [timeoutMs] or as soon as [stop] holds.
 */
fun prefetchStart(
    engine: TorrentEngine,
    ref: TorrentRef,
    timeoutMs: Long,
    stop: () -> Boolean,
): Boolean {
    val handle = engine.handle(ref.hash)
    val info = handle.torrentFile()
    val files = info.files()
    val span = FileSpan(files.fileOffset(ref.fileIndex), files.fileSize(ref.fileIndex), info.pieceLength())
    // One piece was not enough: a 24-bit FLAC needs more than one before the player starts (11 s of silence in CI).
    val window = span.deadlineWindow(span.pieceAt(0)) + (span.lastPiece to 0)
    val pieces = window.map { it.first }
    val wasIdle = handle.status().isFinished
    selectOnly(engine, handle, pieces.toSet())
    window.filterNot { handle.havePiece(it.first) }.forEach { (piece, ms) -> handle.setPieceDeadline(piece, ms) }
    // As in TorrentReader: trackers send no seeds to a torrent that announced itself complete.
    if (wasIdle) handle.forceReannounce()
    return engine.awaitUntil(timeoutMs) { stop() || pieces.all(handle::havePiece) } && !stop()
}

// libtorrent keeps the data of unselected files in a part file, and a track tapped while a piece there is half
// written fails to move it out: "End of file", and the torrent pauses for good. So the unselected files under
// [window] are selected at the lowest priority, and their pieces outside it are switched off instead.
private fun selectOnly(
    engine: TorrentEngine,
    handle: TorrentHandle,
    window: Set<Int>,
) {
    val info = handle.torrentFile()
    val files = info.files()
    val pieces =
        (0 until files.numFiles())
            .filter { files.fileSize(it) > 0 && !files.padFileAt(it) }
            .associateWith { FileSpan(files.fileOffset(it), files.fileSize(it), info.pieceLength()) }
            .mapValues { (_, span) -> span.pieceAt(0)..span.lastPiece }
    val (unselected, selected) = pieces.keys.partition { handle.filePriority(it) == Priority.IGNORE }
    val touched = unselected.filter { file -> window.any { it in pieces.getValue(file) } }
    touched.forEach { handle.filePriority(it, Priority.LOW) }
    // libtorrent applies file priorities to the pieces in a callback, which would undo the lines below.
    engine.awaitUntil(PRIORITY_TIMEOUT_MS) { touched.none { handle.filePriority(it) == Priority.IGNORE } }
    val wanted = selected.flatMapTo(window.toMutableSet()) { pieces.getValue(it) }
    // Still LOW: a track tapped meanwhile has raised its files, and those download whole.
    touched
        .filter { handle.filePriority(it) == Priority.LOW }
        .flatMap { pieces.getValue(it) }
        .minus(wanted)
        .forEach { handle.piecePriority(it, Priority.IGNORE) }
}
