package io.github.ilevs.p2pmusic.engine

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.libtorrent4j.Priority
import org.libtorrent4j.SessionManager
import org.libtorrent4j.SessionParams
import org.libtorrent4j.SettingsPack
import org.libtorrent4j.TcpEndpoint
import org.libtorrent4j.TorrentBuilder
import org.libtorrent4j.TorrentInfo
import org.libtorrent4j.swig.settings_pack
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.random.Random

/**
 * Streams a real torrent between two libtorrent sessions on localhost: no internet, no Android.
 * Checks the bytes a player would read, a read that starts mid-file (a seek), and that the
 * other folders of the torrent are not downloaded while the rest of the album is queued.
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
    private lateinit var torrentBytes: ByteArray

    @Before
    fun setUp() {
        val seedDir = temp.newFolder("seed")
        // Shaped like a typical discography: album folders, Cyrillic names.
        val album = File(seedDir, "Исполнитель - Дискография").apply { mkdirs() }
        File(album, "2001 - Первый альбом").apply { mkdirs() }.resolve(TRACK_NAME).writeBytes(track)
        File(album, "2001 - Первый альбом").resolve(NEXT_TRACK_NAME).writeBytes(cover)
        File(album, "Сканы").apply { mkdirs() }.resolve("scans.bin").writeBytes(cover)
        torrentBytes =
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

        engine = TorrentEngine(temp.newFolder("engine"), localSettings())
        engine
            .openTorrent(torrentBytes)
            .swig()
            .connect_peer(TcpEndpoint(LOCALHOST, seeder.swig().listen_port()).swig())
    }

    @After
    fun tearDown() {
        engine.close()
        seeder.stop()
    }

    @Test
    fun opensTheTorrentFileOverAMagnetWithoutPeers() {
        TorrentEngine(temp.newFolder(), localSettings()).use { other ->
            val magnet = "magnet:?xt=urn:btih:$hash"
            assertThrows(IOException::class.java) { other.openMagnet(magnet, timeoutMs = 1) }

            assertEquals(info.numFiles(), other.openTorrent(torrentBytes).torrentFile().numFiles())
        }
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

    @Test
    fun queuesTheRestOfTheAlbum() {
        TorrentReader(engine, TorrentRef(hash, trackIndex()), 0).close()
        val handle = engine.handle(hash)
        assertEquals(Priority.DEFAULT, handle.filePriority(fileIndex(NEXT_TRACK_NAME)))
        assertEquals(Priority.IGNORE, handle.filePriority(fileIndex("scans.bin")))
    }

    // What a downloaded album needs to play offline: the .torrent cache and the files on disk, no peer.
    @Test
    fun playsFromDiskWithoutPeersAfterARestart() {
        val ref = TorrentRef(hash, trackIndex())
        readAll(TorrentReader(engine, ref, 0))
        engine.close()
        engine = TorrentEngine(File(temp.root, "engine"), localSettings())
        val started = System.nanoTime()
        assertTrue(prefetchStart(engine, ref, CACHE_TIMEOUT_MS) { false })
        println("METRIC cached_track_start_ms=${(System.nanoTime() - started) / NANOS_IN_MS}")
        assertArrayEquals(track, readAll(TorrentReader(engine, ref, 0)))
    }

    // A skipped album must not download: the prefetch sets piece deadlines and leaves the file unselected.
    @Test
    fun prefetchFetchesTheStartWithoutSelectingTheFile() {
        val ref = TorrentRef(hash, trackIndex())
        assertTrue(prefetchStart(engine, ref, CACHE_TIMEOUT_MS) { false })
        assertEquals(Priority.IGNORE, engine.handle(hash).filePriority(trackIndex()))
    }

    @Test
    fun prefetchStopsWhenAnotherAlbumIsTapped() {
        TorrentEngine(temp.newFolder(), localSettings()).use { other ->
            other.openTorrent(torrentBytes)
            assertFalse(prefetchStart(other, TorrentRef(hash, trackIndex()), CACHE_TIMEOUT_MS) { true })
        }
    }

    private fun trackIndex(): Int = fileIndex(TRACK_NAME)

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
        const val TRACK_NAME = "01 Песня.mp3"
        const val NEXT_TRACK_NAME = "02 Другая песня.mp3"
        const val PIECE_SIZE = 32 * 1024
        const val TRACK_SIZE = 1_000_000
        const val COVER_SIZE = 700_000
        const val BUFFER_SIZE = 10_000
        const val SEED_TIMEOUT_MS = 30_000L
        const val POLL_MS = 50L
        const val CACHE_TIMEOUT_MS = 10_000L
        const val NANOS_IN_MS = 1_000_000
    }
}
