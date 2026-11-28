package io.github.ilevs.p2pmusic.ui

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ilevs.p2pmusic.R
import io.github.ilevs.p2pmusic.playback.StreamController
import io.github.ilevs.p2pmusic.playback.StreamState

private const val MS_PER_SECOND = 1_000f
private const val BYTES_PER_MB = 1_048_576f
private const val SECONDS_PER_MINUTE = 60

/** The player: the current track with its controls stays in place, the album's tracks scroll under it. */
@Composable
fun StreamScreen(
    controller: StreamController,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    Box(modifier = modifier.fillMaxSize().padding(16.dp)) {
        // Landscape head units are short: the controls go beside the tracks instead of above them.
        if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PlayerPanel(controller, state, Modifier.weight(1f))
                TrackList(controller, state, Modifier.weight(1f))
            }
        } else {
            Column {
                PlayerPanel(controller, state)
                TrackList(controller, state, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TrackList(
    controller: StreamController,
    state: StreamState,
    modifier: Modifier = Modifier,
) {
    var magnet by rememberSaveable { mutableStateOf("") }
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { StatusText(state) }
        items(state.files, key = { it.index }) { file ->
            Text(
                text = file.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable { controller.play(file) }.padding(vertical = 12.dp),
            )
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = magnet,
                    onValueChange = { magnet = it },
                    label = { Text(stringResource(R.string.magnet_label)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f).leaveOnUpDown(LocalFocusManager.current),
                )
                Button(
                    onClick = { controller.open(magnet.trim()) },
                    enabled = magnet.isNotBlank() && !state.isLoading,
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text(stringResource(R.string.open)) }
            }
        }
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
    state: StreamState,
    modifier: Modifier = Modifier,
) {
    val player = controller.player
    val playback by playbackState(player)
    var dragFraction by remember { mutableFloatStateOf(-1f) }
    if (!playback.hasTrack) return
    Column(modifier = modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            state.coverUrl?.let { Cover(it, Modifier.padding(end = 12.dp).size(64.dp)) }
            Text(
                text = playback.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Slider(
            value = if (dragFraction >= 0f) dragFraction else playback.fraction,
            onValueChange = { dragFraction = it },
            onValueChangeFinished = {
                controller.seekTo((dragFraction * playback.durationMs).toLong())
                dragFraction = -1f
            },
            enabled = playback.durationMs > 0,
        )
        Text(
            text =
                if (playback.isBuffering) {
                    // Why there is no sound yet: no peers, or they are slow.
                    val speed = stringResource(R.string.speed, state.downloadRate / BYTES_PER_MB)
                    stringResource(R.string.buffering, state.peers, speed)
                } else {
                    stringResource(R.string.position, formatTime(playback.positionMs), formatTime(playback.durationMs))
                },
            style = MaterialTheme.typography.bodySmall,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            IconButton(onClick = player::seekToPreviousMediaItem) {
                Icon(painterResource(R.drawable.ic_previous), stringResource(R.string.previous))
            }
            PlayPauseButton(player, playback.isPlaying, Modifier.size(64.dp))
            IconButton(onClick = player::seekToNextMediaItem) {
                Icon(painterResource(R.drawable.ic_next), stringResource(R.string.next))
            }
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

private fun formatTime(ms: Long): String {
    val seconds = ms / MS_PER_SECOND.toLong()
    return "%d:%02d".format(seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
}
