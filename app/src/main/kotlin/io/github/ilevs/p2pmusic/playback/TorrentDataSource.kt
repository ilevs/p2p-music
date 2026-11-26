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
import io.github.ilevs.p2pmusic.engine.TorrentEngine
import io.github.ilevs.p2pmusic.engine.TorrentReader
import io.github.ilevs.p2pmusic.engine.parseTorrentUri

/** Plays a file straight from a torrent: `torrent://<infohash>/<file index>`. See [TorrentReader]. */
@OptIn(UnstableApi::class)
class TorrentDataSource(
    private val engine: TorrentEngine,
) : BaseDataSource(true) {
    private var uri: Uri? = null
    private var reader: TorrentReader? = null
    private var remaining = 0L
    private var isOpened = false

    override fun open(dataSpec: DataSpec): Long {
        val ref =
            parseTorrentUri(dataSpec.uri.toString())
                ?: throw DataSourceException(PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        val torrentReader = TorrentReader(engine, ref, dataSpec.position)
        reader = torrentReader
        if (dataSpec.position > torrentReader.size) {
            throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        }
        val toEnd = torrentReader.size - dataSpec.position
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
        val read =
            when {
                length == 0 -> 0
                remaining == 0L -> C.RESULT_END_OF_INPUT
                else -> checkNotNull(reader).read(buffer, offset, minOf(length.toLong(), remaining).toInt())
            }
        if (read > 0) {
            remaining -= read
            bytesTransferred(read)
        }
        return if (read < 0) C.RESULT_END_OF_INPUT else read
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        reader?.close()
        reader = null
        uri = null
        if (isOpened) {
            isOpened = false
            transferEnded()
        }
    }

    class Factory(
        private val engine: TorrentEngine,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = TorrentDataSource(engine)
    }
}
