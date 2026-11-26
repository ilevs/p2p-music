package io.github.ilevs.p2pmusic.engine

import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.libtorrent4j.TorrentInfo
import kotlin.concurrent.thread

/**
 * The whole path on a real public swarm: .torrent, list audio, read the start of a track as a
 * player would, then seek to the middle. Needs internet, so it runs only with LIVE_TESTS=true (CI).
 */
class LiveStreamTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test(timeout = TIMEOUT_MS)
    fun streamsAnInternetArchiveAlbum() {
        assumeTrue("LIVE_TESTS is not set", System.getenv("LIVE_TESTS") == "true")
        val request = Request.Builder().url("https://archive.org/download/$ITEM/${ITEM}_archive.torrent").build()
        val bytes = OkHttpClient().newCall(request).execute().use { checkNotNull(it.body).bytes() }
        val engine = TorrentEngine(temp.newFolder())
        val info = TorrentInfo.bdecode(bytes)
        // Swarm state in the CI log, so a timeout says whether peers or web seeds never came.
        thread(isDaemon = true) {
            while (true) {
                engine.find(info.infoHash().toHex())?.let { handle ->
                    val status = handle.status()
                    println(
                        "${status.state()} peers=${status.numPeers()} seeds=${status.numSeeds()} " +
                            "rate=${status.downloadRate()} trackers=${handle.trackers().map { it.url() }} " +
                            "web seeds=${handle.urlSeeds()}",
                    )
                }
                Thread.sleep(STATUS_INTERVAL_MS)
            }
        }
        println(checkStreams(engine, bytes))
    }

    private companion object {
        // A public domain recording on archive.org; its torrents are seeded by archive.org itself.
        const val ITEM = "testmp3testfile"
        const val TIMEOUT_MS = 180_000L
        const val STATUS_INTERVAL_MS = 5_000L
    }
}

/** Opens [torrent], reads the head and the middle of its first audio file; returns timings for the log. */
internal fun checkStreams(
    engine: TorrentEngine,
    torrent: ByteArray,
): String {
    val handle = engine.openTorrent(torrent)
    val files = handle.torrentFile().files()
    val index = (0 until files.numFiles()).first { isAudioFile(files.fileName(it)) }
    val ref = TorrentRef(handle.infoHash().toHex(), index)
    val start = System.currentTimeMillis()
    val head = readSome(TorrentReader(engine, ref, 0), 0)
    val startup = System.currentTimeMillis() - start
    val seek = System.currentTimeMillis()
    val middle = files.fileSize(index) / 2
    readSome(TorrentReader(engine, ref, middle), middle)
    val seekMs = System.currentTimeMillis() - seek
    val audioCount = (0 until files.numFiles()).count { isAudioFile(files.fileName(it)) }
    return "${files.filePath(index)}: $audioCount audio files, head ${head.size} B in $startup ms, seek in $seekMs ms"
}

private fun readSome(
    reader: TorrentReader,
    from: Long,
): ByteArray =
    reader.use {
        val buffer = ByteArray(minOf(READ_BYTES.toLong(), it.size - from).toInt())
        var total = 0
        while (total < buffer.size) {
            val read = it.read(buffer, total, buffer.size - total)
            if (read < 0) break
            total += read
        }
        assertEquals(buffer.size, total)
        buffer
    }

private const val READ_BYTES = 64 * 1024
