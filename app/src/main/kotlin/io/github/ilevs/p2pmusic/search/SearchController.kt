package io.github.ilevs.p2pmusic.search

import io.github.ilevs.p2pmusic.R
import io.github.ilevs.p2pmusic.SettingsStore
import io.github.ilevs.p2pmusic.catalog.audioFolders
import io.github.ilevs.p2pmusic.engine.SoulseekEngine
import io.github.ilevs.p2pmusic.engine.SoulseekFile
import io.github.ilevs.p2pmusic.engine.TorrentEngine
import io.github.ilevs.p2pmusic.engine.toUri
import io.github.ilevs.p2pmusic.playback.AudioFile
import io.github.ilevs.p2pmusic.playback.OpenedAlbum
import io.github.ilevs.p2pmusic.playback.torrentAlbum
import io.github.ilevs.p2pmusic.text
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** [SearchResult.source] of a Soulseek folder. */
const val SOULSEEK = "Soulseek"

// Enough folders to show, and the most the list shows.
private const val ENOUGH_FOLDERS = 10
private const val MAX_FOLDERS = 30
private const val ALBUM_TRACKS = 3
private const val RACE_MS = 60_000L

data class SearchState(
    val results: List<SearchResult> = emptyList(),
    val isLoading: Boolean = false,
    val message: String? = null,
)

/**
 * Search on the built-in trackers, and on Soulseek when they find nothing; the "check login" button of the
 * settings screen.
 */
class SearchController(
    private val source: TorrentSource,
    private val tracker: RuTracker,
    private val soulseek: SoulseekEngine,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val mutableState = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = mutableState.asStateFlow()

    // The files of each Soulseek folder in the results, by its result id.
    @Volatile
    private var folders = emptyMap<String, List<SoulseekFile>>()

    fun search(query: String) {
        run {
            val torrents = runCatching { source.search(query) }
            val found = torrents.getOrDefault(emptyList()).sortedByDescending { it.seeds }
            if (found.isEmpty()) {
                val trackers = torrents.exceptionOrNull()?.message ?: "${source.name}: ${text(R.string.nothing_found)}"
                val (results, answer) = searchSoulseek(query)
                // Soulseek first: a long tracker error is cut off on a small screen.
                results to "$SOULSEEK: $answer. $trackers"
            } else {
                found to null
            }
        }
    }

    /** The release or Soulseek folder of [result], ready to play; a folder's tracks start downloading. Blocking. */
    fun open(
        result: SearchResult,
        engine: TorrentEngine,
    ): OpenedAlbum {
        if (result.id !in folders) return torrentAlbum(source.open(result, engine), null)
        // One user often keeps us queued for minutes; the same release under the same folder name sits with others.
        val twins = folders.values.filter { folderResult(it).title.equals(result.title, ignoreCase = true) }
        // Waiting on without a sender would spin forever, so say so and let the user pick another folder.
        val files =
            soulseek.raceFolders(twins, RACE_MS)
                ?: throw IOException(text(R.string.soulseek_folder_stalled, twins.size, result.title))
        // In order: the user sends them one after another, so each next track is on disk before it is needed.
        files.drop(1).forEach { runCatching { soulseek.download(it) } }
        return OpenedAlbum(null, result.title, files.mapIndexed { i, f -> AudioFile(i, f.name, f.size, f.toUri()) })
    }

    fun checkLogin() {
        run {
            tracker.login()
            state.value.results to text(R.string.logged_in, tracker.name)
        }
    }

    // Folders that match [query] as results, and what Soulseek answered, for the message.
    private fun searchSoulseek(query: String): Pair<List<SearchResult>, String> {
        val user = settings.load()
        val found =
            runCatching {
                check(user.soulseekLogin.isNotBlank()) { text(R.string.soulseek_no_login) }
                soulseek.connect(user.soulseekLogin.trim(), user.soulseekPassword, user.seeding)
                // Albums before stray tracks; stable, so a free slot and speed still lead within each.
                audioFolders(soulseek.search(query) { audioFolders(it).size >= ENOUGH_FOLDERS })
                    .sortedByDescending { it.size >= ALBUM_TRACKS }
                    .take(MAX_FOLDERS)
            }
        val list = found.getOrDefault(emptyList())
        folders = list.associateBy(::folderId)
        val answer =
            found.exceptionOrNull()?.let { it.message ?: it.toString() }
                ?: if (list.isEmpty()) text(R.string.nothing_found) else text(R.string.soulseek_folders, list.size)
        return list.map(::folderResult) to answer
    }

    private fun run(block: () -> Pair<List<SearchResult>, String?>) {
        mutableState.update { it.copy(isLoading = true, message = null) }
        scope.launch {
            val result = runCatching { withContext(io) { block() } }
            mutableState.update { state ->
                result.fold(
                    onSuccess = { (results, message) -> SearchState(results, isLoading = false, message = message) },
                    onFailure = { state.copy(isLoading = false, message = it.message ?: it.toString()) },
                )
            }
        }
    }
}

internal fun folderId(folder: List<SoulseekFile>): String = "$SOULSEEK:${folder[0].user}:${folder[0].folder}"

internal fun folderResult(folder: List<SoulseekFile>): SearchResult =
    SearchResult(
        id = folderId(folder),
        title = folder[0].folder.substringAfterLast('\\').substringAfterLast('/'),
        category = text(R.string.soulseek_folder_details, folder[0].user, folder.size),
        sizeBytes = folder.sumOf(SoulseekFile::size),
        seeds = folder[0].freeSlots,
        leeches = 0,
        source = SOULSEEK,
    )
