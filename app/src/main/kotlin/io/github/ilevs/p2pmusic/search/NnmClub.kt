package io.github.ilevs.p2pmusic.search

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URLEncoder

private const val CHARSET = "windows-1251"

/**
 * nnmclub.to: the search and a topic's magnet link need no account and sit behind no browser check.
 * shortcut: no .torrent files, the site gives them only after a login; add an account when discographies
 * from here (opened by their .torrent) are missed.
 */
class NnmClub(
    private val client: () -> OkHttpClient,
    private val baseUrl: HttpUrl = "https://nnmclub.to/forum/".toHttpUrl(),
) : TorrentSource {
    override val name = "NNM-Club"

    override fun search(query: String): List<SearchResult> =
        parseNnmSearch(get("tracker.php") { addEncodedQueryParameter("nm", URLEncoder.encode(query, CHARSET)) })

    /** From the topic's page: the search page shows no magnets. */
    override fun magnet(result: SearchResult): String =
        Jsoup
            .parse(get("viewtopic.php") { addQueryParameter("t", result.id) })
            .selectFirst("a[href^=magnet:]")
            ?.attr("href")
            ?: throw IOException("NNM-Club: no magnet in topic ${result.id}")

    override fun torrentFile(result: SearchResult): ByteArray =
        throw IOException("NNM-Club отдаёт .torrent только после входа")

    private fun get(
        page: String,
        query: HttpUrl.Builder.() -> Unit,
    ): String {
        val url = baseUrl.newBuilder().addPathSegment(page).apply(query).build()
        return client().newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("NNM-Club: HTTP ${response.code}")
            String(checkNotNull(response.body).bytes(), charset(CHARSET))
        }
    }
}

// A row: forum, title link (viewtopic.php?t=<id>), author, size as <u>bytes</u> text, seeds, leeches.
internal fun parseNnmSearch(html: String): List<SearchResult> =
    Jsoup.parse(html).select("tr.prow1, tr.prow2").mapNotNull { row ->
        row.selectFirst("a.topictitle[href*=viewtopic.php?t=]")?.let { link ->
            SearchResult(
                id = link.attr("href").substringAfter("t=").takeWhile(Char::isDigit),
                title = link.text(),
                category = row.selectFirst("a[href*=tracker.php?f=]")?.text().orEmpty(),
                sizeBytes = row.select("td.gensmall > u").firstNotNullOfOrNull { it.text().toLongOrNull() } ?: 0L,
                seeds = row.selectFirst("td.seedmed")?.text()?.trim()?.toIntOrNull() ?: 0,
                leeches = row.selectFirst("td.leechmed")?.text()?.trim()?.toIntOrNull() ?: 0,
            )
        }
    }
