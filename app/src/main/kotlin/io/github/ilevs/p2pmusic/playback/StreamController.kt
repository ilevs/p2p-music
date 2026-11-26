package io.github.ilevs.p2pmusic.playback

import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import io.github.ilevs.p2pmusic.engine.TorrentEngine
import io.github.ilevs.p2pmusic.engine.TorrentRef
import io.github.ilevs.p2pmusic.engine.isAudioFile
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

private const val STATS_INTERVAL_MS = 1_000L

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
    }

    fun open(magnet: String) {
        mutableState.update { StreamState(isLoading = true) }
        scope.launch {
            runCatching { withContext(io) { listAudio(magnet) } }
                .onSuccess { (name, files) ->
                    mutableState.update { it.copy(torrentName = name, files = files, isLoading = false) }
                    watchStats()
                }.onFailure { error ->
                    mutableState.update { it.copy(isLoading = false, error = error.message ?: error.toString()) }
                }
        }
    }

    fun play(file: AudioFile) {
        val torrentHash = hash ?: return
        val files = state.value.files
        val items =
            files.map {
                MediaItem
                    .Builder()
                    .setMediaId(TorrentRef(torrentHash, it.index).toUri())
                    .setUri(TorrentRef(torrentHash, it.index).toUri())
                    .setMediaMetadata(MediaMetadata.Builder().setTitle(it.name).build())
                    .build()
            }
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

    private fun listAudio(magnet: String): Pair<String, List<AudioFile>> {
        val handle = engine.openMagnet(magnet)
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
