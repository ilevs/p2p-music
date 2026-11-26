package io.github.ilevs.p2pmusic

import android.app.Application

class P2pMusicApp : Application() {
    val graph: AppGraph by lazy { AppGraph(this) }
}
