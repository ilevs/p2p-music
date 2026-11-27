package io.github.ilevs.p2pmusic.search

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException
import kotlin.math.pow

private const val MUSIC_CATEGORY = "2"
private val SizeUnits = listOf("B", "KB", "MB", "GB", "TB")
private const val KILO = 1024.0

/** rutor.info: no account needed. Searches the music section only. */
class Rutor(
    private val client: () -> OkHttpClient,
    private val baseUrl: HttpUrl = "https://rutor.info/".toHttpUrl(),
    private val downloadUrl: HttpUrl = "https://d.rutor.info/download/".toHttpUrl(),
) : TorrentSource {
    override val name = "Rutor"

    override fun search(query: String): List<SearchResult> {
        // search/<page>/<category>/<mode>/<sort>/<query>, the format the TorAPI project uses.
        val url =
            baseUrl
                .newBuilder()
                .addPathSegments("search/0/$MUSIC_CATEGORY/300/0")
                .addPathSegment(query)
                .build()
        return parseRutorSearch(String(get(url)))
    }

    override fun torrentFile(result: SearchResult): ByteArray {
        val bytes = get(downloadUrl.newBuilder().addPathSegment(result.id).build())
        if (!isTorrentFile(bytes)) throw IOException("Rutor did not return a .torrent for ${result.id}")
        return bytes
    }

    private fun get(url: HttpUrl): ByteArray =
        client().newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Rutor: HTTP ${response.code}")
            checkNotNull(response.body).bytes()
        }
}

// A row: date, title links (download, magnet, topic), an optional comments cell, size, seeds and leeches.
internal fun parseRutorSearch(html: String): List<SearchResult> =
    Jsoup.parse(html).select("tr.gai, tr.tum").mapNotNull { row ->
        row.selectFirst("a[href^=/torrent/]")?.let { link ->
            val cells = row.select("> td")
            SearchResult(
                id = link.attr("href").removePrefix("/torrent/").substringBefore('/'),
                title = link.text(),
                category = "Rutor",
                sizeBytes = parseSize(cells.getOrNull(cells.size - 2)?.text().orEmpty()),
                seeds = row.number("span.green"),
                leeches = row.number("span.red"),
                magnet = row.selectFirst("a[href^=magnet:]")?.attr("href").orEmpty(),
            )
        }
    }

private fun Element.number(css: String): Int {
    val text = selectFirst(css)?.text().orEmpty()
    return text.trim().toIntOrNull() ?: 0
}

/** "802.50 MB" (with a no-break space) to bytes; 0 when it is not a size. */
internal fun parseSize(text: String): Long {
    val parts = text.split(' ', ' ').filter(String::isNotBlank)
    val number = parts.firstOrNull()?.toDoubleOrNull() ?: return 0L
    val power = SizeUnits.indexOf(parts.getOrNull(1)?.uppercase())
    return if (power < 0) 0L else (number * KILO.pow(power)).toLong()
}
