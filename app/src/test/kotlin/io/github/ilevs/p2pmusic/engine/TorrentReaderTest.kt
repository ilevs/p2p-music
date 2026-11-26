package io.github.ilevs.p2pmusic.engine

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.settings_pack
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

/**
 * Streams a real torrent between two libtorrent sessions on localhost: no internet, no Android.
 * Checks the bytes a player would read, a read that starts mid-file (a seek), and that the
 * other files of the torrent are not downloaded.
 */
class TorrentReaderTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val track = Random(seed = 1).nextBytes(TRACK_SIZE)
    private val cover = Random(seed = 2).nextBytes(COVER_SIZE)
    private lateinit var seeder: SessionManager
    private lateinit var engine: TorrentEngine
    private lateinit var hash: String
    private lateinit var info: TorrentInfo

    @Before
    fun setUp() {
        val seedDir = temp.newFolder("seed")
        val album = File(seedDir, "album").apply { mkdirs() }
        File(album, "01 track.flac").writeBytes(track)
        File(album, "scans.bin").writeBytes(cover)
        val torrentBytes =
            TorrentBuilder()
                .path(album)
                .pieceSize(PIECE_SIZE)
                .flags(TorrentBuilder.V1_ONLY)
                .generate()
                .entry()
                .bencode()
        info = TorrentInfo.bdecode(torrentBytes)
        hash = info.infoHash().toHex()

        seeder = SessionManager().apply { start(SessionParams(localSettings())) }
        seeder.download(info, seedDir)
        val deadline = System.currentTimeMillis() + SEED_TIMEOUT_MS
        while (seeder.find(info.infoHash())?.status()?.isSeeding != true) {
            check(System.currentTimeMillis() < deadline) { "Seeder did not finish checking its files" }
            Thread.sleep(POLL_MS)
        }

        val engineDir = temp.newFolder("engine")
        File(engineDir, "torrents").mkdirs()
        File(engineDir, "torrents/$hash.torrent").writeBytes(torrentBytes)
        engine = TorrentEngine(engineDir, localSettings())
        engine
            .handle(hash)
            .swig()
            .connect_peer(TcpEndpoint(LOCALHOST, seeder.swig().listen_port()).swig())
    }

    @After
    fun tearDown() {
        seeder.stop()
    }

    @Test
    fun streamsWholeFile() {
        assertArrayEquals(track, readAll(TorrentReader(engine, TorrentRef(hash, trackIndex()), 0)))
    }

    @Test
    fun streamsFromTheMiddleAfterASeek() {
        val start = TRACK_SIZE / 2L + 1
        val bytes = readAll(TorrentReader(engine, TorrentRef(hash, trackIndex()), start))
        assertArrayEquals(track.copyOfRange(start.toInt(), TRACK_SIZE), bytes)
    }

    @Test
    fun leavesOtherFilesAlone() {
        val ref = TorrentRef(hash, trackIndex())
        TorrentReader(engine, ref, 0).use { reader ->
            assertEquals(TRACK_SIZE.toLong(), reader.size)
            readAll(reader)
        }
        val coverIndex = fileIndex("scans.bin")
        val files = info.files()
        val firstCoverPiece = ((files.fileOffset(coverIndex) + PIECE_SIZE - 1) / PIECE_SIZE).toInt()
        val lastCoverPiece = ((files.fileOffset(coverIndex) + COVER_SIZE) / PIECE_SIZE).toInt() - 1
        val handle = engine.handle(hash)
        // Pieces shared with the track may arrive; pieces only the other file uses must not.
        (firstCoverPiece..lastCoverPiece).forEach { assertFalse("piece $it", handle.havePiece(it)) }
    }

    private fun trackIndex(): Int = fileIndex("01 track.flac")

    private fun fileIndex(name: String): Int = (0 until info.numFiles()).first { info.files().fileName(it) == name }

    private fun readAll(reader: TorrentReader): ByteArray =
        reader.use {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = it.read(buffer, 0, buffer.size)
                if (read < 0) break
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }

    private fun localSettings(): SettingsPack =
        SettingsPack()
            .listenInterfaces("$LOCALHOST:0")
            .apply {
                setEnableDht(false)
                setBoolean(settings_pack.bool_types.enable_lsd.swigValue(), false)
            }

    private companion object {
        const val LOCALHOST = "127.0.0.1"
        const val PIECE_SIZE = 32 * 1024
        const val TRACK_SIZE = 1_000_000
        const val COVER_SIZE = 700_000
        const val BUFFER_SIZE = 10_000
        const val SEED_TIMEOUT_MS = 30_000L
        const val POLL_MS = 50L
    }
}
