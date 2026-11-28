package io.github.ilevs.p2pmusic.catalog

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

// MusicBrainz asks every client to name itself and a contact.
private const val USER_AGENT = "P2PMusic/0.1 ( https://github.com/ilevs/p2p-music )"
private const val PAGE_SIZE = "25"
private const val YEAR_LENGTH = 4
private const val ATTEMPTS = 5

/** An album (MusicBrainz release group). */
data class Album(
    val id: String,
    val title: String,
    val artist: String,
    val year: String,
)

/** Front cover from the Cover Art Archive, 250 px. */
val Album.coverUrl: String get() = "https://coverartarchive.org/release-group/$id/front-250"

/** Album search on musicbrainz.org: free, no account. Blocking. */
class MusicBrainz(
    private val client: OkHttpClient,
    private val baseUrl: HttpUrl = "https://musicbrainz.org/ws/2/".toHttpUrl(),
    private val retryDelayMs: Long = 1_500,
) {
    fun searchAlbums(query: String): List<Album> =
        try {
            fetchAlbums(query)
        } catch (e: IOException) {
            // Network errors such as "timeout" say nothing about where they came from.
            if (e.message.orEmpty().startsWith("MusicBrainz")) throw e
            throw IOException("MusicBrainz: ${e.message ?: e}", e)
        }

    private fun fetchAlbums(query: String): List<Album> {
        val url =
            baseUrl
                .newBuilder()
                .addPathSegment("release-group")
                .addQueryParameter("query", query)
                .addQueryParameter("limit", PAGE_SIZE)
                .addQueryParameter("fmt", "json")
                .build()
        val request =
            Request
                .Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()
        // MusicBrainz answers 503 to more than one request a second from an address, and at times
        // does not answer at all: the user saw a bare "timeout". Wait and retry.
        var failure = IOException("MusicBrainz: no attempts")
        for (attempt in 1..ATTEMPTS) {
            // Each wait is longer: three tries 1.5 s apart still ended in "HTTP 503" on a CI emulator.
            Thread.sleep(retryDelayMs * (attempt - 1))
            try {
                return parseReleaseGroups(get(request))
            } catch (e: IOException) {
                failure = e
            }
        }
        throw failure
    }

    private fun get(request: Request): String =
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("MusicBrainz: HTTP ${response.code}")
            checkNotNull(response.body).string()
        }
}

internal fun parseReleaseGroups(json: String): List<Album> {
    val groups = JSONObject(json).getJSONArray("release-groups")
    return (0 until groups.length()).map { i ->
        val group = groups.getJSONObject(i)
        val credits = group.optJSONArray("artist-credit") ?: JSONArray()
        val artist =
            (0 until credits.length()).joinToString("") { c ->
                val credit = credits.getJSONObject(c)
                credit.optString("name") + credit.optString("joinphrase")
            }
        Album(
            id = group.getString("id"),
            title = group.optString("title"),
            artist = artist,
            year = group.optString("first-release-date").take(YEAR_LENGTH),
        )
    }
}
