package local.cinema.launcher

import android.os.Build
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

data class PlexServer(val name: String, val machineId: String, val baseUrl: String, val token: String)

data class PlexPin(val id: Long, val code: String, val expiresInSec: Int)

/** One "continue watching" entry, already shaped for the home screen. */
data class CwItem(
    val ratingKey: String,
    val title: String,     // show or film title
    val badge: String,     // "S2 · E5" or "Film"
    val subtitle: String,  // "S2 · E5 · Low Tide"
    val progress: Float,   // 0..1
    val timeLeft: String,  // "23 min left"
    val thumbUrl: String?, // 16:9 card image
    val artUrl: String?,   // full-screen backdrop
    val deepLink: String,  // opens the item in its app: a plex:// link, or the app's own intent URI
    val resumeSeconds: Long, // where the link asks Plex to start
    val app: String = PlexClient.PLEX_PACKAGE, // the app the item comes from and opens in
)

sealed class CwState {
    object NotLinked : CwState()
    data class Failed(val message: String) : CwState()
    data class Loaded(val items: List<CwItem>, val serverName: String) : CwState()
}

class PlexException(message: String) : Exception(message)
class PlexAuthException : Exception("Plex sign-in expired")
class PinExpiredException : Exception("code expired")

/**
 * Talks to plex.tv (sign-in with a plex.tv/link code, server discovery) and to the
 * Plex Media Server itself (continue watching, artwork). Call from a background thread.
 */
