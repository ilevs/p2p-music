package io.github.ilevs.p2pmusic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.github.ilevs.p2pmusic.ui.HomeScreen
import io.github.ilevs.p2pmusic.ui.theme.P2pMusicTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            P2pMusicTheme {
                HomeScreen()
            }
        }
    }
}
