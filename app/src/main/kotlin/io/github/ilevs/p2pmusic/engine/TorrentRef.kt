package io.github.ilevs.p2pmusic.engine

private const val SCHEME = "torrent://"
private val HashPattern = Regex("[0-9a-f]{40}")

/** A file inside a torrent, addressed as `torrent://<infohash>/<file index>` in media URIs. */
data class TorrentRef(
    val hash: String,
    val fileIndex: Int,
)

fun TorrentRef.toUri(): String = "$SCHEME$hash/$fileIndex"

/** Returns null when [uri] is not a valid torrent URI. */
fun parseTorrentUri(uri: String): TorrentRef? {
    val parts = uri.removePrefix(SCHEME).split('/')
    val index = parts.getOrNull(1)?.toIntOrNull()
    val hash = parts[0].lowercase()
    val isValid = uri.startsWith(SCHEME) && parts.size == 2 && HashPattern.matches(hash) && index != null && index >= 0
    return if (isValid) TorrentRef(hash, requireNotNull(index)) else null
}
