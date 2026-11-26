package io.github.ilevs.p2pmusic.search

import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ProxyTest {
    private val target = MockWebServer().apply { start() }
    private val proxy = Socks5TestServer(username = "user", password = "pass")

    @After
    fun tearDown() {
        target.shutdown()
        proxy.close()
    }

    @Test
    fun goesThroughSocks5WithLoginAndRemoteDns() {
        target.enqueue(MockResponse().setBody("ok"))
        val client = trackerHttpClient(ProxySettings(LOCALHOST, proxy.port, "user", "pass"))

        val body = client.newCall(request()).execute().use { checkNotNull(it.body).string() }

        assertEquals("ok", body)
        // The proxy, not the phone, resolved the tracker's name.
        assertEquals("localhost", proxy.lastHost)
    }

    @Test
    fun failsWithWrongPassword() {
        val client = trackerHttpClient(ProxySettings(LOCALHOST, proxy.port, "user", "wrong"))
        val result = runCatching { client.newCall(request()).execute().close() }
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(0, target.requestCount)
    }

    private fun request() = Request.Builder().url("http://localhost:${target.port}/").build()

    private companion object {
        const val LOCALHOST = "127.0.0.1"
    }
}
