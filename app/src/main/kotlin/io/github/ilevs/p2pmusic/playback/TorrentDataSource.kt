package io.github.ilevs.p2pmusic.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import io.github.ilevs.p2pmusic.engine.FileSpan
import io.github.ilevs.p2pmusic.engine.TorrentEngine
import io.github.ilevs.p2pmusic.engine.parseTorrentUri
import org.libtorrent4j.Priority
import org.libtorrent4j.TorrentHandle
import java.io.IOException
import java.io.RandomAccessFile

/**
 * Plays a file straight from a torrent: `torrent://<infohash>/<file index>`.
 *
 * Bytes come from the file libtorrent writes on disk. Before reading a piece we wait for it,
 * and the pieces right ahead of the read position get deadlines so libtorrent fetches them
 * first, from the fastest peers. A seek is a new [open], so the window simply moves.
 */
@OptIn(UnstableApi::class)
class TorrentDataSource(
    private val engine: TorrentEngine,
) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var handle: TorrentHandle? = null
    private var span: FileSpan? = null
    private var path: String? = null
    private var file: RandomAccessFile? = null
    private var position = 0L
    private var remaining = 0L
    private var windowStart = -1
    private val deadlinePieces = mutableSetOf<Int>()
    private var isOpened = false

    override fun open(dataSpec: DataSpec): Long {
        val ref =
            parseTorrentUri(dataSpec.uri.toString())
                ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val torrent = engine.handle(ref.hash)
        val info = torrent.torrentFile()
        val files = info.files()
        val fileSpan = FileSpan(files.fileOffset(ref.fileIndex), files.fileSize(ref.fileIndex), info.pieceLength())
        if (dataSpec.position > fileSpan.size) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        torrent.filePriority(ref.fileIndex, Priority.TOP_PRIORITY)
        // The tail holds ID3v1/APE tags and the M4A index; players read it right after the header.
        setDeadline(torrent, fileSpan.lastPiece, 0)
        handle = torrent
        span = fileSpan
        path = files.filePath(ref.fileIndex, torrent.savePath())
        position = dataSpec.position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) fileSpan.size - position else dataSpec.length
        moveWindow(fileSpan.pieceAt(position))
        isOpened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int =
        when {
            length == 0 -> 0
            remaining == 0L -> C.RESULT_END_OF_INPUT
            else -> readPiece(buffer, offset, length)
        }

    override fun getUri(): Uri? = uri

    override fun close() {
        val torrent = handle
        if (torrent != null && torrent.isValid) {
            deadlinePieces.filterNot(torrent::havePiece).forEach(torrent::resetPieceDeadline)
        }
        deadlinePieces.clear()
        file?.close()
        file = null
        handle = null
        span = null
        uri = null
        windowStart = -1
        if (isOpened) {
            isOpened = false
            transferEnded()
        }
    }

    private fun readPiece(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val torrent = checkNotNull(handle)
        val fileSpan = checkNotNull(span)
        val piece = fileSpan.pieceAt(position)
        moveWindow(piece)
        engine.awaitPiece(torrent, piece)
        val toRead = minOf(length.toLong(), remaining, fileSpan.pieceEnd(position) - position).toInt()
        val input = file ?: RandomAccessFile(checkNotNull(path), "r").also { file = it }
        input.seek(position)
        val read = input.read(buffer, offset, toRead)
        if (read < 0) throw IOException("Piece $piece is verified but the file ends early")
        position += read
        remaining -= read
        bytesTransferred(read)
        return read
    }

    private fun moveWindow(piece: Int) {
        if (piece == windowStart) return
        windowStart = piece
        val torrent = checkNotNull(handle)
        checkNotNull(span).deadlineWindow(piece).forEach { (index, deadlineMs) ->
            setDeadline(torrent, index, deadlineMs)
        }
    }

    private fun setDeadline(
        torrent: TorrentHandle,
        piece: Int,
        deadlineMs: Int,
    ) {
        if (!torrent.havePiece(piece) && deadlinePieces.add(piece)) {
            torrent.setPieceDeadline(piece, deadlineMs)
        }
    }

    class Factory(
        private val engine: TorrentEngine,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = TorrentDataSource(engine)
    }
}
