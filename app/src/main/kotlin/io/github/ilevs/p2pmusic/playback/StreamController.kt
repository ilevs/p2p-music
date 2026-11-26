package io.github.ilevs.p2pmusic.playback

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaSession
import io.github.ilevs.p2pmusic.LastPlayed
import io.github.ilevs.p2pmusic.SettingsStore
import io.github.ilevs.p2pmusic.engine.TorrentEngine
import io.github.ilevs.p2pmusic.engine.TorrentRef
import io.github.ilevs.p2pmusic.engine.isAudioFile
import io.github.ilevs.p2pmusic.engine.parseTorrentUri
import io.github.ilevs.p2pmusic.engine.toUri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.libtorrent4j.TorrentHandle

private const val STATS_INTERVAL_MS = 1_000L
private const val SAVE_INTERVAL_MS = 5_000L

data class AudioFile(
    val index: Int,
    val name: String,
    val size: Long,
)

data class StreamState(
    val torrentName: String? = null,
    val files: List<AudioFile> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val startupMs: Long? = null,
    val seekMs: Long? = null,
    val peers: Int = 0,
    val downloadRate: Int = 0,
)

/**
 * The streaming prototype: open a magnet, list its audio files, play them as one queue,
 * and measure how long playback takes to start and to recover after a seek.
 */
class StreamController(
    private val engine: TorrentEngine,
    private val settings: SettingsStore,
    val player: Player,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val mutableState = MutableStateFlow(StreamState())
    val state: StateFlow<StreamState> = mutableState.asStateFlow()

    @Volatile
    private var hash: String? = null
    private var startClock = 0L
    private var seekClock = 0L
    private var statsJob: Job? = null

    init {
        player.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) onReady()
                }
            },
        )
        // The car can lose power at any moment, so the spot to resume from is saved while playing.
        scope.launch {
            while (isActive) {
                val ref = player.currentMediaItem?.mediaId?.let(::parseTorrentUri)
                if (player.isPlaying && ref != null) {
                    settings.saveLastPlayed(LastPlayed(ref.hash, ref.fileIndex, player.currentPosition))
                }
                delay(SAVE_INTERVAL_MS)
            }
        }
    }

    fun open(magnet: String) = load { engine.openMagnet(magnet) }

    /** Opens a .torrent that [download] fetches off the main thread, e.g. from a tracker. */
    fun openTorrent(download: () -> ByteArray) = load { engine.openTorrent(download()) }

    private fun load(open: () -> TorrentHandle) {
        mutableState.update { StreamState(isLoading = true) }
        scope.launch {
            runCatching { withContext(io) { listAudio(open()) } }
                .onSuccess { (name, files) ->
                    mutableState.update { it.copy(torrentName = name, files = files, isLoading = false) }
                    watchStats()
                }.onFailure { error ->
                    mutableState.update { it.copy(isLoading = false, error = error.message ?: error.toString()) }
                }
        }
    }

    /** The opened torrent's audio files as a queue, for the player, the notification and the car. */
    fun queueItems(): List<MediaItem> {
        val torrentHash = hash ?: return emptyList()
        val current = state.value
        return current.files.map {
            val uri = TorrentRef(torrentHash, it.index).toUri()
            MediaItem
                .Builder()
                .setMediaId(uri)
                .setUri(uri)
                .setMediaMetadata(
                    MediaMetadata
                        .Builder()
                        .setTitle(it.name.substringAfterLast('/').substringBeforeLast('.'))
                        .setAlbumTitle(current.torrentName)
                        .setIsBrowsable(false)
                        .setIsPlayable(true)
                        .build(),
                ).build()
        }
    }

    fun play(file: AudioFile) {
        val items = queueItems()
        if (items.isEmpty()) return
        val files = state.value.files
        startClock = SystemClock.elapsedRealtime()
        seekClock = 0L
        mutableState.update { it.copy(startupMs = null) }
        player.setMediaItems(items, files.indexOf(file), 0L)
        player.prepare()
        player.play()
    }

    fun seekTo(positionMs: Long) {
        seekClock = SystemClock.elapsedRealtime()
        player.seekTo(positionMs)
    }

    // Not private: called from the listener object, and lint forbids synthetic accessors.
    internal fun onReady() {
        val now = SystemClock.elapsedRealtime()
        when {
            seekClock > 0 -> mutableState.update { it.copy(seekMs = now - seekClock) }
            startClock > 0 -> mutableState.update { it.copy(startupMs = now - startClock) }
        }
        seekClock = 0L
        startClock = 0L
    }

    /** Reopens the last played torrent and returns the queue at the saved spot, or null. */
    suspend fun restore(): MediaSession.MediaItemsWithStartPosition? {
        val last = settings.lastPlayed() ?: return null
        val (name, files) = withContext(io) { listAudio(engine.handle(last.hash)) }
        mutableState.update { StreamState(torrentName = name, files = files) }
        watchStats()
        val index = files.indexOfFirst { it.index == last.fileIndex }
        return if (index < 0) null else MediaSession.MediaItemsWithStartPosition(queueItems(), index, last.positionMs)
    }

    private fun listAudio(handle: TorrentHandle): Pair<String, List<AudioFile>> {
        hash = handle.infoHash().toHex()
        val info = handle.torrentFile()
        val storage = info.files()
        val files =
            (0 until storage.numFiles())
                .filter { isAudioFile(storage.fileName(it)) }
                .map { AudioFile(it, storage.filePath(it), storage.fileSize(it)) }
                .sortedBy { it.name }
        return info.name() to files
    }

    private fun watchStats() {
        statsJob?.cancel()
        statsJob =
            scope.launch {
                while (isActive) {
                    val status = withContext(io) { hash?.let(engine::find)?.status() }
                    if (status != null) {
                        mutableState.update { it.copy(peers = status.numPeers(), downloadRate = status.downloadRate()) }
                    }
                    delay(STATS_INTERVAL_MS)
                }
            }
    }
}
