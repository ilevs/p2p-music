package io.github.ilevs.p2pmusic.catalog

import io.github.ilevs.p2pmusic.SavedAlbum
import io.github.ilevs.p2pmusic.SettingsStore
import io.github.ilevs.p2pmusic.engine.SoulseekEngine
import io.github.ilevs.p2pmusic.engine.toUri
import io.github.ilevs.p2pmusic.playback.AudioFile
import io.github.ilevs.p2pmusic.playback.StreamController
import io.github.ilevs.p2pmusic.search.SearchResult
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

// The first track of this many Soulseek users is requested at once; the first to send wins.
private const val RACE_SIZE = 3
private const val RACE_ROUNDS = 2
private const val RACE_MS = 20_000L

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
                stream.openHandle(album.title, album.coverUrl) { stream.engine.handle(saved.hash) }
            } else {
                // A tracker release first; Soulseek only when the trackers have nothing or fail.
                val torrent = runCatching { pickRelease(album) }
                val pick = torrent.getOrNull()
                if (pick == null) {
                    val why = torrent.exceptionOrNull()?.message ?: "На ${source.name} нет раздач с сидами"
                    playFromSoulseek(album, why)
                } else {
                    stream.openHandle(album.title, album.coverUrl) {
                        source.open(pick, stream.engine).also { handle ->
                            settings.addToLibrary(SavedAlbum(album, handle.infoHash().toHex()))
                            mutableState.update { it.copy(library = settings.library().map { saved -> saved.album }) }
                        }
                    }
                }
            }
            null
        }
    }

    // A release of the album itself, else a discography found by the artist alone;
    // a failed first search still tries the second, which reports the error.
    private fun pickRelease(album: Album): SearchResult? {
        val exact = runCatching { source.search("${album.artist} ${album.title}") }
        return pickTorrent(album, exact.getOrDefault(emptyList())) ?: pickTorrent(album, source.search(album.artist))
    }

    /**
     * Races the first track of the best few Soulseek users that have [album]; the winner sends the rest of the
     * album right after it, so the next track is on disk before it is needed. [why] says what the trackers said.
     */
    private fun playFromSoulseek(
        album: Album,
        why: String,
    ) {
        val soulseek = connectSoulseek(why)
        val folders =
            soulseekFolders(
                album,
                soulseek.search("${album.artist} ${album.title}") { enoughToRace(album, it, RACE_SIZE) },
            )
        if (folders.isEmpty()) throw IOException("$why; в Soulseek «${album.title}» тоже нет")
        val tracks =
            folders
                .take(RACE_SIZE * RACE_ROUNDS)
                .chunked(RACE_SIZE)
                .firstNotNullOfOrNull { round ->
                    soulseek.race(round.map { it.first() }, RACE_MS)?.let { winner -> round.first { it[0] == winner } }
                } ?: throw IOException("$why; в Soulseek все, у кого есть «${album.title}», сейчас заняты")
        tracks.drop(1).forEach { runCatching { soulseek.download(it) } }
        val files = tracks.mapIndexed { i, file -> AudioFile(i, file.name, file.size, file.toUri()) }
        stream.openFiles(album.title, album.coverUrl, files)
    }

    private fun connectSoulseek(why: String): SoulseekEngine {
        val user = settings.load()
        if (user.soulseekLogin.isBlank()) {
            throw IOException(
                "$why. Впишите логин Soulseek в настройках, чтобы искать и там",
            )
        }
        return stream.soulseek.apply { connect(user.soulseekLogin.trim(), user.soulseekPassword, user.seeding) }
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
