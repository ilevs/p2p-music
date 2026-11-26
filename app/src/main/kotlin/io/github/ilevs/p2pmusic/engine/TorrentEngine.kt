package io.github.ilevs.p2pmusic.engine

import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.SessionManager
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.Vectors
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.swig.libtorrent
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val METADATA_TIMEOUT_MS = 60_000L
private const val WAIT_STEP_MS = 500L

/**
 * One libtorrent session for the whole app. Blocking calls: use them off the main thread.
 *
 * Torrents are added with nothing selected; reading a file through [TorrentDataSource]
 * is what makes it download, so a discography torrent never pulls every album.
 */
class TorrentEngine(
    dataDir: File,
) {
    private val torrentsDir = File(dataDir, "torrents")
    private val downloadsDir = File(dataDir, "downloads")

    // Signalled on every alert we listen to; waiters re-check their condition after it.
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    private val session: SessionManager by lazy {
        torrentsDir.mkdirs()
        downloadsDir.mkdirs()
        SessionManager().apply {
            addListener(
                object : AlertListener {
                    override fun types(): IntArray =
                        intArrayOf(
                            AlertType.ADD_TORRENT.swig(),
                            AlertType.METADATA_RECEIVED.swig(),
                            AlertType.PIECE_FINISHED.swig(),
                        )

                    override fun alert(alert: Alert<*>) {
                        lock.withLock { changed.signalAll() }
                    }
                },
            )
            start()
        }
    }

    /** Adds a magnet link (or finds it in the session) and waits for its metadata. */
    fun openMagnet(magnet: String): TorrentHandle {
        val hash =
            try {
                AddTorrentParams
                    .parseMagnetUri(magnet)
                    .infoHashes.best
                    .toHex()
            } catch (e: IllegalArgumentException) {
                throw IOException("Not a magnet link", e)
            }
        if (find(hash) == null && !addCached(hash)) {
            session.download(magnet, downloadsDir, TorrentFlags.DEFAULT_DONT_DOWNLOAD)
        }
        val handle = awaitMetadata(hash)
        File(torrentsDir, "$hash.torrent").takeUnless { it.exists() }?.writeBytes(torrentFileBytes(handle))
        return handle
    }

    /** A torrent that was opened before, from the session or from the .torrent cache. */
    fun handle(hash: String): TorrentHandle {
        if (find(hash) == null && !addCached(hash)) throw IOException("Unknown torrent $hash")
        return awaitMetadata(hash)
    }

    fun find(hash: String): TorrentHandle? = session.find(Sha1Hash.parseHex(hash))?.takeIf { it.isValid }

    /** Blocks until [piece] is downloaded and verified. Interrupting the thread cancels the wait. */
    fun awaitPiece(
        handle: TorrentHandle,
        piece: Int,
    ) {
        awaitUntil(Long.MAX_VALUE) { handle.havePiece(piece) }
    }

    private fun addCached(hash: String): Boolean {
        val file = File(torrentsDir, "$hash.torrent")
        if (file.exists()) {
            session.download(TorrentInfo(file), downloadsDir, null, null, null, TorrentFlags.DEFAULT_DONT_DOWNLOAD)
        }
        return file.exists()
    }

    private fun awaitMetadata(hash: String): TorrentHandle {
        val isReady = awaitUntil(METADATA_TIMEOUT_MS) { find(hash)?.status()?.hasMetadata() == true }
        if (!isReady) throw IOException("No metadata for $hash: no peers found")
        return requireNotNull(find(hash))
    }

    private fun awaitUntil(
        timeoutMs: Long,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = if (timeoutMs == Long.MAX_VALUE) Long.MAX_VALUE else System.currentTimeMillis() + timeoutMs
        lock.withLock {
            while (!condition()) {
                if (System.currentTimeMillis() >= deadline) return false
                try {
                    // Bounded wait: an alert may fire between the check and the await.
                    changed.await(WAIT_STEP_MS, TimeUnit.MILLISECONDS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw InterruptedIOException(e.message)
                }
            }
        }
        return true
    }

    private fun torrentFileBytes(handle: TorrentHandle): ByteArray {
        val params = AddTorrentParams().apply { torrentInfo = handle.torrentFile() }
        return Vectors.byte_vector2bytes(libtorrent.write_torrent_file_buf_ex(params.swig()))
    }
}
