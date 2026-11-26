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
 * The whole path on a real public swarm: .torrent, read the start of the file as a player would,
 * then seek to the middle. Needs internet, so it runs only with LIVE_TESTS=true (CI).
 *
 * The swarm is Ubuntu's: legal, always large. archive.org music torrents stalled here because they
 * lean on HTTPS web seeds and have no peers. Album folders and Cyrillic names: see TorrentReaderTest.
 */
class LiveStreamTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test(timeout = TIMEOUT_MS)
    fun streamsFromAPublicSwarm() {
        assumeTrue("LIVE_TESTS is not set", System.getenv("LIVE_TESTS") == "true")
        val client = OkHttpClient()
        // The point release changes, so take whatever .torrent the release page links to now.
        val page =
            client
                .newCall(
                    Request.Builder().url(RELEASE_PAGE).build(),
                ).execute()
                .use { checkNotNull(it.body).string() }
        val name = checkNotNull(Regex("ubuntu-[0-9.]+-live-server-amd64\\.iso\\.torrent").find(page)).value
        val torrent = Request.Builder().url(RELEASE_PAGE + name).build()
        val bytes = client.newCall(torrent).execute().use { checkNotNull(it.body).bytes() }
        val engine = TorrentEngine(temp.newFolder())
        val info = TorrentInfo.bdecode(bytes)
        // Swarm state in the CI log, so a timeout says whether peers never came.
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
        const val RELEASE_PAGE = "https://releases.ubuntu.com/noble/"
        const val TIMEOUT_MS = 180_000L
        const val STATUS_INTERVAL_MS = 5_000L
    }
}

/** Opens [torrent], reads the head and the middle of its first audio (or only) file; returns timings for the log. */
internal fun checkStreams(
    engine: TorrentEngine,
    torrent: ByteArray,
): String {
    val handle = engine.openTorrent(torrent)
    val files = handle.torrentFile().files()
    val index = (0 until files.numFiles()).firstOrNull { isAudioFile(files.fileName(it)) } ?: 0
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
