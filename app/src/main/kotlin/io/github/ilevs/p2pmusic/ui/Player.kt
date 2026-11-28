package io.github.ilevs.p2pmusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import io.github.ilevs.p2pmusic.R
import io.github.ilevs.p2pmusic.playback.StreamController
import kotlinx.coroutines.delay

private const val TICK_MS = 500L
private const val ICON_PART = 0.6f

/** What the player shows about the current track. */
internal data class Playback(
    val title: String = "",
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isPlaying: Boolean = false,
    val hasTrack: Boolean = false,
    val isBuffering: Boolean = false,
) {
    val fraction: Float
        get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/** The [player]'s track and position, polled: the position has no listener. */
@Composable
internal fun playbackState(player: Player): State<Playback> =
    produceState(Playback(), player) {
        while (true) {
            value =
                Playback(
                    title =
                        player.mediaMetadata.title
                            ?.toString()
                            .orEmpty(),
                    positionMs = player.currentPosition,
                    durationMs = player.duration.coerceAtLeast(0L),
                    isPlaying = player.isPlaying,
                    hasTrack = player.currentMediaItem != null,
                    isBuffering = player.playbackState == Player.STATE_BUFFERING,
                )
            delay(TICK_MS)
        }
    }

/** The bar over the tabs while a track is loaded: what plays, pause, and a tap opens the player. */
@Composable
fun MiniPlayer(
    controller: StreamController,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val playback by playbackState(controller.player)
    val state by controller.state.collectAsState()
    if (!playback.hasTrack) return
    Surface(tonalElevation = 3.dp, modifier = modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column {
            LinearProgressIndicator(progress = { playback.fraction }, modifier = Modifier.fillMaxWidth())
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                state.coverUrl?.let { Cover(it, Modifier.size(40.dp)) }
                Text(
                    text = playback.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                )
                PlayPauseButton(controller.player, playback.isPlaying)
            }
        }
    }
}

@Composable
internal fun PlayPauseButton(
    player: Player,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    FilledIconButton(
        onClick = {
            if (player.isPlaying) {
                player.pause()
            } else {
                if (player.playbackState == Player.STATE_IDLE) player.prepare()
                player.play()
            }
        },
        modifier = modifier,
    ) {
        val icon = Modifier.fillMaxSize(ICON_PART)
        if (isPlaying) {
            Icon(painterResource(R.drawable.ic_pause), stringResource(R.string.pause), icon)
        } else {
            Icon(Icons.Filled.PlayArrow, stringResource(R.string.play), icon)
        }
    }
}
