package io.github.ilevs.p2pmusic.catalog

import io.github.ilevs.p2pmusic.playback.AudioFile
import io.github.ilevs.p2pmusic.search.SearchResult

/**
 * The tracker releases that may hold [album], the likeliest first: each must name the artist and have seeds;
 * one that names the album comes before discographies and collections, those before the artist's other
 * releases (most of them other albums), and more seeds win inside each group.
 */
internal fun rankTorrents(
    album: Album,
    results: List<SearchResult>,
): List<SearchResult> {
    val artist = normalize(album.artist)
    return results
        .filter { it.seeds > 0 && artist in normalize(it.title) }
        .sortedWith(
            compareByDescending<SearchResult> { namesAlbum(album, it) }
                .thenByDescending { Collection.containsMatchIn(it.title) }
                .thenByDescending { it.seeds },
        )
}

/** Whether the release's title names [album] itself. */
internal fun namesAlbum(
    album: Album,
    result: SearchResult,
): Boolean = normalize(album.title) in normalize(result.title)

/**
 * The files of [album] inside the torrent named [torrent], which may be a whole discography. When no folder
 * names the album, all files if the torrent itself does, else none: another album must not play in its place.
 */
internal fun albumFiles(
    album: String,
    torrent: String,
    files: List<AudioFile>,
): List<AudioFile> {
    val title = normalize(album)
    return files.filter { title in normalize(it.name) }.ifEmpty {
        if (title in normalize(torrent)) files else emptyList()
    }
}

private val NonWord = Regex("[^\\p{L}\\p{N}]+")

// "Дискография", "Discography", "Сборник", "Collection", "Антология", "Все альбомы", or a span of years.
private val Collection =
    Regex(
        "дискограф|discograph|сборник|collection|антолог|antholog|альбомы|albums|\\d{4}\\s*[-–]\\s*\\d{4}",
        RegexOption.IGNORE_CASE,
    )

// Trackers and the catalog spell "ё" both ways.
private fun normalize(text: String): String {
    val words = text.lowercase().replace('ё', 'е').replace(NonWord, " ")
    return " " + words.trim() + " "
}
