package io.github.ilevs.p2pmusic.engine

import org.libtorrent4j.AddTorrentParams
import org.libtorrent4j.AlertListener
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.Sha1Hash
import org.libtorrent4j.TorrentFlags
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.Vectors
import org.libtorrent4j.alerts.Alert
import org.libtorrent4j.alerts.AlertType
import org.libtorrent4j.swig.error_code
import org.libtorrent4j.swig.libtorrent
import org.libtorrent4j.swig.load_torrent_limits
import org.libtorrent4j.swig.settings_pack
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

private const val METADATA_TIMEOUT_MS = 60_000L
private const val MS_PER_SECOND = 1_000L
private const val WAIT_STEP_MS = 500L
private const val MIN_UPLOAD_BYTES_PER_SECOND = 1024
private const val MIN_RECONNECT_SECONDS = 5
private const val REMOVE_TIMEOUT_MS = 5_000L

// Two searches' worth: the albums of the search before stay warm when the user goes back to them.
private const val MAX_PREFETCHED = 12

/**
 * One libtorrent session for the whole app. Blocking calls: use them off the main thread.
 * Exception messages are shown to the user as they are, so they are in Russian.
 *
 * Torrents are added with nothing selected; reading a file through [TorrentDataSource]
 * is what makes it download, so a discography torrent never pulls every album.
 */
