package io.github.ilevs.p2pmusic.catalog

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/** Album search on deezer.com: free, no key or account. Fresh releases appear there on the day they come out. */
class Deezer(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl = "https://api.deezer.com/".toHttpUrl(),
) {
    fun searchAlbums(query: String): List<Album> {
        val url =
            baseUrl
                .newBuilder()
                .addPathSegments("search/album")
                .addQueryParameter("q", query)
                .addQueryParameter("limit", PAGE_SIZE)
                .build()
        return parseDeezerAlbums(getText(client, url, "Deezer"))
    }
}

/** Album search in the iTunes Store (Apple Music's catalog): free, no key or account. */
class ITunes(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl = "https://itunes.apple.com/".toHttpUrl(),
) {
    fun searchAlbums(query: String): List<Album> {
        val url =
            baseUrl
                .newBuilder()
                .addPathSegment("search")
                .addQueryParameter("term", query)
                // The default US store listed 6 of 17 albums of the coverage set, missing most Russian ones.
                .addQueryParameter("country", "RU")
                .addQueryParameter("media", "music")
                .addQueryParameter("entity", "album")
                .addQueryParameter("limit", PAGE_SIZE)
                .build()
        return parseITunesAlbums(getText(client, url, "iTunes"))
    }
}

/**
 * The albums of all catalogs in one list: the first of each, then the second of each, and so on, so one catalog's
 * tributes and compilations do not push another's album off the screen. An album that several have is shown once,
 * where the first of them stands.
 */
internal fun mergeAlbums(catalogs: List<List<Album>>): List<Album> =
    (0 until (catalogs.maxOfOrNull { it.size } ?: 0))
        .flatMap { i -> catalogs.mapNotNull { it.getOrNull(i) } }
        .groupBy { normalize(it.artist) + "|" + normalize(it.title) }
        // Deezer gives no year and iTunes may give a reissue's; the user tells albums apart by the first release's.
        .values
        .map { same ->
            val years = same.map { it.year }.filter(String::isNotEmpty)
            same[0].copy(year = years.minOrNull().orEmpty())
        }

internal fun parseDeezerAlbums(json: String): List<Album> {
    val root = JSONObject(json)
    // Deezer answers its errors, such as too many requests, with HTTP 200.
    root.optJSONObject("error")?.let { throw IOException("Deezer: ${it.optString("message")}") }
    val data = root.getJSONArray("data")
    return (0 until data.length()).map { i ->
        val album = data.getJSONObject(i)
        Album(
            id = "deezer:${album.getLong("id")}",
            title = storeTitle(album.optString("title")),
            artist = album.optJSONObject("artist")?.optString("name").orEmpty(),
            year = "",
            cover = album.optString("cover_medium"),
        )
    }
}

internal fun parseITunesAlbums(json: String): List<Album> {
    val results = JSONObject(json).getJSONArray("results")
    return (0 until results.length()).map { i ->
        val album = results.getJSONObject(i)
        Album(
            id = "itunes:${album.getLong("collectionId")}",
            title = storeTitle(album.optString("collectionName")),
            artist = album.optString("artistName"),
            year = album.optString("releaseDate").take(YEAR_LENGTH),
            cover = album.optString("artworkUrl100").replace("100x100", "250x250"),
        )
    }
}

// shortcut: also drops a trailing bracket that is part of the name itself; fine until such an album fails to play.
private val Edition = Regex("""(\s*(\([^()]*\)|\[[^\[\]]*]|\s-\s(EP|Single)))+$""")

/**
 * The album's name without the store's "(Remastered 2011)", "[Deluxe Edition]", " - EP" or " - Single":
 * tracker releases rarely carry them, and the release search looks for the name as it is.
 */
internal fun storeTitle(title: String): String = title.replace(Edition, "").ifEmpty { title }

internal fun getText(
    client: OkHttpClient,
    url: HttpUrl,
    name: String,
): String =
    try {
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("$name: HTTP ${response.code}")
            checkNotNull(response.body).string()
        }
    } catch (e: IOException) {
        // Network errors such as "timeout" say nothing about where they came from.
        if (e.message.orEmpty().startsWith(name)) throw e
        throw IOException("$name: ${e.message ?: e}", e)
    }
