package io.github.ilevs.p2pmusic

import android.app.Application

class P2pMusicApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    override fun onCreate() {
        super.onCreate()
        // Before anything that builds a message: the app's resources follow the system or per-app language.
        Texts.lookup = { id, args -> getString(id, *args) }
    }
}
