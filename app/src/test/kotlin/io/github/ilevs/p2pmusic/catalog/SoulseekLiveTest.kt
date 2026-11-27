package io.github.ilevs.p2pmusic.catalog

import io.github.ilevs.p2pmusic.engine.SoulseekEngine
import io.github.ilevs.p2pmusic.engine.awaitBytes
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The app's Soulseek path on the real network through the native library: log in, find an album, race the
 * first track of the best users and get its first bytes, as the player would. Prints the timings for the
 * CI log. Needs LIVE_TESTS=true and an account in SOULSEEK_LOGIN / SOULSEEK_PASSWORD.
 */
class SoulseekLiveTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test(timeout = TIMEOUT_MS)
    fun startsAnAlbumTrackFromSoulseek() {
        val login = System.getenv("SOULSEEK_LOGIN").orEmpty()
        assumeTrue(
            "LIVE_TESTS or the Soulseek account is not set",
            System.getenv("LIVE_TESTS") == "true" && login.isNotEmpty(),
        )
        val engine = SoulseekEngine(temp.newFolder("soulseek"))
        val start = System.currentTimeMillis()
        engine.connect(login, System.getenv("SOULSEEK_PASSWORD").orEmpty(), share = false)
        val loggedIn = System.currentTimeMillis()
        val album = Album("1", "The Dark Side of the Moon", "Pink Floyd", "1973")
        val folders = soulseekFolders(album, engine.search("${album.artist} ${album.title}"))
        val searched = System.currentTimeMillis()
        println("METRIC soulseek_login_ms=${loggedIn - start}")
        println("METRIC soulseek_search_ms=${searched - loggedIn}")
        println("Soulseek: ${folders.size} folders with the album")
        assertTrue("No folder with the album", folders.isNotEmpty())
        val first = engine.race(folders.take(RACE_SIZE).map { it.first() }, RACE_MS)
        val raced = System.currentTimeMillis()
        println("METRIC soulseek_first_bytes_ms=${raced - searched}")
        println("Soulseek: first bytes from ${first?.user}")
        checkNotNull(first) { "None of ${folders.take(RACE_SIZE).map { it[0].user }} started sending" }
        val have = engine.awaitBytes(first, BYTES, RACE_MS)
        println("METRIC soulseek_256kb_ms=${System.currentTimeMillis() - searched}")
        println("Soulseek: ${have / KB} KB of ${first.name}")
        engine.cancel(first)
    }

    private companion object {
        const val TIMEOUT_MS = 180_000L
        const val RACE_SIZE = 5
        const val RACE_MS = 60_000L
        const val BYTES = 256 * 1024L
        const val KB = 1024
    }
}
