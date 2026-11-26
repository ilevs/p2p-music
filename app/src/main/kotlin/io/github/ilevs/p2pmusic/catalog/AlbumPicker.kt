package io.github.ilevs.p2pmusic.catalog

import io.github.ilevs.p2pmusic.playback.AudioFile
import io.github.ilevs.p2pmusic.search.SearchResult

/**
 * The tracker release most likely to hold [album]: it must name the artist and have seeds;
 * one that also names the album beats a discography, then more seeds win.
 */
internal fun pickTorrent(
    album: Album,
    results: List<SearchResult>,
): SearchResult? {
    val artist = normalize(album.artist)
    val title = normalize(album.title)
    return results
        .filter { it.seeds > 0 && artist in normalize(it.title) }
        .maxWithOrNull(compareBy<SearchResult>({ title in normalize(it.title) }, { it.seeds }))
}

/** The files of [album] inside a torrent that may be a whole discography; all of them if no folder matches. */
internal fun albumFiles(
    album: String,
    files: List<AudioFile>,
): List<AudioFile> {
    val title = normalize(album)
    return files.filter { title in normalize(it.name) }.ifEmpty { files }
}

private val NonWord = Regex("[^\\p{L}\\p{N}]+")

private fun normalize(text: String): String = " " + text.lowercase().replace(NonWord, " ").trim() + " "
