package io.github.ilevs.p2pmusic

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.ilevs.p2pmusic.playback.PlaybackService
import io.github.ilevs.p2pmusic.ui.MainScreen
import io.github.ilevs.p2pmusic.ui.theme.P2pMusicTheme

class MainActivity : ComponentActivity() {
    private val graph get() = (application as P2pMusicApp).graph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) openMagnet(intent)
        setContent {
            P2pMusicTheme {
                MainScreen(graph)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // The session service promotes itself to foreground while playing, so music survives the app going away.
        startService(Intent(this, PlaybackService::class.java))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openMagnet(intent)
    }

    private fun openMagnet(intent: Intent?) {
        val uri = intent?.data
        if (uri?.scheme == "magnet") graph.stream.open(uri.toString())
    }
}