class TorrentEngine(
    dataDir: File,
    private val settings: SettingsPack = SettingsPack(),
) : Closeable {
    /** Cached .torrent files, `<infohash>.torrent`. */
    val torrentsDir = File(dataDir, "torrents")
    private val downloadsDir = File(dataDir, "downloads")

    init {
        torrentsDir.mkdirs()
        downloadsDir.mkdirs()
        // A torrent with nothing selected counts as finished, and libtorrent would drop its seeds right after
        // the metadata; then the first track waits until they are found again.
        settings.setBoolean(settings_pack.bool_types.close_redundant_connections.swigValue(), false)
        // Peers dropped while nothing was selected are retried after this many seconds, not libtorrent's 60.
        settings.setInteger(settings_pack.int_types.min_reconnect_time.swigValue(), MIN_RECONNECT_SECONDS)
        // Ask every tracker of a magnet at once, not tier by tier, so the metadata comes from the fastest one.
        settings.setBoolean(settings_pack.bool_types.announce_to_all_trackers.swigValue(), true)
        settings.setBoolean(settings_pack.bool_types.announce_to_all_tiers.swigValue(), true)
        // No queue: libtorrent runs 3 downloading torrents at once by default, and prefetched releases could pause
        // the one that plays.
        settings.setInteger(settings_pack.int_types.active_downloads.swigValue(), -1)
        settings.setInteger(settings_pack.int_types.active_seeds.swigValue(), -1)
        settings.setInteger(settings_pack.int_types.active_limit.swigValue(), -1)
    }

    // Signalled on every alert we listen to; waiters re-check their condition after it.
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    private val sessionLazy =
        lazy {
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
                start(SessionParams(settings))
            }
        }
    private val session: SessionManager by sessionLazy

    // Torrents added by [prefetch] and not opened since, oldest first. Guarded by itself.
    private val prefetched = LinkedHashSet<String>()

    /**
     * Off caps uploads at 1 KB/s: libtorrent has no real zero, 0 means unlimited.
     * Starts the session if it is not running, which the app does at launch so the first magnet finds the DHT ready.
     */
    fun setSeeding(enabled: Boolean) {
        settings.uploadRateLimit(if (enabled) 0 else MIN_UPLOAD_BYTES_PER_SECOND)
        session.applySettings(settings)
    }

    /** Stops the session. A session left running crashed the JVM of the tests when it exited (exit value 139). */
    override fun close() {
        if (sessionLazy.isInitialized()) session.stop()
    }

    /** Adds a magnet link (or finds it in the session) and waits [timeoutMs] for its metadata. */
    fun openMagnet(
        magnet: String,
        timeoutMs: Long = METADATA_TIMEOUT_MS,
    ): TorrentHandle {
        val hash = parseMagnet(magnet).infoHashes.best.toHex()
        // An opened torrent stays in the session: [prefetch] no longer removes it.
        synchronized(prefetched) { prefetched.remove(hash) }
        if (find(hash) == null && !addCached(hash)) {
            session.download(magnet, downloadsDir, TorrentFlags.DEFAULT_DONT_DOWNLOAD)
        }
        val handle = awaitMetadata(hash, timeoutMs)
        File(torrentsDir, "$hash.torrent").takeUnless { it.exists() }?.writeBytes(torrentFileBytes(handle))
        return handle
    }

    /**
     * Starts fetching the metadata of [magnet] before the user taps it, so that [openMagnet] finds it there.
     * Downloads no file. Over [MAX_PREFETCHED], the oldest never opened one is removed, with its peers.
     */
    fun prefetch(magnet: String) {
        val hash = parseMagnet(magnet).infoHashes.best.toHex()
        synchronized(prefetched) {
            if (prefetched.remove(hash)) {
                prefetched += hash
                return
            }
            // Opened, playing or being opened: not this method's to remove.
            if (find(hash) != null) return
            prefetched += hash
            if (prefetched.size > MAX_PREFETCHED) {
                val oldest = prefetched.first()
                prefetched -= oldest
                find(oldest)?.let { session.remove(it) }
            }
        }
        // Added as openMagnet adds it: a magnet added with its own flags got no metadata from NNM-Club in 25 s, three
        // CI runs out of three, where openMagnet's got it in 4-6 s (2026-11-28).
        if (!addCached(hash)) session.download(magnet, downloadsDir, TorrentFlags.DEFAULT_DONT_DOWNLOAD)
    }

    /** Adds a .torrent file (or finds it in the session) and caches it. */
    fun openTorrent(bytes: ByteArray): TorrentHandle {
        val info =
            try {
                TorrentInfo.bdecode(bytes)
            } catch (e: IllegalArgumentException) {
                throw IOException("Трекер вернул не .torrent-файл", e)
            }
        val hash = info.infoHash().toHex()
        synchronized(prefetched) { prefetched.remove(hash) }
        File(torrentsDir, "$hash.torrent").takeUnless { it.exists() }?.writeBytes(bytes)
        // A magnet of this torrent that found no peers yet would keep waiting; the file has the metadata.
        find(hash)?.takeUnless { it.status().hasMetadata() }?.let {
            session.remove(it)
            awaitUntil(REMOVE_TIMEOUT_MS) { find(hash) == null }
        }
        if (find(hash) == null && !addCached(hash)) throw IOException("Не удалось добавить торрент $hash")
        return awaitMetadata(hash)
    }

    /** A torrent that was opened before, from the session or from the .torrent cache. */
    fun handle(hash: String): TorrentHandle {
        synchronized(prefetched) { prefetched.remove(hash) }
        if (find(hash) == null &&
            !addCached(hash)
        ) {
            throw IOException("Торрент $hash не найден: откройте его заново через поиск")
        }
        return awaitMetadata(hash)
    }

    val dhtNodes: Long get() = if (sessionLazy.isInitialized()) session.dhtNodes() else 0

    fun find(hash: String): TorrentHandle? = session.find(Sha1Hash.parseHex(hash))?.takeIf { it.isValid }

    // Loads the whole .torrent, not just its info dictionary, so the torrent keeps its trackers.
    private fun addCached(hash: String): Boolean {
        val file = File(torrentsDir, "$hash.torrent")
        if (!file.exists()) return false
        val error = error_code()
        val params = libtorrent.load_torrent_file(file.absolutePath, error, load_torrent_limits())
        if (error.value() != 0) throw IOException("Сохранённый торрент $hash повреждён: ${error.message()}")
        params.setSave_path(downloadsDir.absolutePath)
        params.setFlags(params.getFlags().or_(TorrentFlags.DEFAULT_DONT_DOWNLOAD))
        session.swig().async_add_torrent(params)
        return true
    }

    private fun awaitMetadata(
        hash: String,
        timeoutMs: Long = METADATA_TIMEOUT_MS,
    ): TorrentHandle {
        val isReady = awaitUntil(timeoutMs) { find(hash)?.status()?.hasMetadata() == true }
        if (!isReady) {
            val seconds = timeoutMs / MS_PER_SECOND
            throw IOException("За $seconds с не нашлось ни одного пира: раздача мёртвая или сеть режет торренты")
        }
        return requireNotNull(find(hash))
    }

    /**
     * Blocks until [condition] holds, re-checked on every alert, at most [timeoutMs]; false when it timed out.
     * Interrupting the thread cancels the wait.
     */
    fun awaitUntil(
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
}

// Not private: called from the class, and lint forbids synthetic accessors.
internal fun parseMagnet(magnet: String): AddTorrentParams =
    try {
        AddTorrentParams.parseMagnetUri(magnet)
    } catch (e: IllegalArgumentException) {
        throw IOException("Это не magnet-ссылка", e)
    }

// Not private: called from the class, and lint forbids synthetic accessors.
internal fun torrentFileBytes(handle: TorrentHandle): ByteArray {
    val params = AddTorrentParams().apply { torrentInfo = handle.torrentFile() }
    return Vectors.byte_vector2bytes(libtorrent.write_torrent_file_buf_ex(params.swig()))
}

/**
 * Trackers of [handle] that ever answered, and all of them. Not TorrentHandle.trackers(): its entries
 * point into a native vector the GC may free while they are read, which crashed the JVM.
 */
fun trackerCounts(handle: TorrentHandle): Pair<Int, Int> {
    val entries = handle.swig().trackers()
    // The monitor keeps the vector reachable, so not freed, until its entries are read.
    return synchronized(entries) { entries.count { it.verified } to entries.size }
}