class PlexClient(private val prefs: Prefs) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val probe = http.newBuilder()
        .connectTimeout(2500, TimeUnit.MILLISECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    private fun request(url: String, token: String? = null): Request.Builder {
        val builder = Request.Builder().url(url)
            .header("Accept", "application/json")
            .header("X-Plex-Client-Identifier", prefs.clientId)
            .header("X-Plex-Product", PRODUCT)
            .header("X-Plex-Version", BuildConfig.VERSION_NAME)
            .header("X-Plex-Platform", "Android")
            .header("X-Plex-Platform-Version", Build.VERSION.RELEASE)
            .header("X-Plex-Device", Build.MODEL)
            .header("X-Plex-Device-Name", "$PRODUCT (${Build.MODEL})")
        if (token != null) builder.header("X-Plex-Token", token)
        return builder
    }

    // ---- sign-in: plex.tv/link code ----

    fun createPin(): PlexPin {
        val req = request("https://plex.tv/api/v2/pins?strong=false")
            .post(ByteArray(0).toRequestBody(null))
            .build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw PlexException("plex.tv answered ${r.code}")
            val o = JSONObject(r.body!!.string())
            return PlexPin(o.getLong("id"), o.getString("code"), o.optInt("expiresIn", 900))
        }
    }

    /** The account token once the code has been entered; null while still waiting. */
    fun checkPin(id: Long): String? {
        http.newCall(request("https://plex.tv/api/v2/pins/$id").get().build()).execute().use { r ->
            if (r.code == 404) throw PinExpiredException()
            if (!r.isSuccessful) return null
            return JSONObject(r.body!!.string()).str("authToken")
        }
    }

    // ---- server discovery ----

    /** Finds the account's Plex Media Server and the first address that answers from this TV. */
    fun findServer(accountToken: String): PlexServer {
        val req = request("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=1", accountToken).get().build()
        val resources = http.newCall(req).execute().use { r ->
            if (r.code == 401) throw PlexAuthException()
            if (!r.isSuccessful) throw PlexException("plex.tv answered ${r.code}")
            JSONArray(r.body!!.string())
        }
        val servers = resources.objects()
            .filter { it.optString("provides").split(',').contains("server") }
            .sortedByDescending { it.optBoolean("owned") }
        if (servers.isEmpty()) throw PlexException("there is no Plex Media Server on this account")

        for (s in servers) {
            val token = s.str("accessToken") ?: accountToken
            val connections = s.optJSONArray("connections")?.objects().orEmpty()
            val candidates = mutableListOf<String>()
            // Home network first (HTTPS, then plain HTTP), then remote, relay last.
            for (c in connections.filter { it.optBoolean("local") && !it.optBoolean("relay") }) {
                c.str("uri")?.let { candidates.add(it) }
                c.str("address")?.let { candidates.add("http://$it:${c.optInt("port", 32400)}") }
            }
            connections.filter { !it.optBoolean("local") && !it.optBoolean("relay") }
                .forEach { c -> c.str("uri")?.let { candidates.add(it) } }
            connections.filter { it.optBoolean("relay") }
                .forEach { c -> c.str("uri")?.let { candidates.add(it) } }

            for (base in candidates.distinct()) {
                if (reachable(base, token)) {
                    return PlexServer(
                        name = s.optString("name", "Plex"),
                        machineId = s.optString("clientIdentifier"),
                        baseUrl = base.trimEnd('/'),
                        token = token,
                    )
                }
            }
        }
        throw PlexException("your Plex server didn't answer from the TV")
    }

    private fun reachable(base: String, token: String): Boolean = try {
        probe.newCall(request("${base.trimEnd('/')}/identity", token).get().build()).execute().use { it.isSuccessful }
    } catch (e: Exception) {
        false
    }

    // ---- continue watching ----

    fun continueWatching(): CwState {
        val account = prefs.plexToken ?: return CwState.NotLinked
        return try {
            val saved = prefs.server
            val (items, server) = try {
                if (saved == null) throw IOException("no server yet")
                fetchItems(saved) to saved
            } catch (e: IOException) {
                // Saved address no longer answers (new IP, server restarted): look it up again.
                val found = findServer(account)
                prefs.server = found
                fetchItems(found) to found
            }
            CwState.Loaded(items, server.name)
        } catch (e: PlexAuthException) {
            prefs.forgetPlex()
            CwState.NotLinked
        } catch (e: Exception) {
            CwState.Failed(e.message ?: "Plex isn't reachable")
        }
    }

    private fun fetchItems(s: PlexServer): List<CwItem> {
        for (path in CW_PATHS) {
            val json = getJson("${s.baseUrl}$path?X-Plex-Container-Start=0&X-Plex-Container-Size=24", s.token) ?: continue
            val metadata = try {
                metadataOf(json)
            } catch (e: JSONException) {
                null
            } ?: continue
            return metadata.mapNotNull { toItem(it, s) }
        }
        return emptyList()
    }

    /** JSON from the server, or null when this endpoint doesn't exist on this server version. */
    private fun getJson(url: String, token: String): JSONObject? {
        http.newCall(request(url, token).get().build()).execute().use { r ->
            if (r.code == 401) throw PlexAuthException()
            if (!r.isSuccessful) return null
            return try {
                JSONObject(r.body!!.string())
            } catch (e: JSONException) {
                null
            }
        }
    }

    private fun metadataOf(root: JSONObject): List<JSONObject>? {
        val mc = root.optJSONObject("MediaContainer") ?: return null
        mc.optJSONArray("Metadata")?.let { return it.objects() }
        val hubs = mc.optJSONArray("Hub")?.objects() ?: return if (mc.has("size")) emptyList() else null
        val hub = hubs.firstOrNull { it.optString("hubIdentifier").contains("continueWatching", ignoreCase = true) }
            ?: hubs.firstOrNull { it.optString("hubIdentifier").contains("onDeck", ignoreCase = true) }
            ?: hubs.singleOrNull()
            ?: return emptyList()
        return hub.optJSONArray("Metadata")?.objects().orEmpty()
    }

    private fun toItem(m: JSONObject, s: PlexServer): CwItem? {
        val ratingKey = m.str("ratingKey") ?: return null
        val type = m.optString("type")
        val duration = m.optLong("duration", 0)
        val offset = m.optLong("viewOffset", 0)
        val progress = if (duration > 0) (offset.toFloat() / duration).coerceIn(0f, 1f) else 0f
        val timeLeft = timeLeftText(duration, offset)

        val title: String
        val badge: String
        val subtitle: String
        when (type) {
            "episode" -> {
                title = m.str("grandparentTitle") ?: m.optString("title")
                val season = m.optInt("parentIndex", -1)
                val episode = m.optInt("index", -1)
                badge = if (season >= 0 && episode >= 0) "S$season · E$episode" else "Episode"
                subtitle = listOfNotNull(badge, m.str("title")).joinToString(" · ")
            }
            "movie" -> {
                title = m.optString("title")
                badge = "Film"
                subtitle = listOfNotNull("Film", m.optInt("year", 0).takeIf { it > 0 }?.toString()).joinToString(" · ")
            }
            else -> {
                title = m.optString("title")
                badge = type.replaceFirstChar { it.uppercase() }
                subtitle = ""
            }
        }

        val thumbPath = if (type == "episode") m.str("thumb") ?: m.str("grandparentArt") else m.str("art") ?: m.str("thumb")
        val artPath = if (type == "episode") m.str("grandparentArt") ?: m.str("art") ?: m.str("thumb") else m.str("art") ?: m.str("thumb")
        return CwItem(
            ratingKey = ratingKey,
            title = title,
            badge = badge,
            subtitle = subtitle,
            progress = progress,
            timeLeft = timeLeft,
            thumbUrl = thumbPath?.let { image(s, it, 400, 225) },
            artUrl = artPath?.let { image(s, it, 1600, 900) },
            deepLink = deepLink(s, ratingKey, offset / 1000),
            resumeSeconds = offset / 1000,
        )
    }

    /**
     * Where the server reports this item playing right now (ms, on any player); -1 when it isn't
     * playing; null when it can't tell (no server yet, not the server's owner, network trouble).
     */
    fun playingAt(ratingKey: String): Long? {
        val s = prefs.server ?: return null
        return try {
            http.newCall(request("${s.baseUrl}/status/sessions", s.token).get().build()).execute().use { r ->
                if (!r.isSuccessful) return null
                val mc = JSONObject(r.body!!.string()).optJSONObject("MediaContainer") ?: return -1L
                val session = mc.optJSONArray("Metadata")?.objects().orEmpty().firstOrNull { it.str("ratingKey") == ratingKey }
                session?.optLong("viewOffset", 0L) ?: -1L
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Server-side resized image, so the TV never decodes full-size posters. */
    private fun image(s: PlexServer, path: String, width: Int, height: Int): String =
        "${s.baseUrl}/photo/:/transcode?width=$width&height=$height&minSize=1&upscale=1" +
            "&url=${enc(path)}&X-Plex-Token=${enc(s.token)}"

    /**
     * Plex's own link format, as the Plex app builds it for its Continue watching entries:
     * plex://watch/video?uri=server://<id>/…/metadata/<key>&offset=<seconds to resume at>.
     */
    private fun deepLink(s: PlexServer, ratingKey: String, offsetSeconds: Long): String {
        val uri = "server://${s.machineId}/com.plexapp.plugins.library/library/metadata/$ratingKey"
        return "plex://watch/video?uri=${enc(uri)}&offset=$offsetSeconds"
    }

    companion object {
        const val PLEX_PACKAGE = "com.plexapp.android"
        private const val PRODUCT = "Cinema Launcher"
        private val CW_PATHS = listOf(
            "/hubs/continueWatching/items",
            "/hubs/home/continueWatching",
            "/hubs/continueWatching",
            "/library/onDeck",
        )
    }
}

private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONObject.str(name: String): String? = if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }

private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

/** "23 min left", or just the running time when not started; "" without a duration. */
fun timeLeftText(durationMs: Long, offsetMs: Long): String {
    if (durationMs <= 0) return ""
    val minutes = ceil((durationMs - offsetMs).coerceAtLeast(0) / 60000.0).toInt()
    return if (offsetMs <= 0) formatMinutes(minutes) else formatMinutes(minutes) + " left"
}

private fun formatMinutes(total: Int): String = when {
    total >= 60 && total % 60 == 0 -> "${total / 60} h"
    total >= 60 -> "${total / 60} h ${total % 60} min"
    else -> "$total min"
}
