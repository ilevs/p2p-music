package io.github.ilevs.p2pmusic.search

import io.github.ilevs.p2pmusic.engine.TorrentEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.libtorrent4j.TorrentHandle
import java.io.IOException

private const val MAGNET_FIRST_MS = 20_000L

data class SearchResult(
    val id: String,
    val title: String,
    val category: String,
    val sizeBytes: Long,
    val seeds: Int,
    val leeches: Int,
    /** Name of the [TorrentSource] that found it; set by [AllSources]. */
    val source: String = "",
    /** A magnet link from the search page, when the tracker shows one. */
    val magnet: String = "",
)

/** A built-in torrent tracker. Blocking calls: use them off the main thread. */
interface TorrentSource {
    val name: String

    fun search(query: String): List<SearchResult>

    /** The .torrent file of a search result. */
    fun torrentFile(result: SearchResult): ByteArray
}

/**
 * Searches every source at once, so one blocked or broken site does not stop the search.
 * Fails only when nothing was found and some source failed, saying what each source answered.
 */
class AllSources(
    private val sources: List<TorrentSource>,
) : TorrentSource {
    override val name = sources.joinToString { it.name }

    override fun search(query: String): List<SearchResult> {
        val outcomes =
            runBlocking(Dispatchers.IO) {
                sources
                    .map { source ->
                        async { runCatching { source.search(query).map { it.copy(source = source.name) } } }
                    }.awaitAll()
            }
        val results = outcomes.flatMap { it.getOrDefault(emptyList()) }
        if (results.isEmpty() && outcomes.any { it.isFailure }) {
            val answers = sources.zip(outcomes).map { (source, outcome) -> answer(source, outcome) }
            throw IOException(answers.joinToString("; "))
        }
        return results
    }

    private fun answer(
        source: TorrentSource,
        outcome: Result<List<SearchResult>>,
    ): String {
        val message = outcome.exceptionOrNull()?.let { it.message ?: it.toString() } ?: "ничего не найдено"
        return named(source, message)
    }

    private fun named(
        source: TorrentSource,
        message: String,
    ) = if (message.startsWith(source.name)) message else "${source.name}: $message"

    override fun torrentFile(result: SearchResult): ByteArray {
        val source = sources.first { it.name == result.source }
        return try {
            source.torrentFile(result)
        } catch (e: IOException) {
            throw IOException(named(source, ".torrent не скачался: ${e.message ?: e}"), e)
        }
    }
}

/**
 * Opens [result] by its magnet, which needs only peers; by the .torrent when the tracker shows no magnet
 * or the magnet finds no metadata in time. Blocking.
 */
fun TorrentSource.open(
    result: SearchResult,
    engine: TorrentEngine,
): TorrentHandle =
    result.magnet
        .takeIf { it.isNotEmpty() }
        ?.let { runCatching { engine.openMagnet(it, MAGNET_FIRST_MS) }.getOrNull() }
        ?: engine.openTorrent(torrentFile(result))
