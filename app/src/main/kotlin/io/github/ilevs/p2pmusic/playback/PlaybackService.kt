package io.github.ilevs.p2pmusic.playback

import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import io.github.ilevs.p2pmusic.P2pMusicApp

/**
 * Keeps playback alive in the background and exposes it to the notification, Bluetooth,
 * steering wheel buttons and Android Auto, which browses the library through [LibraryCallback].
 */
class PlaybackService : MediaLibraryService() {
    private var session: MediaLibrarySession? = null

    override fun onCreate() {
        super.onCreate()
        val graph = (application as P2pMusicApp).graph
        session =
            MediaLibrarySession
                .Builder(this, graph.player, LibraryCallback(graph.stream, graph.settings, graph.scope))
                .build()
                .also(::addSession)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? = session

    override fun onDestroy() {
        // The player belongs to the app graph and outlives the service; only the session goes.
        session?.release()
        session = null
        super.onDestroy()
    }
}
