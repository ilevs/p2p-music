package io.github.ilevs.p2pmusic.playback

import android.net.Uri
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
import io.github.ilevs.p2pmusic.catalog.coverUrl
import io.github.ilevs.p2pmusic.engine.SoulseekEngine
import io.github.ilevs.p2pmusic.engine.TorrentEngine
import io.github.ilevs.p2pmusic.engine.TorrentRef
import io.github.ilevs.p2pmusic.engine.isAudioFile
import io.github.ilevs.p2pmusic.engine.parseTorrentUri
import io.github.ilevs.p2pmusic.engine.prefetchStart
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
import java.io.IOException
import java.util.logging.Logger

private const val STATS_INTERVAL_MS = 1_000L
private const val SAVE_INTERVAL_MS = 5_000L
private const val PREFETCH_MS = 30_000L

// Shows in logcat under the tag "p2pmusic", as the catalog's release tries do.
private val log = Logger.getLogger("p2pmusic")

data class AudioFile(
    val index: Int,
    val name: String,
    val size: Long,
    /** The media URI of a file outside a torrent (Soulseek); torrent files are addressed by [index]. */
    val uri: String? = null,
) {
    /** The file's name without its folders and extension. */
    val title: String get() = name.substringAfterLast('/').substringBeforeLast('.')
}

/** An album ready to play: its torrent's [hash], or null for files outside a torrent, its [name] and [files]. */
data class OpenedAlbum(
    val hash: String?,
    val name: String,
    val files: List<AudioFile>,
)

data class StreamState(
    val torrentName: String? = null,
    val files: List<AudioFile> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val coverUrl: String? = null,
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
    val soulseek: SoulseekEngine,
    private val settings: SettingsStore,
    val player: Player,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val mutableState = MutableStateFlow(StreamState())
    val state: StateFlow<StreamState> = mutableState.asStateFlow()

    @Volatile
    private var hash: String? = null

    // Read by a prefetch on the io thread, to stop as soon as another album is tapped.
    @Volatile
    private var opens = 0
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

                override fun onPlayerError(error: PlaybackException) = onError(error)
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
        openAlbum(null) { torrentAlbum(engine.openMagnet(magnet), null) }
    }

    /**
     * Opens the album that [open] returns off the main thread (a torrent or Soulseek files) and lists its tracks.
     * Nothing plays until a track is tapped: the track before plays on, and the first track's opening bytes
     * come in meanwhile, so a tap on it sounds at once.
     */
    fun openAlbum(
        coverUrl: String?,
        open: () -> OpenedAlbum,
    ) {
        mutableState.update { StreamState(isLoading = true) }
        val mine = ++opens
        scope.launch {
            val result = runCatching { withContext(io) { open() } }
            // A slower open of an album tapped earlier must not replace the one tapped after it.
            if (mine != opens) return@launch
            result
                .onSuccess { album ->
                    hash = album.hash
                    mutableState.update {
                        it.copy(torrentName = album.name, files = album.files, isLoading = false, coverUrl = coverUrl)
                    }
                    watchStats()
                    launch(io) { prefetchFirstTrack(engine, album) { mine != opens } }
                }.onFailure { error ->
                    mutableState.update { it.copy(isLoading = false, error = error.message ?: error.toString()) }
                }
        }
    }

    /** The opened torrent's audio files as a queue, for the player, the notification and the car. */
    fun queueItems(): List<MediaItem> {
        val torrentHash = hash
        val current = state.value
        return current.files.mapNotNull {
            val uri =
                it.uri ?: torrentHash?.let { hash -> TorrentRef(hash, it.index).toUri() } ?: return@mapNotNull null
            MediaItem
                .Builder()
                .setMediaId(uri)
                .setUri(uri)
                .setMediaMetadata(
                    MediaMetadata
                        .Builder()
                        .setTitle(it.title)
                        .setAlbumTitle(current.torrentName)
                        // The notification and the car show it too.
                        .setArtworkUri(current.coverUrl?.let(Uri::parse))
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
        mutableState.update { it.copy(startupMs = null, error = null) }
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
    internal fun onError(error: PlaybackException) {
        val track = player.currentMediaItem?.mediaId
        if (track != null && track != retriedTrack) {
            retriedTrack = track
            player.prepare()
        } else {
            // Without this the player just sits at 0:00 with no reason on screen.
            val reason = error.cause?.message ?: error.errorCodeName
            mutableState.update { it.copy(error = "Трек не играет: $reason") }
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
        // A torrent saved for an album it does not hold (a discography picked by older versions) is not resumed.
        val (name, files) =
            runCatching { withContext(io) { listAudio(engine.handle(spot.hash), saved?.album?.title) } }
                .getOrDefault(null to emptyList())
        hash = spot.hash
        mutableState.update { StreamState(torrentName = name, files = files, coverUrl = saved?.album?.coverUrl) }
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

    private fun watchStats() {
        statsJob?.cancel()
        statsJob =
            scope.launch {
                while (isActive) {
                    val handle = withContext(io) { hash?.let(engine::find) }
                    if (handle != null) {
                        val (status, trackers) = withContext(io) { handle.status() to trackerCounts(handle) }
                        // A disk error pauses the torrent silently; without this the track just never starts.
                        val diskError =
                            status.errorCode().takeIf { it.isError }?.let { "раздача остановлена, ${it.message}" }
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

// Blocks until the first track of a torrent [album] can start, at most PREFETCH_MS, or until [stop] holds.
// Not in the class: detekt caps its functions. Not private: called from a lambda, and lint forbids synthetic accessors.
internal fun prefetchFirstTrack(
    engine: TorrentEngine,
    album: OpenedAlbum,
    stop: () -> Boolean,
) {
    val hash = album.hash ?: return
    val first = album.files.firstOrNull() ?: return
    val started = SystemClock.elapsedRealtime()
    // A failure here only means the player waits for the track, as it did before.
    val ready = runCatching { prefetchStart(engine, TorrentRef(hash, first.index), PREFETCH_MS, stop) }
    log.info { "prefetch «${first.title}»: $ready in ${SystemClock.elapsedRealtime() - started} ms" }
}

// With an [album], only its files; a torrent that does not hold it is an error, not a reason to play another.
internal fun listAudio(
    handle: TorrentHandle,
    album: String? = null,
): Pair<String, List<AudioFile>> {
    val info = handle.torrentFile()
    val storage = info.files()
    val files =
        (0 until storage.numFiles())
            .filter { isAudioFile(storage.fileName(it)) }
            .map { AudioFile(it, storage.filePath(it), storage.fileSize(it)) }
            .sortedBy { it.name }
    val name = info.name()
    val ofAlbum = if (album == null) files else albumFiles(album, name, files)
    if (album != null && ofAlbum.isEmpty()) throw IOException("В раздаче «$name» нет альбома «$album»")
    return name to ofAlbum
}

/** [handle]'s album as [listAudio] finds it. */
internal fun torrentAlbum(
    handle: TorrentHandle,
    album: String?,
): OpenedAlbum {
    val (name, files) = listAudio(handle, album)
    return OpenedAlbum(handle.infoHash().toHex(), name, files)
}
