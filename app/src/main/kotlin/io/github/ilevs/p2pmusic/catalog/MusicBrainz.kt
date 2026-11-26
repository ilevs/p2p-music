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
) {
    fun searchAlbums(query: String): List<Album> {
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
        val json =
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("MusicBrainz: HTTP ${response.code}")
                checkNotNull(response.body).string()
            }
        return parseReleaseGroups(json)
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
