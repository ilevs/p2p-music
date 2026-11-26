package io.github.ilevs.p2pmusic.catalog

import io.github.ilevs.p2pmusic.SavedAlbum
import io.github.ilevs.p2pmusic.SettingsStore
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
import org.libtorrent4j.TorrentInfo
import java.io.IOException

data class CatalogState(
    val albums: List<Album> = emptyList(),
    val library: List<Album> = emptyList(),
    val isLoading: Boolean = false,
    val message: String? = null,
)

/** Album search in the catalog, and playing an album from a release the app picks on the tracker. */
class CatalogController(
    private val catalog: MusicBrainz,
    private val source: TorrentSource,
    private val stream: StreamController,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val mutableState = MutableStateFlow(CatalogState(library = settings.library().map { it.album }))
    val state: StateFlow<CatalogState> = mutableState.asStateFlow()

    fun search(query: String) {
        run { mutableState.update { it.copy(albums = catalog.searchAlbums(query)) } }
    }

    /** Plays from the library when the album was played before (works offline), else from a new release. */
    fun play(album: Album) {
        run {
            val saved = settings.library().firstOrNull { it.album.id == album.id }
            if (saved != null) {
                stream.openHandle(album.title) { stream.engine.handle(saved.hash) }
            } else {
                // A release of the album itself, else a discography found by the artist alone.
                val pick =
                    pickTorrent(album, source.search("${album.artist} ${album.title}"))
                        ?: pickTorrent(album, source.search(album.artist))
                        ?: throw IOException("На ${source.name} нет раздач с сидами для «${album.title}»")
                val bytes = source.torrentFile(pick)
                settings.addToLibrary(SavedAlbum(album, TorrentInfo.bdecode(bytes).infoHash().toHex()))
                mutableState.update { it.copy(library = settings.library().map { saved -> saved.album }) }
                stream.openHandle(album.title) { stream.engine.openTorrent(bytes) }
            }
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
