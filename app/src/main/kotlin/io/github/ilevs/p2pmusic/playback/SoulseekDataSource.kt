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
import io.github.ilevs.p2pmusic.engine.SoulseekEngine
import io.github.ilevs.p2pmusic.engine.SoulseekFile
import io.github.ilevs.p2pmusic.engine.awaitBytes
import io.github.ilevs.p2pmusic.engine.parseSoulseekUri
import java.io.File
import java.io.RandomAccessFile

// A user's queue can hold a track for long; past this the player shows the error instead of spinning.
private const val START_TIMEOUT_MS = 120_000L

/**
 * Plays `soulseek://` files while they download, and hands every other URI to [other] (torrents).
 * The bytes arrive in order, so a read past them waits; that includes a seek ahead.
 */
@OptIn(UnstableApi::class)
class SoulseekDataSource(
    private val engine: SoulseekEngine,
    private val other: DataSource,
) : BaseDataSource(true) {
    private var file: SoulseekFile? = null
    private var input: RandomAccessFile? = null
    private var position = 0L
    private var remaining = 0L
    private var available = 0L
    private var uri: Uri? = null
    private var isOpened = false

    override fun open(dataSpec: DataSpec): Long {
        val remote = parseSoulseekUri(dataSpec.uri.toString()) ?: return other.open(dataSpec)
        file = remote
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        if (dataSpec.position > remote.size) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        engine.download(remote)
        position = dataSpec.position
        val toEnd = remote.size - position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) toEnd else minOf(dataSpec.length, toEnd)
        isOpened = true
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        val remote = file
        return when {
            remote == null -> other.read(buffer, offset, length)
            length == 0 -> 0
            remaining == 0L -> C.RESULT_END_OF_INPUT
            else -> readFile(remote, buffer, offset, length)
        }
    }

    private fun readFile(
        remote: SoulseekFile,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int {
        if (position >= available) available = engine.awaitBytes(remote, position, START_TIMEOUT_MS)
        val toRead = minOf(length.toLong(), remaining, available - position).toInt()
        val reader = input ?: openFile(remote).also { input = it }
        reader.seek(position)
        val read = reader.read(buffer, offset, toRead)
        if (read > 0) {
            position += read
            remaining -= read
            bytesTransferred(read)
        }
        return if (read < 0) C.RESULT_END_OF_INPUT else read
    }

    // The .part file is renamed when complete; an open handle survives that, so open whichever exists now.
    private fun openFile(remote: SoulseekFile): RandomAccessFile {
        val done = engine.target(remote)
        return runCatching {
            RandomAccessFile(
                File(done.path + ".part"),
                "r",
            )
        }.getOrElse { RandomAccessFile(done, "r") }
    }

    override fun getUri(): Uri? = if (file != null) uri else other.uri

    override fun close() {
        if (file == null) {
            other.close()
            return
        }
        input?.close()
        input = null
        file = null
        uri = null
        available = 0L
        if (isOpened) {
            isOpened = false
            transferEnded()
        }
    }

    class Factory(
        private val engine: SoulseekEngine,
        private val other: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = SoulseekDataSource(engine, other.createDataSource())
    }
}
