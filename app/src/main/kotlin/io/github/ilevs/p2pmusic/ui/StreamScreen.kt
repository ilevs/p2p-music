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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ilevs.p2pmusic.R
import io.github.ilevs.p2pmusic.catalog.CatalogController
import io.github.ilevs.p2pmusic.catalog.SoulseekSource
import io.github.ilevs.p2pmusic.playback.StreamController
import io.github.ilevs.p2pmusic.playback.StreamState

private const val MS_PER_SECOND = 1_000L
private const val BYTES_PER_MB = 1_048_576f
private const val SECONDS_PER_MINUTE = 60

/**
 * The player: the current track with its controls stays in place, the album's source and tracks scroll under it.
 */
@Composable
fun StreamScreen(
    controller: StreamController,
    catalog: CatalogController,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    Box(modifier = modifier.fillMaxSize().padding(16.dp)) {
        // Landscape head units are short: the controls go beside the tracks instead of above them.
        if (LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                PlayerPanel(controller, state, Modifier.weight(1f))
                TrackList(controller, catalog, state, Modifier.weight(1f))
            }
        } else {
            Column {
                PlayerPanel(controller, state)
                TrackList(controller, catalog, state, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun TrackList(
    controller: StreamController,
    catalog: CatalogController,
    state: StreamState,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.albumId?.let { album -> item { SourcePicker(catalog, album, state.torrentName) } }
        item { StatusText(state) }
        items(state.files, key = { it.index }) { file ->
            Text(
                text = file.title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable { controller.play(file) }.padding(vertical = 12.dp),
            )
        }
    }
}

/** Where the catalog album [albumId] plays from, and the other releases and Soulseek to switch to. */
@Composable
private fun SourcePicker(
    catalog: CatalogController,
    albumId: String,
    release: String?,
    modifier: Modifier = Modifier,
) {
    val catalogState by catalog.state.collectAsState()
    var expanded by remember { mutableStateOf(false) }
    val picked = catalogState.picked?.takeIf { catalogState.sourcesOf == albumId }
    Box(modifier = modifier) {
        OutlinedButton(
            onClick = {
                expanded = true
                catalog.findSources(albumId)
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            val name = picked?.title ?: release ?: stringResource(R.string.not_measured)
            Text(
                text = stringResource(R.string.source, name),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (catalogState.sourcesOf != albumId) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sources_searching)) },
                    onClick = {},
                    enabled = false,
                )
            }
            val sources = if (catalogState.sourcesOf == albumId) catalogState.sources else emptyList()
            sources.forEach { source ->
                val size = source.sizeBytes / BYTES_PER_MB
                val details =
                    if (source == SoulseekSource) {
                        stringResource(R.string.soulseek_details)
                    } else {
                        stringResource(R.string.result_details, source.source, size, source.seeds)
                    }
                val current = source == picked
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(text = source.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = if (current) stringResource(R.string.source_current, details) else details,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        catalog.playFrom(albumId, source)
                    },
                    // The source playing now: picking it again would only reopen it.
                    enabled = !current,
                )
            }
        }
    }
}

@Composable
internal fun StatusText(state: StreamState) {
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

private fun formatTime(ms: Long): String {
    val seconds = ms / MS_PER_SECOND
    return "%d:%02d".format(seconds / SECONDS_PER_MINUTE, seconds % SECONDS_PER_MINUTE)
}
