package io.github.ilevs.p2pmusic.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ilevs.p2pmusic.R
import io.github.ilevs.p2pmusic.playback.StreamController
import io.github.ilevs.p2pmusic.playback.StreamState

private const val MS_PER_SECOND = 1_000f
private const val BYTES_PER_MB = 1_048_576f

/** Opens a given magnet, and shows what the torrent in the player is doing: peers, trackers, DHT, timings. */
@Composable
fun DebugScreen(
    controller: StreamController,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    var magnet by rememberSaveable { mutableStateOf("") }
    Column(modifier = modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
        StatusText(state)
        Text(text = statsText(state), style = MaterialTheme.typography.bodySmall)
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
