package io.github.ilevs.p2pmusic.search

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.util.concurrent.TimeUnit

private const val TIMEOUT_SECONDS = 30L
private const val BROWSER_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

/** SOCKS5 proxy for tracker requests, e.g. to reach trackers blocked in the country. */
data class ProxySettings(
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = "",
)

/**
 * HTTP client for trackers, optionally through a SOCKS5 proxy. The proxy resolves host names,
 * so blocked domains work even when local DNS lies about them.
 */
fun trackerHttpClient(
    proxy: ProxySettings?,
    cookieJar: CookieJar = CookieJar.NO_COOKIES,
): OkHttpClient {
    val builder =
        OkHttpClient
            .Builder()
            .cookieJar(cookieJar)
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            // Sites behind Cloudflare turn away a client that names itself as a library.
            .addInterceptor { chain ->
                val request =
                    chain
                        .request()
                        .newBuilder()
                        .header("User-Agent", BROWSER_USER_AGENT)
                        .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                        .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8")
                        .build()
                chain.proceed(request)
            }
    if (proxy != null) {
        builder.proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress.createUnresolved(proxy.host, proxy.port)))
        // shortcut: the platform SOCKS client takes credentials only from the global Authenticator;
        // fine while the app has one proxy, revisit if it ever needs several.
        Authenticator.setDefault(SocksAuthenticator(proxy))
    }
    return builder.build()
}

private class SocksAuthenticator(
    private val proxy: ProxySettings,
) : Authenticator() {
    override fun getPasswordAuthentication(): PasswordAuthentication? =
        if (requestingHost == proxy.host && requestingPort == proxy.port && proxy.username.isNotEmpty()) {
            PasswordAuthentication(proxy.username, proxy.password.toCharArray())
        } else {
            null
        }
}

/** Keeps cookies in memory for the life of the process, per host. */
class MemoryCookieJar : CookieJar {
    private val cookies = mutableMapOf<String, List<Cookie>>()

    @Synchronized
    override fun saveFromResponse(
        url: HttpUrl,
        cookies: List<Cookie>,
    ) {
        val names = cookies.map { it.name }.toSet()
        this.cookies[url.host] = this.cookies[url.host].orEmpty().filterNot { it.name in names } + cookies
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        cookies[url.host].orEmpty().filter { it.expiresAt > System.currentTimeMillis() }
}
