package io.github.ilevs.p2pmusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import io.github.ilevs.p2pmusic.R
import io.github.ilevs.p2pmusic.playback.StreamController
import io.github.ilevs.p2pmusic.playback.StreamState
import kotlinx.coroutines.delay

private const val TICK_MS = 500L
private const val MS_PER_SECOND = 1_000f
private const val BYTES_PER_MB = 1_048_576f
private const val SECONDS_PER_MINUTE = 60

/** Debug screen of the streaming prototype: paste a magnet, tap a track, watch the startup numbers. */
@Composable
fun StreamScreen(
    controller: StreamController,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    var magnet by rememberSaveable { mutableStateOf("") }
    // One scrolling list: on short screens (head units, small emulators) fixed panels left the tracks no height.
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = magnet,
                    onValueChange = { magnet = it },
                    label = { Text(stringResource(R.string.magnet_label)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = { controller.open(magnet.trim()) },
                    enabled = magnet.isNotBlank() && !state.isLoading,
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text(stringResource(R.string.open)) }
            }
        }
        item { StatusText(state) }
        items(state.files, key = { it.index }) { file ->
            Text(
                text = file.name,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable { controller.play(file) }.padding(vertical = 12.dp),
            )
        }
        item { PlayerPanel(controller) }
        item { Text(text = statsText(state), style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun StatusText(state: StreamState) {
    val text =
        when {
            state.isLoading -> stringResource(R.string.loading_metadata)
            state.error != null -> stringResource(R.string.error, state.error)
            state.torrentName != null && state.files.isEmpty() -> stringResource(R.string.no_audio)
            else -> state.torrentName.orEmpty()
        }
    Text(text = text, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun PlayerPanel(
    controller: StreamController,
    modifier: Modifier = Modifier,
) {
    val player = controller.player
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var dragFraction by remember { mutableFloatStateOf(-1f) }
    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition
            durationMs = player.duration.coerceAtLeast(0L)
            isPlaying = player.isPlaying
            title =
                player.mediaMetadata.title
                    ?.toString()
                    .orEmpty()
            delay(TICK_MS)
        }
    }
    Column(modifier = modifier.fillMaxWidth()) {
        Text(text = title, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Slider(
            value = if (dragFraction >= 0f) dragFraction else fraction(positionMs, durationMs),
            onValueChange = { dragFraction = it },
            onValueChangeFinished = {
                controller.seekTo((dragFraction * durationMs).toLong())
                dragFraction = -1f
            },
            enabled = durationMs > 0,
        )
        Text(text = stringResource(R.string.position, formatTime(positionMs), formatTime(durationMs)))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = player::seekToPreviousMediaItem) { Text(stringResource(R.string.previous)) }
            Button(onClick = { if (player.isPlaying) player.pause() else playOrResume(player) }) {
                Text(stringResource(if (isPlaying) R.string.pause else R.string.play))
            }
            FilledTonalButton(onClick = player::seekToNextMediaItem) { Text(stringResource(R.string.next)) }
        }
    }
}

@Composable
private fun statsText(state: StreamState): String {
    val notMeasured = stringResource(R.string.not_measured)
    val startup = state.startupMs?.let { stringResource(R.string.seconds, it / MS_PER_SECOND) } ?: notMeasured
    val seek = state.seekMs?.let { stringResource(R.string.seconds, it / MS_PER_SECOND) } ?: notMeasured
    val speed = stringResource(R.string.speed, state.downloadRate / BYTES_PER_MB)
    return stringResource(
        R.string.stats,
        startup,
        seek,
        state.peers,
        speed,
        state.trackersUp,
        state.trackers,
        state.dhtNodes,
    )
}

private fun playOrResume(player: Player) {
    if (player.playbackState == Player.STATE_IDLE) player.prepare()
    player.play()
}

private fun fraction(
    positionMs: Long,
    durationMs: Long,
): Float = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f

private fun formatTime(ms: Long): String {
    val seconds = ms / MS_PER_SECOND.toLong()
    return "%d:%02d".format(seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
}
