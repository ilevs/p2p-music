package io.github.ilevs.p2pmusic.search

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.Jsoup
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.Charset

private val Cp1251: Charset = Charset.forName("windows-1251")
private const val LOGGED_IN_MARKER = "logged-in-username"
private const val CAPTCHA_MARKER = "cap_sid"
private const val HTTP_FORBIDDEN = 403

// The proxy works and the site is up, so "check the proxy" would send the user the wrong way.
private const val CLOUDFLARE_CHECK =
    "RuTracker: сайт закрыт проверкой Cloudflare «Я не робот» для адреса, с которого идёт запрос. " +
        "Приложение её не проходит: нужен другой прокси или VPN"

data class Credentials(
    val login: String,
    val password: String,
)

class TrackerLoginException(
    message: String,
) : IOException(message)

/**
 * rutracker.org: search and .torrent download need an account. Pages are in windows-1251.
 * [client] must keep cookies (see [MemoryCookieJar]); it is asked for on every call so that
 * proxy changes in settings apply right away.
 */
class RuTracker(
    private val client: () -> OkHttpClient,
    private val credentials: () -> Credentials?,
    private val baseUrl: HttpUrl = "https://rutracker.org/forum/".toHttpUrl(),
) : TorrentSource {
    override val name = "RuTracker"

    override fun search(query: String): List<SearchResult> {
        val url =
            baseUrl
                .newBuilder()
                .addPathSegment("tracker.php")
                .addEncodedQueryParameter("nm", URLEncoder.encode(query, Cp1251.name()))
                .build()
        var page = get(url)
        if (LOGGED_IN_MARKER !in page) {
            login()
            page = get(url)
        }
        return parseRuTrackerSearch(page)
    }

    override fun torrentFile(result: SearchResult): ByteArray {
        val url =
            baseUrl
                .newBuilder()
                .addPathSegment("dl.php")
                .addQueryParameter("t", result.id)
                .build()
        var bytes = getBytes(url)
        if (!isTorrentFile(bytes)) {
            login()
            bytes = getBytes(url)
        }
        if (!isTorrentFile(bytes)) throw IOException("RuTracker did not return a .torrent for ${result.id}")
        return bytes
    }

    /** Logs in with the saved account; throws [TrackerLoginException] with a message for the user. */
    fun login() {
        val account = credentials() ?: throw TrackerLoginException("Укажите логин и пароль RuTracker в настройках")
        val form =
            listOf("login_username" to account.login, "login_password" to account.password, "login" to "Вход")
                .joinToString("&") { (key, value) -> "$key=${URLEncoder.encode(value, Cp1251.name())}" }
        val request =
            Request
                .Builder()
                .url(baseUrl.newBuilder().addPathSegment("login.php").build())
                .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                .build()
        val (code, page) =
            client().newCall(request).execute().use { it.code to String(checkNotNull(it.body).bytes(), Cp1251) }
        val html = Jsoup.parse(page)
        val siteError = html.selectFirst("h4.warnColor1")?.text()
        // The last branch: a blocked-site stub from the provider or the proxy, or an anti-bot page.
        val error =
            when {
                LOGGED_IN_MARKER in page -> null
                CAPTCHA_MARKER in page -> "RuTracker просит капчу: войдите один раз через браузер"
                !siteError.isNullOrBlank() -> "RuTracker: $siteError"
                "login_username" in page -> "RuTracker не принял логин или пароль"
                isCloudflareCheck(code, page) -> CLOUDFLARE_CHECK
                else -> "Вместо RuTracker пришла страница «${html.title()}» (HTTP $code). Проверьте прокси"
            }
        if (error != null) throw TrackerLoginException(error)
    }

    private fun get(url: HttpUrl): String = String(getBytes(url), Cp1251)

    private fun getBytes(url: HttpUrl): ByteArray =
        client().newCall(Request.Builder().url(url).build()).execute().use { response ->
            val bytes = checkNotNull(response.body).bytes()
            if (isCloudflareCheck(response.code, String(bytes, Cp1251))) throw IOException(CLOUDFLARE_CHECK)
            if (!response.isSuccessful) throw IOException("RuTracker: HTTP ${response.code}")
            bytes
        }
}

// Cloudflare's challenge page, which only a browser passes.
internal fun isCloudflareCheck(
    code: Int,
    page: String,
): Boolean = code == HTTP_FORBIDDEN && ("Just a moment" in page || "challenge-platform" in page)

// A bencoded .torrent is a dictionary that starts with its "announce" or "info" key.
internal fun isTorrentFile(bytes: ByteArray): Boolean = bytes.isNotEmpty() && bytes[0] == 'd'.code.toByte()

internal fun parseRuTrackerSearch(html: String): List<SearchResult> =
    Jsoup.parse(html).select("table#tor-tbl tr.hl-tr").mapNotNull { row ->
        row.selectFirst("a.tLink")?.let { link ->
            SearchResult(
                id = link.attr("data-topic_id").ifEmpty { link.attr("href").substringAfter("t=") },
                title = link.text(),
                category = row.selectFirst("div.f-name a")?.text().orEmpty(),
                sizeBytes = row.selectFirst("td.tor-size")?.attr("data-ts_text")?.toLongOrNull() ?: 0L,
                seeds = row.selectFirst("b.seedmed")?.text()?.toIntOrNull() ?: 0,
                leeches = row.selectFirst("td.leechmed")?.text()?.toIntOrNull() ?: 0,
            )
        }
    }
