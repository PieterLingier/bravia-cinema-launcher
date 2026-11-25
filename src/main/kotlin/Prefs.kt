package local.cinema.launcher

import android.content.Context
import java.util.UUID

/** The launcher's private settings: Plex link, last working server, home-row order, remote buttons. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("cinema", Context.MODE_PRIVATE)

    /** Stable id that identifies this TV to Plex (X-Plex-Client-Identifier). */
    val clientId: String
        get() = sp.getString(KEY_CLIENT_ID, null) ?: UUID.randomUUID().toString().also {
            sp.edit().putString(KEY_CLIENT_ID, it).apply()
        }

    /** Plex account token from the plex.tv/link flow; null until linked. */
    var plexToken: String?
        get() = sp.getString(KEY_TOKEN, null)
        set(value) {
            sp.edit().putString(KEY_TOKEN, value).apply()
        }

    /** The Plex server and connection that worked last time. */
    var server: PlexServer?
        get() {
            val url = sp.getString(KEY_SERVER_URL, null) ?: return null
            val token = sp.getString(KEY_SERVER_TOKEN, null) ?: return null
            return PlexServer(
                name = sp.getString(KEY_SERVER_NAME, null) ?: "Plex",
                machineId = sp.getString(KEY_SERVER_ID, null) ?: "",
                baseUrl = url,
                token = token,
            )
        }
        set(value) {
            val editor = sp.edit()
            if (value == null) {
                editor.remove(KEY_SERVER_URL).remove(KEY_SERVER_NAME).remove(KEY_SERVER_ID).remove(KEY_SERVER_TOKEN)
            } else {
                editor.putString(KEY_SERVER_URL, value.baseUrl)
                    .putString(KEY_SERVER_NAME, value.name)
                    .putString(KEY_SERVER_ID, value.machineId)
                    .putString(KEY_SERVER_TOKEN, value.token)
            }
            editor.apply()
        }

    /** Package names in the home row, in order; null until the user edits the row. */
    var rowOrder: List<String>?
        get() = sp.getString(KEY_ROW, null)?.split('\n')?.filter { it.isNotBlank() }
        set(value) {
            sp.edit().putString(KEY_ROW, value?.joinToString("\n")).apply()
        }

    /** Every launchable package seen so far, so newly installed apps can be spotted. */
    var knownPackages: Set<String>
        get() = sp.getStringSet(KEY_KNOWN, null)?.toSet() ?: emptySet()
        set(value) {
            sp.edit().putStringSet(KEY_KNOWN, value).apply()
        }

    /**
     * The apps the Continue watching rows come from (package names), top row first: Plex unless the
     * user picked another, or two apps for two rows. (1.4 stored a single app under KEY_CW_SOURCE.)
     */
    var cwSources: List<String>
        get() = sp.getString(KEY_CW_SOURCES, null)?.split('\n')?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
            ?: listOf(sp.getString(KEY_CW_SOURCE, null) ?: PlexClient.PLEX_PACKAGE)
        set(value) {
            sp.edit().putString(KEY_CW_SOURCES, value.joinToString("\n")).remove(KEY_CW_SOURCE).apply()
        }

    /** Remote buttons that open an app ([RemoteButtons.id] → package); [RemoteButtons.DEFAULT] until changed. */
    var buttonApps: Map<Int, String>
        get() = sp.getString(KEY_BUTTONS, null)?.let { stored ->
            stored.split('\n').mapNotNull { line ->
                val (code, pkg) = line.split('=', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
                code.toIntOrNull()?.takeIf { it != 0 }?.let { it to pkg }
            }.toMap()
        } ?: RemoteButtons.DEFAULT
        set(value) {
            sp.edit().putString(KEY_BUTTONS, value.entries.joinToString("\n") { "${it.key}=${it.value}" }).apply()
        }

    fun forgetPlex() {
        plexToken = null
        server = null
    }

    private companion object {
        const val KEY_CLIENT_ID = "clientId"
        const val KEY_TOKEN = "plexToken"
        const val KEY_SERVER_URL = "serverUrl"
        const val KEY_SERVER_NAME = "serverName"
        const val KEY_SERVER_ID = "serverId"
        const val KEY_SERVER_TOKEN = "serverToken"
        const val KEY_ROW = "rowOrder"
        const val KEY_KNOWN = "knownPackages"
        const val KEY_CW_SOURCE = "cwSource"
        const val KEY_CW_SOURCES = "cwSources"
        const val KEY_BUTTONS = "buttonApps"
    }
}
