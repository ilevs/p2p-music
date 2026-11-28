package io.github.ilevs.p2pmusic.catalog

import io.github.ilevs.p2pmusic.engine.SoulseekFile
import io.github.ilevs.p2pmusic.engine.isAudioFile
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

/**
 * Soulseek folders that hold [album], one per user, tracks in order: those with a free upload slot first,
 * then the fastest, since the first track should start in seconds.
 */
internal fun soulseekFolders(
    album: Album,
    files: List<SoulseekFile>,
): List<List<SoulseekFile>> {
    val artist = normalize(album.artist)
    val title = normalize(album.title)
<<<<<<< HEAD
    return audioFolders(files.filter { artist in normalize(it.path) && title in normalize(it.folder) })
        .distinctBy { it[0].user }
}

/** The audio files of [files] by user and folder, tracks in order; a free upload slot first, then the fastest. */
internal fun audioFolders(files: List<SoulseekFile>): List<List<SoulseekFile>> =
    files
        .filter { isAudioFile(it.name) }
=======
    return files
        .filter { isAudioFile(it.name) && artist in normalize(it.path) && title in normalize(it.folder) }
>>>>>>> 7772cb574ca3fdeae2ee7b736bc7fe2add74766f
        .groupBy { it.user to it.folder }
        .values
        .map { folder -> folder.sortedBy { it.name } }
        .sortedWith(compareByDescending<List<SoulseekFile>> { it[0].freeSlots > 0 }.thenByDescending { it[0].speed })
<<<<<<< HEAD
=======
        .distinctBy { it[0].user }
}
>>>>>>> 7772cb574ca3fdeae2ee7b736bc7fe2add74766f

/** Enough answers to start racing: that many users with the album and a free slot. */
internal fun enoughToRace(
    album: Album,
    files: List<SoulseekFile>,
    users: Int,
): Boolean = soulseekFolders(album, files).count { it[0].freeSlots > 0 } >= users

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
