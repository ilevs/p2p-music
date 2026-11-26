package io.github.ilevs.p2pmusic.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.IOException

private const val ROOT_ID = "root"

/**
 * The library tree that Android Auto and other controllers browse: for now the root lists the
 * tracks of the opened torrent. Also restores the last queue when Play is pressed after a restart.
 */
class LibraryCallback(
    private val stream: StreamController,
    private val scope: CoroutineScope,
) : MediaLibrarySession.Callback {
    private val root =
        MediaItem
            .Builder()
            .setMediaId(ROOT_ID)
            .setMediaMetadata(
                MediaMetadata
                    .Builder()
                    .setTitle("P2P Music")
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .build(),
            ).build()

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> = Futures.immediateFuture(LibraryResult.ofItem(root, params))

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val children = if (parentId == ROOT_ID) stream.queueItems() else emptyList()
        return Futures.immediateFuture(LibraryResult.ofItemList(children, params))
    }

    // Controllers send items without URIs; our media id is the torrent URI itself.
    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> =
        Futures.immediateFuture(mediaItems.map { it.buildUpon().setUri(it.mediaId).build() }.toMutableList())

    // Picking one track in the car plays the whole album from that track.
    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val queue = stream.queueItems()
        val index = queue.indexOfFirst { it.mediaId == mediaItems.singleOrNull()?.mediaId }
        return if (index >= 0) {
            Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(queue, index, startPositionMs))
        } else {
            super.onSetMediaItems(mediaSession, controller, mediaItems, startIndex, startPositionMs)
        }
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
        scope.launch {
            runCatching { stream.restore() }
                .onSuccess { if (it != null) future.set(it) else future.setException(IOException("Nothing to resume")) }
                .onFailure { future.setException(it) }
        }
        return future
    }
}
