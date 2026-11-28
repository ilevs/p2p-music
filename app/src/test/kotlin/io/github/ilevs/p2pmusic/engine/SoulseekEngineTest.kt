package io.github.ilevs.p2pmusic.engine

import io.github.ilevs.p2pmusic.engine.SoulseekApi.Companion.FAILED
import io.github.ilevs.p2pmusic.engine.SoulseekApi.Companion.QUEUED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SoulseekEngineTest {
    @Test
    fun parsesSearchLinesAndSkipsBrokenOnes() {
        val lines = "anna\t1\t500000\t9000000\tMusic\\Kino\\1988 - Gruppa krovi\\01 Gruppa krovi.mp3\nbroken line\n"
        val file = parseSearch(lines).single()
        assertEquals(
            SoulseekFile("anna", "Music\\Kino\\1988 - Gruppa krovi\\01 Gruppa krovi.mp3", 9_000_000, 1, 500_000),
            file,
        )
        assertEquals("Music\\Kino\\1988 - Gruppa krovi", file.folder)
        assertEquals("01 Gruppa krovi.mp3", file.name)
    }

    @Test
    fun uriKeepsUserPathAndSize() {
        val file = SoulseekFile("a b/c?", "Music\\Кино\\01 #1 & 2?.flac", 42)
        assertEquals(file, parseSoulseekUri(file.toUri()))
        assertNull(parseSoulseekUri("torrent://abc/1"))
        assertNull(parseSoulseekUri("soulseek://user/path"))
    }

    @Test
    fun strangersNamesStayInsideTheDownloadFolder() {
        val dir = Files.createTempDirectory("soulseek").toFile()
        val engine = SoulseekEngine(dir) { FakeApi() }
        val target = engine.target(SoulseekFile("..", "..\\..\\x.mp3", 1))
        assertTrue(target.canonicalPath, target.canonicalPath.startsWith(dir.canonicalPath + "/"))
    }

    @Test
    fun raceKeepsTheFirstUserToSendAndCancelsTheRest() {
        val api = FakeApi()
        val engine = SoulseekEngine(Files.createTempDirectory("soulseek").toFile()) { api }
        val slow = SoulseekFile("slow", "a\\01.mp3", 100)
        val fast = SoulseekFile("fast", "b\\01.mp3", 100)
        val broken = SoulseekFile("broken", "c\\01.mp3", 100)
        api.states["fast"] = mutableListOf(QUEUED, QUEUED, 10)
        api.states["broken"] = mutableListOf(FAILED)
        assertEquals(fast, engine.race(listOf(slow, fast, broken), 5_000))
        assertEquals(setOf("slow", "broken"), api.cancelled)
        assertEquals(10L, engine.progress(fast))
    }

    @Test
    fun raceGivesUpWhenNobodySends() {
        val api = FakeApi()
        val engine = SoulseekEngine(Files.createTempDirectory("soulseek").toFile()) { api }
        assertNull(engine.race(listOf(SoulseekFile("queued", "a\\01.mp3", 100)), 300))
        assertEquals(setOf("queued"), api.cancelled)
    }

    @Test
    fun aTappedFolderGoesToWhoeverSendsOrToNobody() {
        val api = FakeApi()
        val engine = SoulseekEngine(Files.createTempDirectory("soulseek").toFile()) { api }
        val queued = listOf(SoulseekFile("queued", "a\\01.mp3", 100), SoulseekFile("queued", "a\\02.mp3", 100))
        val sending = listOf(SoulseekFile("sending", "b\\01.mp3", 100), SoulseekFile("sending", "b\\02.mp3", 100))
        api.states["sending"] = mutableListOf(QUEUED, 10)
        assertEquals(sending, engine.raceFolders(listOf(queued, sending), 5_000))
        assertNull(engine.raceFolders(listOf(queued), 300))
    }

    @Test(expected = SoulseekStalledException::class)
    fun aUserWhoSendsNothingStopsTheWaitWithAnError() {
        val engine = SoulseekEngine(Files.createTempDirectory("soulseek").toFile()) { FakeApi() }
        val file = SoulseekFile("queued", "a\\01.mp3", 100)
        engine.download(file)
        engine.awaitBytes(file, 0, 300)
    }

    /** Each user's download reports the next of its states on every poll, then stays at the last one. */
    private class FakeApi : SoulseekApi {
        val states = mutableMapOf<String, MutableList<Long>>()
        val cancelled = mutableSetOf<String>()
        private val users = mutableListOf<String>()

        override fun login(
            username: String,
            password: String,
            shareDir: String,
            listenPort: Int,
        ): String? = null

        override fun isOnline() = true

        override fun disconnect() = Unit

        override fun search(
            query: String,
            timeoutMs: Long,
        ) = ""

        override fun results(query: String) = ""

        override fun download(
            username: String,
            filename: String,
            size: Long,
            dir: String,
        ): Long {
            users += username
            return users.size.toLong()
        }

        override fun status(id: Long): Long {
            val queue = states.getOrPut(users[id.toInt() - 1]) { mutableListOf(QUEUED) }
            return if (queue.size > 1) queue.removeAt(0) else queue[0]
        }

        override fun cancel(id: Long) {
            cancelled += users[id.toInt() - 1]
        }
    }
}
