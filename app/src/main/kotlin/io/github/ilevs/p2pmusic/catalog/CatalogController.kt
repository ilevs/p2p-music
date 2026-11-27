package io.github.ilevs.p2pmusic.catalog

import io.github.ilevs.p2pmusic.SavedAlbum
import io.github.ilevs.p2pmusic.SettingsStore
import io.github.ilevs.p2pmusic.playback.StreamController
import io.github.ilevs.p2pmusic.search.TorrentSource
import io.github.ilevs.p2pmusic.search.open
import io.github.ilevs.p2pmusic.toJson
import io.github.ilevs.p2pmusic.toSavedAlbum
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64

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
        run {
            mutableState.update { it.copy(albums = catalog.searchAlbums(query)) }
            null
        }
    }

    /** Plays from the library when the album was played before (works offline), else from a new release. */
    fun play(album: Album) {
        run {
            val saved = settings.library().firstOrNull { it.album.id == album.id }
            if (saved != null) {
                stream.openHandle(album.title) { stream.engine.handle(saved.hash) }
            } else {
                // A release of the album itself, else a discography found by the artist alone;
                // a failed first search still tries the second, which reports the error.
                val exact = runCatching { source.search("${album.artist} ${album.title}") }
                val pick =
                    pickTorrent(album, exact.getOrDefault(emptyList()))
                        ?: pickTorrent(album, source.search(album.artist))
                        ?: throw IOException("На ${source.name} нет раздач с сидами для «${album.title}»")
                stream.openHandle(album.title) {
                    source.open(pick, stream.engine).also { handle ->
                        settings.addToLibrary(SavedAlbum(album, handle.infoHash().toHex()))
                        mutableState.update { it.copy(library = settings.library().map { saved -> saved.album }) }
                    }
                }
            }
            null
        }
    }

    /** Writes the library with each album's .torrent, so another device plays it without the tracker. */
    fun exportLibrary(output: OutputStream) {
        run {
            val albums =
                settings.library().map { saved ->
                    val torrent = File(stream.engine.torrentsDir, "${saved.hash}.torrent").takeIf { it.exists() }
                    saved.toJson().put("torrent", torrent?.readBytes()?.let(Base64.getEncoder()::encodeToString))
                }
            output.use { it.write(JSONObject().put("albums", JSONArray(albums)).toString().toByteArray()) }
            "Сохранено альбомов: ${albums.size}"
        }
    }

    /** Adds albums from an export file; their .torrent files are checked and cached. */
    fun importLibrary(input: InputStream) {
        run {
            val albums = JSONObject(input.use { String(it.readBytes()) }).getJSONArray("albums")
            // Oldest first, so the file's order survives addToLibrary putting each one on top.
            for (i in albums.length() - 1 downTo 0) {
                val item = albums.getJSONObject(i)
                val torrent = item.optString("torrent").takeIf(String::isNotEmpty)
                val hash =
                    torrent?.let {
                        stream.engine
                            .openTorrent(Base64.getDecoder().decode(it))
                            .infoHash()
                            .toHex()
                    }
                settings.addToLibrary(item.toSavedAlbum().let { if (hash != null) it.copy(hash = hash) else it })
            }
            mutableState.update { it.copy(library = settings.library().map { saved -> saved.album }) }
            "Добавлено альбомов: ${albums.length()}"
        }
    }

    // The block returns a message for the user, or null.
    private fun run(block: suspend () -> String?) {
        mutableState.update { it.copy(isLoading = true, message = null) }
        scope.launch {
            val result = runCatching { withContext(io) { block() } }
            val message = result.getOrElse { it.message ?: it.toString() }
            mutableState.update { it.copy(isLoading = false, message = message) }
        }
    }
}
