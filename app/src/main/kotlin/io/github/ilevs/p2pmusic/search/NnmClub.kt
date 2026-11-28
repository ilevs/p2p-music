package io.github.ilevs.p2pmusic.search

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.io.IOException

private const val CHARSET = "windows-1251"

// The forums of the "Трекер: Музыка" group in the search form, read on 2026-11-28.
private val MusicForums =
    """
    313 1291 680 1149 429 681 330 1256 1285 370 1260 371 1261 375 1259 374 1257 373 1258 372
    1160 876 1255 376 326 1352 359 358 1353 1188 1189 328 1370 1180 1181 364 363 1179 879 322
    1350 962 333 1356 965 336 1362 337 338 1351 963 334 1357 961 332 325 1354 1355 1165 1166
    1168 1167 1162 352 1164 1163 1161 353 324 1327 1328 1325 1326 1365 1366 1323 1324 976 346
    1363 977 345 349 1243 347 979 673 671 1224 1225 1367 980 672 1316 1317 1364 981 344 1368
    983 984 982 348 674 323 1369 1187 339 1186 340 1185 341 329 1361 369 368 1218 365 1217 366
    1215 1216 1213 367 331 1358 1157 711 1159 378 1359 1158 379 380 1178 1360 361 360 327 1184
    824 1182 354 877 1183 1190 917
    """.trim().split(Regex("\\s+"))

/**
 * nnmclub.to: the search and a topic's magnet link need no account and sit behind no browser check.
 * Searches the music forums only.
 * shortcut: no .torrent files, the site gives them only after a login; add an account when discographies
 * from here (opened by their .torrent) are missed.
 */
class NnmClub(
    private val client: () -> OkHttpClient,
    private val baseUrl: HttpUrl = "https://nnmclub.to/forum/".toHttpUrl(),
) : TorrentSource {
    override val name = "NNM-Club"

    // The query goes as UTF-8 though the pages come in windows-1251; a windows-1251 query finds other topics.
    override fun search(query: String): List<SearchResult> =
        parseNnmSearch(
            get("tracker.php") {
                MusicForums.forEach { addQueryParameter("f[]", it) }
                addQueryParameter("nm", query)
            },
        )

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
        val url =
            baseUrl
                .newBuilder()
                .addPathSegment(page)
                .apply(query)
                .build()
        return client().newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("NNM-Club: HTTP ${response.code}")
            String(checkNotNull(response.body).bytes(), charset(CHARSET))
        }
    }
}

// A row: forum, title link (viewtopic.php?t=<id>), author, download, size as <u>bytes</u>, seeds, leeches.
internal fun parseNnmSearch(html: String): List<SearchResult> =
    Jsoup.parse(html).select("tr.prow1, tr.prow2").mapNotNull { row ->
        row.selectFirst("a.topictitle[href*=viewtopic.php?t=]")?.let { link ->
            SearchResult(
                id = link.attr("href").substringAfter("t=").takeWhile(Char::isDigit),
                title = link.text(),
                category = row.selectFirst("a[href*=tracker.php?f=]")?.text().orEmpty(),
                sizeBytes = row.count("td.gensmall > u").toLong(),
                seeds = row.count("td.seedmed").toInt(),
                leeches = row.count("td.leechmed").toInt(),
            )
        }
    }

private fun Element.count(css: String): Long {
    val text = selectFirst(css)?.text().orEmpty()
    return text.trim().toLongOrNull() ?: 0L
}
