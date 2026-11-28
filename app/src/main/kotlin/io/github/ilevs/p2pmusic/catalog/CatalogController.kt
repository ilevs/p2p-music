package io.github.ilevs.p2pmusic.catalog

import io.github.ilevs.p2pmusic.SavedAlbum
import io.github.ilevs.p2pmusic.SettingsStore
import io.github.ilevs.p2pmusic.engine.SoulseekEngine
import io.github.ilevs.p2pmusic.engine.toUri
import io.github.ilevs.p2pmusic.playback.AudioFile
import io.github.ilevs.p2pmusic.playback.OpenedAlbum
import io.github.ilevs.p2pmusic.playback.StreamController
import io.github.ilevs.p2pmusic.playback.listAudio
import io.github.ilevs.p2pmusic.playback.torrentAlbum
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
import org.libtorrent4j.TorrentHandle
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64

// The first track of this many Soulseek users is requested at once; the first to send wins.
private const val RACE_SIZE = 5
private const val RACE_ROUNDS = 2

// The file connection of a firewalled downloader (any phone behind NAT) opens about 41 s after the request.
private const val RACE_MS = 60_000L
private const val MAX_RELEASES_TRIED = 6

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

    /**
     * Plays from the library when the album was played before (works offline), else from a new release.
     * The search runs as part of the player's open, so the player drops the album before at once
     * and shows this one loading, then playing or the reason it was not found.
     * Soulseek only when the trackers have no release that holds the album, or fail.
     */
    fun play(album: Album) {
        stream.openAlbum(autoplay = true, album.coverUrl) {
            runCatching { openTorrent(album) }.getOrElse { playFromSoulseek(album, it.message ?: it.toString()) }
        }
    }

    // The saved release, else a new one; saved only once the torrent proved to hold the album.
    private fun openTorrent(album: Album): OpenedAlbum {
        val saved = settings.library().firstOrNull { it.album.id == album.id }
        // Older versions saved releases that do not hold the album; such a one is searched for again.
        val kept = runCatching { saved?.let { torrentAlbum(stream.engine.handle(it.hash), album.title) } }
        val opened = kept.getOrNull() ?: torrentAlbum(openRelease(album), album.title)
        settings.addToLibrary(SavedAlbum(album, checkNotNull(opened.hash)))
        mutableState.update { it.copy(library = settings.library().map { saved -> saved.album }) }
        return opened
    }

    // Tries the releases found for the album, then those found by the artist alone, until one holds the album:
    // the artist's most seeded release is often another album. The second search runs only if the first gave none.
    private fun openRelease(album: Album): TorrentHandle {
        val exact = runCatching { source.search("${album.artist} ${album.title}") }.getOrDefault(emptyList())
        val wide by lazy { runCatching { source.search(album.artist) } }
        val picks =
            sequence {
                yieldAll(rankTorrents(album, exact))
                yieldAll(rankTorrents(album, wide.getOrDefault(emptyList()) - exact.toSet()))
            }
        val opened =
            picks.take(MAX_RELEASES_TRIED).firstNotNullOfOrNull { pick -> lookInto(album, pick) }
        val answers = wide.exceptionOrNull()?.let { " ${it.message}" }.orEmpty()
        return opened ?: throw IOException("Альбом «${album.title}» не найден на трекерах (${source.name}).$answers")
    }

    // The handle of [pick] if it holds the album, else null: a release that fails to open is one more miss.
    private fun lookInto(
        album: Album,
        pick: SearchResult,
    ): TorrentHandle? =
        runCatching {
            // A release named after the album opens the usual way; the rest are only looked into,
            // and their .torrent answers at once where a magnet can take its whole wait.
            val handle =
                if (namesAlbum(album, pick)) {
                    source.open(pick, stream.engine)
                } else {
                    stream.engine.openTorrent(source.torrentFile(pick))
                }
            handle.also { listAudio(it, album.title) }
        }.getOrNull()

    /**
     * Races the first track of the best few Soulseek users that have [album] in a folder of its own; the winner
     * sends the rest of the album right after it, so the next track is on disk before it is needed.
     * [why] says what the trackers said.
     */
    private fun playFromSoulseek(
        album: Album,
        why: String,
    ): OpenedAlbum {
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
                .firstNotNullOfOrNull { round -> soulseek.raceFolders(round, RACE_MS) }
                ?: throw IOException("$why; в Soulseek все, у кого есть «${album.title}», сейчас заняты")
        tracks.drop(1).forEach { runCatching { soulseek.download(it) } }
        return OpenedAlbum(null, album.title, tracks.mapIndexed { i, f -> AudioFile(i, f.name, f.size, f.toUri()) })
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
