package io.github.ilevs.p2pmusic.search

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.libtorrent4j.TorrentInfo

/**
 * Talks to the real rutracker.org with the account from the RUTRACKER_LOGIN and
 * RUTRACKER_PASSWORD environment variables (GitHub secrets in CI). Skipped without them.
 */
class RuTrackerLiveTest {
    @Test
    fun searchesAndDownloadsATorrent() {
        val login = System.getenv("RUTRACKER_LOGIN").orEmpty()
        val password = System.getenv("RUTRACKER_PASSWORD").orEmpty()
        assumeTrue("No RuTracker account in the environment", login.isNotEmpty() && password.isNotEmpty())
        val client = trackerHttpClient(proxy = null, cookieJar = MemoryCookieJar())
        val tracker = RuTracker(client = { client }, credentials = { Credentials(login, password) })

        val results = tracker.search("Pink Floyd FLAC")
        assertTrue("No results", results.isNotEmpty())
        val best = results.maxBy { it.seeds }
        val info = TorrentInfo.bdecode(tracker.torrentFile(best))
        assertTrue(info.isValid && info.numFiles() > 0)
    }
}
