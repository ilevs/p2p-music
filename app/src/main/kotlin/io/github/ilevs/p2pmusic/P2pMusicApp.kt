package io.github.ilevs.p2pmusic

import android.app.Application

class P2pMusicApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }

    // The args arrive as an array and getString takes only varargs; the copy is of a few words per message.
    @Suppress("SpreadOperator")
    override fun onCreate() {
        super.onCreate()
        // Before anything that builds a message: the app's resources follow the system or per-app language.
        Texts.lookup = { id, args -> getString(id, *args) }
    }
}
