package io.github.ilevs.p2pmusic

import android.content.Context
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import io.github.ilevs.p2pmusic.catalog.CatalogController
import io.github.ilevs.p2pmusic.catalog.MusicBrainz
import io.github.ilevs.p2pmusic.engine.SoulseekEngine
import io.github.ilevs.p2pmusic.engine.SoulseekStalledException
import io.github.ilevs.p2pmusic.engine.TorrentEngine
import io.github.ilevs.p2pmusic.playback.SoulseekDataSource
import io.github.ilevs.p2pmusic.playback.StreamController
import io.github.ilevs.p2pmusic.playback.TorrentDataSource
import io.github.ilevs.p2pmusic.search.AllSources
import io.github.ilevs.p2pmusic.search.Knaben
import io.github.ilevs.p2pmusic.search.MemoryCookieJar
import io.github.ilevs.p2pmusic.search.NnmClub
import io.github.ilevs.p2pmusic.search.ProxySettings
import io.github.ilevs.p2pmusic.search.RuTracker
import io.github.ilevs.p2pmusic.search.Rutor
import io.github.ilevs.p2pmusic.search.SearchController
import io.github.ilevs.p2pmusic.search.trackerHttpClient
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
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
    val settings = SettingsStore(context)

    private val storage = context.getExternalFilesDir(null) ?: context.filesDir

    val engine = TorrentEngine(File(storage, "torrent"))

    private val soulseek = SoulseekEngine(File(storage, "soulseek"))

    val player: ExoPlayer =
        ExoPlayer
            .Builder(context)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(SoulseekDataSource.Factory(soulseek, TorrentDataSource.Factory(engine)))
                    .setLoadErrorHandlingPolicy(NoRetryOnStall),
            ).setLoadControl(
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

    val scope = MainScope()

    init {
        scope.launch(io) { engine.setSeeding(settings.load().seeding) }
    }

    val stream = StreamController(engine, soulseek, settings, player, scope, io)

    private val cookies = MemoryCookieJar()
    private var trackerClient: Pair<ProxySettings?, OkHttpClient>? = null

    private val tracker = RuTracker(client = ::trackerClient, credentials = { settings.load().credentials() })

    private val sources =
        AllSources(
            listOf(tracker, Rutor(client = ::trackerClient)),
            fallback = listOf(Knaben(client = ::trackerClient), NnmClub(client = ::trackerClient)),
        )

    val search = SearchController(sources, tracker, soulseek, engine, settings, scope, io)

    // The catalog is not blocked anywhere, so it goes direct, without the tracker proxy.
    val catalog = CatalogController(MusicBrainz(OkHttpClient()), sources, stream, settings, scope, io)

    // One client per proxy setting, so a changed proxy applies to the next request.
    @Synchronized
    internal fun trackerClient(): OkHttpClient {
        val proxy = settings.load().proxy()
        return trackerClient?.takeIf { it.first == proxy }?.second
            ?: trackerHttpClient(proxy, cookies).also { trackerClient = proxy to it }
    }
}

// The player retries a failed read four times: after a user sent nothing for two minutes, that kept
// "Загружаю трек…" up for about nine minutes before the error showed. Asking the same user again does not help.
@OptIn(UnstableApi::class)
private object NoRetryOnStall : DefaultLoadErrorHandlingPolicy() {
    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
        when (loadErrorInfo.exception) {
            is SoulseekStalledException -> C.TIME_UNSET
            else -> super.getRetryDelayMsFor(loadErrorInfo)
        }
}
