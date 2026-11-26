package io.github.ilevs.p2pmusic

import android.content.Context
import androidx.core.content.edit
import io.github.ilevs.p2pmusic.search.Credentials
import io.github.ilevs.p2pmusic.search.ProxySettings

/** What the user typed on the settings screen. Kept only on this device (backups are off). */
data class UserSettings(
    val rutrackerLogin: String = "",
    val rutrackerPassword: String = "",
    val proxyHost: String = "",
    val proxyPort: String = "",
    val proxyUser: String = "",
    val proxyPassword: String = "",
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

/** Where playback was when the app last saved it. */
data class LastPlayed(
    val hash: String,
    val fileIndex: Int,
    val positionMs: Long,
)

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
        )

    fun save(settings: UserSettings) {
        prefs.edit {
            putString(RUTRACKER_LOGIN, settings.rutrackerLogin)
            putString(RUTRACKER_PASSWORD, settings.rutrackerPassword)
            putString(PROXY_HOST, settings.proxyHost)
            putString(PROXY_PORT, settings.proxyPort)
            putString(PROXY_USER, settings.proxyUser)
            putString(PROXY_PASSWORD, settings.proxyPassword)
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
    }
}
