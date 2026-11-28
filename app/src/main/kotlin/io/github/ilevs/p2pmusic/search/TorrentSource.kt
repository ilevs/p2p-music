package io.github.ilevs.p2pmusic.search

import io.github.ilevs.p2pmusic.engine.TorrentEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.libtorrent4j.TorrentHandle
import java.io.IOException
import java.util.concurrent.CompletableFuture

private const val MAGNET_FIRST_MS = 20_000L

data class SearchResult(
    val id: String,
    val title: String,
    val category: String,
    val sizeBytes: Long,
    val seeds: Int,
    val leeches: Int,
    /** Name of the [TorrentSource] that found it; set by [AllSources]. */
    val source: String = "",
    /** A magnet link from the search page, when the tracker shows one. */
    val magnet: String = "",
)

/** A built-in torrent tracker. Blocking calls: use them off the main thread. */
interface TorrentSource {
    val name: String

    fun search(query: String): List<SearchResult>

    /** The .torrent file of a search result. */
    fun torrentFile(result: SearchResult): ByteArray

    /** The magnet link of a search result, "" when there is none; a tracker may need a request for it. */
    fun magnet(result: SearchResult): String = result.magnet
}

/**
 * Searches every source at once, so one blocked or broken site does not stop the search. The [fallback] sources
 * only when those found nothing: what they find may open slower (2026-11-28 CI: with Knaben searched always,
 * album tap to sound went from 6-10 s to 24-39 s). Fails only when nothing was found and some source failed,
 * saying what each source answered.
 */
class AllSources(
    private val sources: List<TorrentSource>,
    private val fallback: List<TorrentSource> = emptyList(),
) : TorrentSource {
    override val name = (sources + fallback).joinToString { it.name }

    override fun search(query: String): List<SearchResult> {
        val outcomes = searchAll(sources, query)
        val all =
            if (outcomes.any { it.getOrDefault(emptyList()).isNotEmpty() }) {
                outcomes
            } else {
                outcomes + searchAll(fallback, query)
            }
        val results = all.flatMap { it.getOrDefault(emptyList()) }
        if (results.isEmpty() && all.any { it.isFailure }) {
            val answers = (sources + fallback).zip(all).map { (source, outcome) -> answer(source, outcome) }
            throw IOException(answers.joinToString("; "))
        }
        return results
    }

    private fun searchAll(
        group: List<TorrentSource>,
        query: String,
    ): List<Result<List<SearchResult>>> =
        runBlocking(Dispatchers.IO) {
            group
                .map { source ->
                    async { runCatching { source.search(query).map { it.copy(source = source.name) } } }
                }.awaitAll()
        }

    private fun answer(
        source: TorrentSource,
        outcome: Result<List<SearchResult>>,
    ): String {
        val message = outcome.exceptionOrNull()?.let { it.message ?: it.toString() } ?: "ничего не найдено"
        return named(source, message)
    }

    private fun named(
        source: TorrentSource,
        message: String,
    ) = if (message.startsWith(source.name)) message else "${source.name}: $message"

    override fun magnet(result: SearchResult): String =
        (sources + fallback).firstOrNull { it.name == result.source }?.magnet(result) ?: result.magnet

    override fun torrentFile(result: SearchResult): ByteArray {
        val source = (sources + fallback).first { it.name == result.source }
        return try {
            source.torrentFile(result)
        } catch (e: IOException) {
            throw IOException(named(source, ".torrent не скачался: ${e.message ?: e}"), e)
        }
    }
}

/**
 * Opens [result] by its magnet and by its .torrent at once, whichever has the metadata first: the magnet needs
 * only peers, the .torrent only the tracker's site. One after the other cost the magnet's whole wait when no
 * peer answered (2026-11-28 CI: 20.8 s against 0.6-2.5 s). Blocking.
 */
fun TorrentSource.open(
    result: SearchResult,
    engine: TorrentEngine,
): TorrentHandle {
    val magnet = runCatching { magnet(result) }.getOrNull()
    if (magnet.isNullOrEmpty()) return engine.openTorrent(torrentFile(result))
    // Not a coroutine: its scope would wait for a slow download of the .torrent after the magnet has won.
    val byFile = CompletableFuture.supplyAsync { runCatching { engine.openTorrent(torrentFile(result)) } }
    // Both are one torrent in the session, so this wait also ends when the .torrent brings the metadata.
    return runCatching { engine.openMagnet(magnet, MAGNET_FIRST_MS) }.getOrNull() ?: byFile.get().getOrThrow()
}

/**
 * Starts fetching the metadata of [result] ahead of a tap, see [TorrentEngine.prefetch]. Blocking: a tracker may
 * need a request for the magnet. A release without a magnet is left to its .torrent at the tap.
 */
fun TorrentSource.prefetch(
    result: SearchResult,
    engine: TorrentEngine,
) {
    magnet(result).takeIf { it.isNotEmpty() }?.let(engine::prefetch)
}
