package io.github.ilevs.p2pmusic.engine

import io.github.ilevs.p2pmusic.R
import io.github.ilevs.p2pmusic.engine.SoulseekApi.Companion.FAILED
import io.github.ilevs.p2pmusic.engine.SoulseekApi.Companion.QUEUED
import io.github.ilevs.p2pmusic.text
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.net.URLEncoder

private const val SCHEME = "soulseek://"
private const val SEARCH_MS = 15_000L
private const val SEARCH_POLL_MS = 250L
private const val POLL_MS = 100L
private const val LISTEN_PORT = 2234
private const val FIELDS = 5
private const val MAX_NAME = 100
private val UnsafeName = Regex("[^\\p{L}\\p{N} ._-]+")

/** A file someone shares on Soulseek, with what their search answer said about them. */
data class SoulseekFile(
    val user: String,
    val path: String,
    val size: Long,
    val freeSlots: Int = 0,
    val speed: Int = 0,
) {
    /** The remote folder; Soulseek paths use backslashes. */
    val folder: String get() = path.take(maxOf(path.lastIndexOf('\\'), path.lastIndexOf('/'), 0))
    val name: String get() = path.substringAfterLast('\\').substringAfterLast('/')
}

/** `soulseek://<user>/<path>?<size>`, URL-encoded, so a queued track can be fetched again after a failure. */
fun SoulseekFile.toUri(): String = "$SCHEME${encode(user)}/${encode(path)}?$size"

fun parseSoulseekUri(uri: String): SoulseekFile? {
    if (!uri.startsWith(SCHEME)) return null
    val user = uri.removePrefix(SCHEME).substringBefore('/')
    val path = uri.substringAfter("$SCHEME$user/").substringBefore('?')
    val size = uri.substringAfterLast('?').toLongOrNull()
    return if (size == null ||
        user.isEmpty() ||
        path.isEmpty()
    ) {
        null
    } else {
        SoulseekFile(decode(user), decode(path), size)
    }
}

private fun encode(text: String) = URLEncoder.encode(text, Charsets.UTF_8.name())

private fun decode(text: String) = URLDecoder.decode(text, Charsets.UTF_8.name())

// Field order of a search line, see native/soulseek.
private const val USER = 0
private const val SLOTS = 1
private const val SPEED = 2
private const val SIZE = 3
private const val PATH = 4

internal fun parseSearch(lines: String): List<SoulseekFile> =
    lines
        .lineSequence()
        .map { it.split('\t', limit = FIELDS) }
        .filter { it.size == FIELDS }
        .mapNotNull { f ->
            f[SIZE].toLongOrNull()?.let {
                SoulseekFile(f[USER], f[PATH], it, f[SLOTS].toIntOrNull() ?: 0, f[SPEED].toIntOrNull() ?: 0)
            }
        }.toList()

/**
 * Soulseek downloads for the player: each file comes whole from one user, in order, into a `.part` file
 * that becomes the file when done. Blocking calls: use them off the main thread.
 */
