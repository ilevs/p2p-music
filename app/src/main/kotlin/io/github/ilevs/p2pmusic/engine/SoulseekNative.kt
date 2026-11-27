package io.github.ilevs.p2pmusic.engine

/** What [SoulseekEngine] needs from the network; the native client in the app, a fake in tests. */
interface SoulseekApi {
    /** Null when logged in, else why not. */
    fun login(
        username: String,
        password: String,
        shareDir: String,
        listenPort: Int,
    ): String?

    fun isOnline(): Boolean

    /** One line per file: user, free slots, speed, size, path, tab-separated. */
    fun search(
        query: String,
        timeoutMs: Long,
    ): String

    /** The id of a queued download into [dir], or [FAILED]. */
    fun download(
        username: String,
        filename: String,
        size: Long,
        dir: String,
    ): Long

    /** Bytes of download [id] on disk so far, its size when done, [QUEUED] or [FAILED]. */
    fun status(id: Long): Long

    fun cancel(id: Long)

    companion object {
        const val QUEUED = -1L
        const val FAILED = -2L
    }
}

/** soulseek-rs-lib through JNI, see native/soulseek. Every call blocks. */
object SoulseekNative : SoulseekApi {
    init {
        System.loadLibrary("soulseek")
    }

    external override fun login(
        username: String,
        password: String,
        shareDir: String,
        listenPort: Int,
    ): String?

    external override fun isOnline(): Boolean

    external override fun search(
        query: String,
        timeoutMs: Long,
    ): String

    external override fun download(
        username: String,
        filename: String,
        size: Long,
        dir: String,
    ): Long

    external override fun status(id: Long): Long

    external override fun cancel(id: Long)
}
