package io.github.ilevs.p2pmusic.catalog

import io.github.ilevs.p2pmusic.playback.StreamController
import io.github.ilevs.p2pmusic.search.TorrentSource
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

data class CatalogState(
    val albums: List<Album> = emptyList(),
    val isLoading: Boolean = false,
    val message: String? = null,
)

/** Album search in the catalog, and playing an album from a release the app picks on the tracker. */
class CatalogController(
    private val catalog: MusicBrainz,
    private val source: TorrentSource,
    private val stream: StreamController,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val mutableState = MutableStateFlow(CatalogState())
    val state: StateFlow<CatalogState> = mutableState.asStateFlow()

    fun search(query: String) {
        run { mutableState.update { it.copy(albums = catalog.searchAlbums(query)) } }
    }

    fun play(album: Album) {
        run {
            // A release of the album itself, else a discography found by the artist alone.
            val pick =
                pickTorrent(album, source.search("${album.artist} ${album.title}"))
                    ?: pickTorrent(album, source.search(album.artist))
                    ?: throw IOException("На ${source.name} нет раздач с сидами для «${album.title}»")
            stream.openTorrent(album.title) { source.torrentFile(pick) }
        }
    }

    private fun run(block: suspend () -> Unit) {
        mutableState.update { it.copy(isLoading = true, message = null) }
        scope.launch {
            val result = runCatching { withContext(io) { block() } }
            val error = result.exceptionOrNull()
            mutableState.update { it.copy(isLoading = false, message = error?.let { e -> e.message ?: e.toString() }) }
        }
    }
}
