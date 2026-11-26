package io.github.ilevs.p2pmusic

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import io.github.ilevs.p2pmusic.engine.TorrentEngine
import io.github.ilevs.p2pmusic.playback.StreamController
import io.github.ilevs.p2pmusic.playback.TorrentDataSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import java.io.File

// Start playing after half a second of audio; keep up to two minutes decoded ahead,
// the file is on disk anyway. Tune with the startup numbers from the stream screen.
private const val MIN_BUFFER_MS = 15_000
private const val MAX_BUFFER_MS = 120_000
private const val START_BUFFER_MS = 500
private const val REBUFFER_MS = 1_000

/** Everything that lives as long as the app. Created on the main thread. */
@OptIn(UnstableApi::class)
class AppGraph(
    context: Context,
    io: CoroutineDispatcher = Dispatchers.IO,
) {
    val engine = TorrentEngine(File(context.getExternalFilesDir(null) ?: context.filesDir, "torrent"))

    val player: ExoPlayer =
        ExoPlayer
            .Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(TorrentDataSource.Factory(engine)))
            .setLoadControl(
                DefaultLoadControl
                    .Builder()
                    .setBufferDurationsMs(MIN_BUFFER_MS, MAX_BUFFER_MS, START_BUFFER_MS, REBUFFER_MS)
                    .build(),
            ).setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true,
            ).setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()

    val stream = StreamController(engine, player, MainScope(), io)
}
