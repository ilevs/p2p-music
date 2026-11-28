package io.github.ilevs.p2pmusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ilevs.p2pmusic.R
import io.github.ilevs.p2pmusic.catalog.Album
import io.github.ilevs.p2pmusic.catalog.CatalogController
import io.github.ilevs.p2pmusic.catalog.coverUrl
import io.github.ilevs.p2pmusic.search.SearchController
import io.github.ilevs.p2pmusic.search.SearchResult

private const val BYTES_PER_MB = 1_048_576f
private const val MESSAGE_LINES = 3

/** Albums from the catalog (the app picks the release), or raw releases from the tracker. */
@Composable
fun SearchScreen(
    catalog: CatalogController,
    controller: SearchController,
    onAlbum: (Album) -> Unit,
    onOpen: (SearchResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val catalogState by catalog.state.collectAsState()
    val state by controller.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var showAlbums by rememberSaveable { mutableStateOf(true) }
    val isLoading = if (showAlbums) catalogState.isLoading else state.isLoading
    val message = if (showAlbums) catalogState.message else state.message
    val keyboard = LocalSoftwareKeyboardController.current
    val search = {
        // The keyboard stays up otherwise and covers the results, which the app draws under it.
        keyboard?.hide()
        when {
            query.isBlank() -> Unit
            showAlbums -> catalog.search(query.trim())
            else -> controller.search(query.trim())
        }
    }
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SearchBar(query = query, onQuery = { query = it }, enabled = !isLoading, onSearch = search)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = showAlbums,
                onClick = { showAlbums = true },
                label = { Text(stringResource(R.string.albums)) },
            )
            FilterChip(
                selected = !showAlbums,
                onClick = { showAlbums = false },
                label = { Text(stringResource(R.string.releases)) },
            )
        }
        if (isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        // A few lines at most: on a head unit's small screen long tracker errors left no room for the results.
        message?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                maxLines = MESSAGE_LINES,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val showLibrary = showAlbums && catalogState.albums.isEmpty() && catalogState.library.isNotEmpty()
        if (showLibrary) Text(text = stringResource(R.string.library), style = MaterialTheme.typography.titleMedium)
        SearchResults(
            albums = if (showAlbums) catalogState.albums.ifEmpty { catalogState.library } else null,
            results = state.results,
            onAlbum = onAlbum,
            onOpen = onOpen,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Albums when [albums] is not null, otherwise tracker [results]. */
@Composable
private fun SearchBar(
    query: String,
    onQuery: (String) -> Unit,
    enabled: Boolean,
    onSearch: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            label = { Text(stringResource(R.string.search_label)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { onSearch() }),
            modifier =
                Modifier
                    .weight(
                        1f,
                    ).remoteKeys(
                        LocalFocusManager.current,
                        LocalSoftwareKeyboardController.current,
                        LocalInputModeManager.current,
                    ),
        )
        Button(
            onClick = onSearch,
            enabled = query.isNotBlank() && enabled,
            modifier = Modifier.padding(start = 8.dp),
        ) { Text(stringResource(R.string.search)) }
    }
}

@Composable
private fun SearchResults(
    albums: List<Album>?,
    results: List<SearchResult>,
    onAlbum: (Album) -> Unit,
    onOpen: (SearchResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier) {
        if (albums != null) {
            items(albums, key = { it.id }) { album ->
                TwoLineRow(
                    title = album.title,
                    details = listOf(album.artist, album.year).filter(String::isNotEmpty).joinToString(" · "),
                    onClick = { onAlbum(album) },
                    coverUrl = album.coverUrl,
                )
                HorizontalDivider()
            }
        } else {
            items(results, key = { "${it.source}/${it.id}" }) { result ->
                TwoLineRow(
                    title = result.title,
                    details =
                        stringResource(
                            R.string.result_details,
                            result.category,
                            result.sizeBytes / BYTES_PER_MB,
                            result.seeds,
                        ),
                    onClick = { onOpen(result) },
                )
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun TwoLineRow(
    title: String,
    details: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    coverUrl: String? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        coverUrl?.let { Cover(url = it, modifier = Modifier.size(56.dp)) }
        Column {
            Text(text = title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                text = details,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
