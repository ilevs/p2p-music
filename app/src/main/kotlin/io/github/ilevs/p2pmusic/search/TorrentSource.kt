package io.github.ilevs.p2pmusic.search

data class SearchResult(
    val id: String,
    val title: String,
    val category: String,
    val sizeBytes: Long,
    val seeds: Int,
    val leeches: Int,
)

/** A built-in torrent tracker. Blocking calls: use them off the main thread. */
interface TorrentSource {
    val name: String

    fun search(query: String): List<SearchResult>

    /** The .torrent file of a search result. */
    fun torrentFile(result: SearchResult): ByteArray
}
