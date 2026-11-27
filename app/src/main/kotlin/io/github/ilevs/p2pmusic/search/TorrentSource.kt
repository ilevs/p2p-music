package io.github.ilevs.p2pmusic.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking

data class SearchResult(
    val id: String,
    val title: String,
    val category: String,
    val sizeBytes: Long,
    val seeds: Int,
    val leeches: Int,
    /** Name of the [TorrentSource] that found it; set by [AllSources]. */
    val source: String = "",
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
 * Fails only when nothing was found and some source failed, with that source's error.
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
        if (results.isEmpty()) outcomes.firstNotNullOfOrNull { it.exceptionOrNull() }?.let { throw it }
        return results
    }

    override fun torrentFile(result: SearchResult): ByteArray =
        sources.first { it.name == result.source }.torrentFile(result)
}
