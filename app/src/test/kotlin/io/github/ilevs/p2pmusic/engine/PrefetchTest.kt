package io.github.ilevs.p2pmusic.engine

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import kotlin.random.Random

class PrefetchTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val random = Random(seed = 3)

    @Test
    fun removesTheOldestPrefetchedTorrentButNotAnOpenedOne() {
        TorrentEngine(temp.newFolder()).use { engine ->
            val dropped = hash()
            val opened = hash()
            engine.prefetch(magnet(dropped))
            engine.prefetch(magnet(opened))
            assertTrue(engine.awaitUntil(WAIT_MS) { engine.find(opened) != null })
            // Nobody has it, so the open fails, but the user asked for it: it is no longer a guess.
            assertThrows(IOException::class.java) { engine.openMagnet(magnet(opened), timeoutMs = 1) }

            repeat(MORE_THAN_KEPT) { engine.prefetch(magnet(hash())) }
            val last = hash().also { engine.prefetch(magnet(it)) }

            assertTrue(engine.awaitUntil(WAIT_MS) { engine.find(last) != null && engine.find(dropped) == null })
            assertNull(engine.find(dropped))
            assertNotNull(engine.find(opened))
        }
    }

    private fun hash() = random.nextBytes(HASH_BYTES).joinToString("") { "%02x".format(it) }

    private fun magnet(hash: String) = "magnet:?xt=urn:btih:$hash"

    private companion object {
        const val HASH_BYTES = 20
        const val MORE_THAN_KEPT = 12
        const val WAIT_MS = 10_000L
    }
}
