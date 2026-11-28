package io.github.ilevs.p2pmusic.catalog

import io.github.ilevs.p2pmusic.search.SearchResult
import io.github.ilevs.p2pmusic.search.TorrentSource
import java.util.concurrent.ConcurrentHashMap

private const val MAX_KEPT = 100

/**
 * [TorrentSource.search] once per query: the prefetch of an album and the tap on it share one search, and a tap
 * during the prefetch's search waits for it. A failed search is not kept, the next call runs it again.
 * shortcut: answers are kept until [MAX_KEPT] queries, however old; add an age limit if stale seeds pick dead releases.
 */
internal class SearchCache(
    private val source: TorrentSource,
) {
    private val answers = ConcurrentHashMap<String, Lazy<List<SearchResult>>>()

    fun search(query: String): List<SearchResult> {
        if (answers.size > MAX_KEPT) answers.clear()
        return answers.getOrPut(query) { lazy { source.search(query) } }.value
    }
}
