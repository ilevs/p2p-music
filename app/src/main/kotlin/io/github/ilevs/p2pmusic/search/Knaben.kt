package io.github.ilevs.p2pmusic.search

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

private const val AUDIO_CATEGORY = 1_000_000
private const val MAX_RESULTS = 100

/**
 * knaben.org: a free search API over the indexes it keeps of RuTracker, The Pirate Bay, 1337x and others.
 * No account and no Cloudflare check, so it answers while rutracker.org itself does not. Audio only.
 */
class Knaben(
    private val client: () -> OkHttpClient,
    private val apiUrl: HttpUrl = "https://api.knaben.org/v1".toHttpUrl(),
) : TorrentSource {
    override val name = "Knaben"

    override fun search(query: String): List<SearchResult> {
        // "100%": every word must match; without it the API answers nothing to Cyrillic queries.
        val body =
            JSONObject()
                .put("search_type", "100%")
                .put("search_field", "title")
                .put("query", query)
                .put("categories", JSONArray().put(AUDIO_CATEGORY))
                .put("order_by", "seeders")
                .put("order_direction", "desc")
                .put("size", MAX_RESULTS)
                .put("hide_unsafe", false)
                .put("hide_xxx", true)
        val request =
            Request
                .Builder()
                .url(apiUrl)
                .post(body.toString().toRequestBody(JSON))
                .build()
        return parseKnabenSearch(String(get(request)))
    }

    /** By the link Knaben gives, which fetches the .torrent from the tracker for us; [SearchResult.id] holds it. */
    override fun torrentFile(result: SearchResult): ByteArray {
        val bytes = get(Request.Builder().url(result.id).build())
        if (!isTorrentFile(bytes)) throw IOException("Knaben did not return a .torrent for ${result.title}")
        return bytes
    }

    private fun get(request: Request): ByteArray =
        client().newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Knaben: HTTP ${response.code}")
            checkNotNull(response.body).bytes()
        }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

// A hit without a link cannot be opened; one without a hash still can, by its .torrent.
internal fun parseKnabenSearch(json: String): List<SearchResult> {
    val hits = JSONObject(json).optJSONArray("hits") ?: return emptyList()
    return (0 until hits.length()).map(hits::getJSONObject).filter { it.optString("link").isNotEmpty() }.map { hit ->
        val hash = hit.optString("hash").takeUnless { hit.isNull("hash") }.orEmpty()
        SearchResult(
            id = hit.getString("link"),
            title = hit.optString("title"),
            category = hit.optString("tracker"),
            sizeBytes = hit.optLong("bytes"),
            seeds = hit.optInt("seeders"),
            leeches = hit.optInt("peers"),
            magnet = if (hash.isEmpty()) "" else "magnet:?xt=urn:btih:$hash",
        )
    }
}
