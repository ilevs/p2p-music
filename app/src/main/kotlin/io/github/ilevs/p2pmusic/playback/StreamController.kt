package io.github.ilevs.p2pmusic.playback

import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import io.github.ilevs.p2pmusic.LastPlayed
import io.github.ilevs.p2pmusic.SavedAlbum
import io.github.ilevs.p2pmusic.SettingsStore
import io.github.ilevs.p2pmusic.catalog.albumFiles
import io.github.ilevs.p2pmusic.engine.TorrentEngine
import io.github.ilevs.p2pmusic.engine.TorrentRef
import io.github.ilevs.p2pmusic.engine.isAudioFile
import io.github.ilevs.p2pmusic.engine.parseTorrentUri
import io.github.ilevs.p2pmusic.engine.toUri
import io.github.ilevs.p2pmusic.engine.trackerCounts
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
import org.libtorrent4j.AddTorrentParams
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
    val trackersUp: Int = 0,
    val trackers: Int = 0,
    val dhtNodes: Long = 0,
)

/**
 * The streaming prototype: open a magnet, list its audio files, play them as one queue,
 * and measure how long playback takes to start and to recover after a seek.
 */
class StreamController(
    val engine: TorrentEngine,
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
    private var retriedTrack: String? = null

    init {
        player.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) onReady()
                }

                override fun onPlayerError(error: PlaybackException) = onError()
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

    fun open(magnet: String) {
        // Count peers while the metadata is still on its way, so a stuck magnet shows why.
        hash =
            runCatching {
                AddTorrentParams
                    .parseMagnetUri(magnet)
                    .infoHashes.best
                    .toHex()
            }.getOrNull()
        watchStats()
        openHandle { engine.openMagnet(magnet) }
    }

    /**
     * Opens the torrent that [open] returns off the main thread.
     * With an [album], keeps only that album's files (the torrent may be a discography) and plays it.
     */
    fun openHandle(
        album: String? = null,
        open: () -> TorrentHandle,
    ) {
        mutableState.update { StreamState(isLoading = true) }
        scope.launch {
            runCatching { withContext(io) { listAudio(open()) } }
                .onSuccess { (name, all) ->
                    val files = album?.let { albumFiles(it, all) } ?: all
                    mutableState.update { it.copy(torrentName = name, files = files, isLoading = false) }
                    watchStats()
                    if (album != null) files.firstOrNull()?.let(::play)
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

    // shortcut: one retry per track, because libtorrent reports a piece as verified while its blocks still wait
    // in the write queue, and the file then reads as zeros ("no extractor could read the stream"); read through
    // libtorrent's read_piece if errors show up in the middle of tracks.
    internal fun onError() {
        val track = player.currentMediaItem?.mediaId
        if (track != null && track != retriedTrack) {
            retriedTrack = track
            player.prepare()
        }
    }

    // Not private: called from the listener object, and lint forbids synthetic accessors.
    internal fun onReady() {
        retriedTrack = null
        val now = SystemClock.elapsedRealtime()
        when {
            seekClock > 0 -> mutableState.update { it.copy(seekMs = now - seekClock) }
            startClock > 0 -> mutableState.update { it.copy(startupMs = now - startClock) }
        }
        seekClock = 0L
        startClock = 0L
    }

    /**
     * Reopens [saved] from its first track, or else the last played torrent at the saved spot.
     * Returns the queue for the session, or null when there is nothing to play.
     */
    @OptIn(UnstableApi::class) // MediaItemsWithStartPosition, the only way to resume at a position
    suspend fun restore(saved: SavedAlbum? = null): MediaSession.MediaItemsWithStartPosition? {
        val spot = saved?.let { LastPlayed(it.hash, -1, 0L) } ?: settings.lastPlayed() ?: return null
        val (name, all) = withContext(io) { listAudio(engine.handle(spot.hash)) }
        val files = saved?.let { albumFiles(it.album.title, all) } ?: all
        mutableState.update { StreamState(torrentName = name, files = files) }
        watchStats()
        val index = if (saved != null) 0 else files.indexOfFirst { it.index == spot.fileIndex }
        return if (index !in
            files.indices
        ) {
            null
        } else {
            MediaSession.MediaItemsWithStartPosition(queueItems(), index, spot.positionMs)
        }
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
                    val handle = withContext(io) { hash?.let(engine::find) }
                    if (handle != null) {
                        val (status, trackers) = withContext(io) { handle.status() to trackerCounts(handle) }
                        // A disk error pauses the torrent silently; without this the track just never starts.
                        val diskError = status.errorCode().takeIf { it.isError }?.message
                        mutableState.update {
                            it.copy(
                                peers = status.numPeers(),
                                downloadRate = status.downloadRate(),
                                error = diskError ?: it.error,
                                // Shows a blocked tracker or DHT when a torrent finds no peers.
                                trackersUp = trackers.first,
                                trackers = trackers.second,
                                dhtNodes = engine.dhtNodes,
                            )
                        }
                    }
                    delay(STATS_INTERVAL_MS)
                }
            }
    }
}
