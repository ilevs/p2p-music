package io.github.ilevs.p2pmusic

import android.content.Context
import androidx.core.content.edit
import io.github.ilevs.p2pmusic.catalog.Album
import io.github.ilevs.p2pmusic.search.Credentials
import io.github.ilevs.p2pmusic.search.ProxySettings
import org.json.JSONArray
import org.json.JSONObject

/** What the user typed on the settings screen. Kept only on this device (backups are off). */
data class UserSettings(
    val rutrackerLogin: String = "",
    val rutrackerPassword: String = "",
    val proxyHost: String = "",
    val proxyPort: String = "",
    val proxyUser: String = "",
    val proxyPassword: String = "",
    val seeding: Boolean = true,
    val soulseekLogin: String = "",
    val soulseekPassword: String = "",
)

fun UserSettings.credentials(): Credentials? =
    Credentials(rutrackerLogin.trim(), rutrackerPassword).takeIf { it.login.isNotEmpty() && it.password.isNotEmpty() }

/** Null when the proxy is not filled in, so requests go direct. */
fun UserSettings.proxy(): ProxySettings? {
    val port = proxyPort.trim().toIntOrNull()?.takeIf { it in 1..MAX_PORT }
    return if (proxyHost.isBlank() ||
        port == null
    ) {
        null
    } else {
        ProxySettings(proxyHost.trim(), port, proxyUser, proxyPassword)
    }
}

private const val MAX_PORT = 65_535
private const val LIBRARY_SIZE = 100

/** An album played before, with the torrent that holds it, so it plays again without the tracker. */
data class SavedAlbum(
    val album: Album,
    val hash: String,
)

/** Where playback was when the app last saved it. */
data class LastPlayed(
    val hash: String,
    val fileIndex: Int,
    val positionMs: Long,
)

internal fun Album.toJson(): JSONObject =
    JSONObject()
        .put("id", id)
        .put("title", title)
        .put("artist", artist)
        .put("year", year)
        .put("cover", cover)

// Older versions saved no "cover".
internal fun JSONObject.toAlbum(): Album =
    Album(getString("id"), getString("title"), getString("artist"), getString("year"), optString("cover"))

/** One saved album as JSON: the form kept in settings and written to the export file. */
internal fun SavedAlbum.toJson(): JSONObject = album.toJson().put("hash", hash)

internal fun JSONObject.toSavedAlbum(): SavedAlbum = SavedAlbum(toAlbum(), getString("hash"))

internal fun parseLibrary(json: String): List<SavedAlbum> {
    val array = JSONArray(json)
    return (0 until array.length()).map { array.getJSONObject(it).toSavedAlbum() }
}

internal fun parseAlbums(json: String): List<Album> {
    val array = JSONArray(json)
    return (0 until array.length()).map { array.getJSONObject(it).toAlbum() }
}

/** The newest [LIBRARY_SIZE] played albums, and every liked one older than that: it keeps its release. */
internal fun trimHistory(
    history: List<SavedAlbum>,
    liked: Set<String>,
): List<SavedAlbum> = history.filterIndexed { i, saved -> i < LIBRARY_SIZE || saved.album.id in liked }

class SettingsStore(
    context: Context,
) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): UserSettings =
        UserSettings(
            rutrackerLogin = prefs.getString(RUTRACKER_LOGIN, "").orEmpty(),
            rutrackerPassword = prefs.getString(RUTRACKER_PASSWORD, "").orEmpty(),
            proxyHost = prefs.getString(PROXY_HOST, "").orEmpty(),
            proxyPort = prefs.getString(PROXY_PORT, "").orEmpty(),
            proxyUser = prefs.getString(PROXY_USER, "").orEmpty(),
            proxyPassword = prefs.getString(PROXY_PASSWORD, "").orEmpty(),
            seeding = prefs.getBoolean(SEEDING, true),
            soulseekLogin = prefs.getString(SOULSEEK_LOGIN, "").orEmpty(),
            soulseekPassword = prefs.getString(SOULSEEK_PASSWORD, "").orEmpty(),
        )

    fun save(settings: UserSettings) {
        prefs.edit {
            putString(RUTRACKER_LOGIN, settings.rutrackerLogin)
            putString(RUTRACKER_PASSWORD, settings.rutrackerPassword)
            putString(PROXY_HOST, settings.proxyHost)
            putString(PROXY_PORT, settings.proxyPort)
            putString(PROXY_USER, settings.proxyUser)
            putString(PROXY_PASSWORD, settings.proxyPassword)
            putBoolean(SEEDING, settings.seeding)
            putString(SOULSEEK_LOGIN, settings.soulseekLogin)
            putString(SOULSEEK_PASSWORD, settings.soulseekPassword)
        }
    }

    fun lastPlayed(): LastPlayed? {
        val hash = prefs.getString(LAST_HASH, null) ?: return null
        return LastPlayed(hash, prefs.getInt(LAST_FILE, 0), prefs.getLong(LAST_POSITION, 0L))
    }

    fun saveLastPlayed(last: LastPlayed) {
        prefs.edit {
            putString(LAST_HASH, last.hash)
            putInt(LAST_FILE, last.fileIndex)
            putLong(LAST_POSITION, last.positionMs)
        }
    }

    /** The listening history: played albums with their releases, newest first. */
    fun library(): List<SavedAlbum> = parseLibrary(prefs.getString(LIBRARY, "[]").orEmpty())

    fun addToLibrary(saved: SavedAlbum) {
        val history = listOf(saved) + library().filterNot { it.album.id == saved.album.id }
        val library = trimHistory(history, liked().map { it.id }.toSet())
        prefs.edit { putString(LIBRARY, JSONArray(library.map { it.toJson() }).toString()) }
    }

    /** "Мои альбомы": the albums the user liked, newest first. */
    fun liked(): List<Album> = parseAlbums(prefs.getString(LIKED, "[]").orEmpty())

    fun setLiked(
        album: Album,
        liked: Boolean,
    ) {
        val others = liked().filterNot { it.id == album.id }
        val albums = if (liked) listOf(album) + others else others
        prefs.edit { putString(LIKED, JSONArray(albums.map { it.toJson() }).toString()) }
    }

    private companion object {
        const val RUTRACKER_LOGIN = "rutracker_login"
        const val RUTRACKER_PASSWORD = "rutracker_password"
        const val PROXY_HOST = "proxy_host"
        const val PROXY_PORT = "proxy_port"
        const val PROXY_USER = "proxy_user"
        const val PROXY_PASSWORD = "proxy_password"
        const val LAST_HASH = "last_hash"
        const val LAST_FILE = "last_file"
        const val LAST_POSITION = "last_position"
        const val LIBRARY = "library"
        const val LIKED = "liked"
        const val SEEDING = "seeding"
        const val SOULSEEK_LOGIN = "soulseek_login"
        const val SOULSEEK_PASSWORD = "soulseek_password"
    }
}
