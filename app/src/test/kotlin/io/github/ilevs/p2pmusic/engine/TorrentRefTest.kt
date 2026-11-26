package io.github.ilevs.p2pmusic.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TorrentRefTest {
    private val hash = "0123456789abcdef0123456789abcdef01234567"

    @Test
    fun roundTrips() {
        val ref = TorrentRef(hash, 3)
        assertEquals(ref, parseTorrentUri(ref.toUri()))
    }

    @Test
    fun rejectsMalformedUris() {
        assertNull(parseTorrentUri("http://$hash/1"))
        assertNull(parseTorrentUri("torrent://$hash"))
        assertNull(parseTorrentUri("torrent://$hash/-1"))
        assertNull(parseTorrentUri("torrent://nothex/1"))
        assertNull(parseTorrentUri("torrent://$hash/1/2"))
    }
}
