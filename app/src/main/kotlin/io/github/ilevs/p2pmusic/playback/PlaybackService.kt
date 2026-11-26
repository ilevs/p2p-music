package io.github.ilevs.p2pmusic.playback

import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import io.github.ilevs.p2pmusic.P2pMusicApp

/** Keeps playback alive in the background and exposes it to the notification, Bluetooth and the car. */
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val player = (application as P2pMusicApp).graph.player
        session = MediaSession.Builder(this, player).build().also(::addSession)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        // The player belongs to the app graph and outlives the service; only the session goes.
        session?.release()
        session = null
        super.onDestroy()
    }
}