class SoulseekEngine(
    val dir: File,
    private val api: () -> SoulseekApi = { SoulseekNative },
) {
    private val downloads = mutableMapOf<SoulseekFile, Long>()

    /** Logs in when the session is gone, e.g. after the phone changed networks. */
    @Synchronized
    fun connect(
        login: String,
        password: String,
        share: Boolean,
    ) {
        if (api().isOnline()) return
        // Sharing what we downloaded keeps us off the ban lists of users who refuse leechers.
        val error = api().login(login, password, if (share) dir.path else "", LISTEN_PORT)
        if (error != null) throw IOException(loginError(error))
        downloads.clear()
    }

    /** Logs out and stops the client's threads. */
    @Synchronized
    fun disconnect() {
        api().disconnect()
        downloads.clear()
    }

    /**
     * Searches until the answers so far are [enough] or [SEARCH_MS] pass: users behind NAT answer through the
     * server, the first of them about five seconds in, so a fixed short window finds nothing.
     */
    fun search(
        query: String,
        enough: (List<SoulseekFile>) -> Boolean,
    ): List<SoulseekFile> {
        api().search(query, 0)
        val deadline = System.currentTimeMillis() + SEARCH_MS
        var files = emptyList<SoulseekFile>()
        while (System.currentTimeMillis() < deadline && !enough(files)) {
            Thread.sleep(SEARCH_POLL_MS)
            files = parseSearch(api().results(query))
        }
        return files
    }

    /** Starts [file] unless it is already on its way or on disk. Returns its download id, or 0 if done before. */
    @Synchronized
    fun download(file: SoulseekFile): Long {
        val running = downloads[file]?.takeIf { api().status(it) != FAILED }
        return when {
            running != null -> {
                running
            }

            target(file).length() == file.size -> {
                0L
            }

            else -> {
                api().download(file.user, file.path, file.size, folderOf(file).path).also {
                    if (it == FAILED) throw IOException(text(R.string.soulseek_download_refused, file.name))
                    downloads[file] = it
                }
            }
        }
    }

    /** Bytes of [file] on disk, [QUEUED] while waiting for the user's slot, [FAILED] when it failed. */
    @Synchronized
    fun progress(file: SoulseekFile): Long {
        if (target(file).length() == file.size) return file.size
        return downloads[file]?.let { api().status(it) } ?: FAILED
    }

    @Synchronized
    fun cancel(file: SoulseekFile) {
        downloads.remove(file)?.let { api().cancel(it) }
    }

    /**
     * Asks every one of [candidates] for its file at once and keeps the first that starts sending,
     * cancelling the rest: a free slot on paper still often means a queue. Null if none starts in [waitMs].
     */
    fun race(
        candidates: List<SoulseekFile>,
        waitMs: Long,
    ): SoulseekFile? {
        val started = candidates.filter { runCatching { download(it) }.isSuccess }
        val deadline = System.currentTimeMillis() + waitMs
        var winner: SoulseekFile? = null
        while (winner == null && System.currentTimeMillis() < deadline && started.any { progress(it) != FAILED }) {
            winner = started.firstOrNull { progress(it) > 0 }
            if (winner == null) Thread.sleep(POLL_MS)
        }
        started.filter { it != winner }.forEach(::cancel)
        return winner
    }

    /** The first of [folders] whose user starts sending its first track within [waitMs], or null. */
    fun raceFolders(
        folders: List<List<SoulseekFile>>,
        waitMs: Long,
    ): List<SoulseekFile>? = race(folders.map { it[0] }, waitMs)?.let { winner -> folders.first { it[0] == winner } }

    /** Where [file] is, or will be when done; while downloading it is this name plus `.part`. */
    fun target(file: SoulseekFile): File = File(folderOf(file), file.name)

    // One folder per user and remote folder, so two albums' "01.mp3" never meet.
    private fun folderOf(file: SoulseekFile): File = File(File(dir, safeName(file.user)), safeName(file.folder))
}

// The server's reasons for a refused login, in the user's words; any other shows as the server put it.
private val LoginReasons =
    mapOf(
        "INVALIDPASS" to R.string.soulseek_invalid_password,
        "INVALIDUSERNAME" to R.string.soulseek_invalid_username,
        "SVRFULL" to R.string.soulseek_server_full,
    )

internal fun loginError(error: String): String =
    "Soulseek: " + (
        LoginReasons.entries
            .firstOrNull { it.key in error }
            ?.value
            ?.let { text(it) } ?: error
    )

// Names come from strangers: no separators, no "..", and short enough for the file system.
internal fun safeName(text: String): String =
    text
        .replace(UnsafeName, "_")
        .takeLast(MAX_NAME)
        .trimStart('.')
        .ifEmpty { "_" }

/** Waits until [file] has more than [position] bytes on disk; returns how many it has. */
internal fun SoulseekEngine.awaitBytes(
    file: SoulseekFile,
    position: Long,
    timeoutMs: Long,
): Long {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        val have = progress(file)
        when {
            have > position || have == file.size -> {
                return have
            }

            have == FAILED -> {
                throw IOException(text(R.string.soulseek_download_broke, file.name))
            }

            System.currentTimeMillis() > deadline -> {
                val id = if (have == QUEUED) R.string.soulseek_user_queued else R.string.soulseek_user_stalled
                throw SoulseekStalledException(text(id, file.user, file.name))
            }
        }
        Thread.sleep(POLL_MS)
    }
}

/** A user sent nothing in time; asking the same user again will not help. */
class SoulseekStalledException(
    message: String,
) : IOException(message)
