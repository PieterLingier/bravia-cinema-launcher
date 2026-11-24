package local.cinema.launcher

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.util.Log

/**
 * The artwork apps publish for the Android TV home screen, used as their backdrop in Cinema:
 * the app's Watch Next items (what you were watching in it, newest first), then the first items
 * of its home-screen rows ("channels"). Apps keep these up to date in Android's TV provider even
 * with Google's home screen switched off.
 *
 * Reading other apps' entries needs READ_TV_LISTINGS, granted over adb (README). Without it the
 * provider only returns Cinema's own entries (none), so every app keeps its colour glow.
 */
class TvArt(private val context: Context) {

    private class Art(
        val pkg: String,
        val url: String,
        val engaged: Long = 0,                // Watch Next: when you last watched it
        val rowShown: Boolean = false,        // channel: Google's home screen showed this row
        val channelId: Long = Long.MAX_VALUE, // channel: older rows are usually the app's main one
        val weight: Long = 0,                 // channel: the app's own ranking, higher first
        val id: Long = 0,
    )

    /** Up to [PER_APP] landscape image URLs per package, best first. Call from a background thread. */
    fun load(): Map<String, List<String>> {
        if (context.checkSelfPermission(READ_TV_LISTINGS) != PackageManager.PERMISSION_GRANTED) return emptyMap()

        val watchNext = ArrayList<Art>()
        query(WATCH_NEXT, arrayOf(ID, PACKAGE, ENGAGED) + IMAGE_COLUMNS) { c ->
            val pkg = c.str(PACKAGE) ?: return@query
            val url = landscapeImage(c) ?: return@query
            watchNext += Art(pkg, url, engaged = c.long(ENGAGED) ?: 0, id = c.long(ID) ?: 0)
        }

        val shownRows = HashMap<Long, Boolean>()
        query(CHANNELS, arrayOf(ID, TYPE, BROWSABLE)) { c ->
            val id = c.long(ID) ?: return@query
            if (c.str(TYPE) == TYPE_PREVIEW) shownRows[id] = c.long(BROWSABLE) == 1L
        }
        val rows = ArrayList<Art>()
        query(PREVIEW_PROGRAMS, arrayOf(ID, PACKAGE, CHANNEL_ID, WEIGHT) + IMAGE_COLUMNS) { c ->
            val pkg = c.str(PACKAGE) ?: return@query
            val url = landscapeImage(c) ?: return@query
            val channel = c.long(CHANNEL_ID) ?: Long.MAX_VALUE
            rows += Art(
                pkg, url,
                rowShown = shownRows[channel] == true,
                channelId = channel,
                weight = c.long(WEIGHT) ?: 0,
                id = c.long(ID) ?: 0,
            )
        }

        val ordered = watchNext.sortedWith(compareByDescending<Art> { it.engaged }.thenByDescending { it.id }) +
            rows.sortedWith(
                compareByDescending<Art> { it.rowShown }
                    .thenBy { it.channelId }
                    .thenByDescending { it.weight }
                    .thenBy { it.id },
            )
        val art = ordered
            .filter { it.pkg != context.packageName && it.pkg != PlexClient.PLEX_PACKAGE } // Plex: from Continue watching
            .groupBy { it.pkg }
            .mapValues { (_, list) -> list.map { it.url }.distinct().take(PER_APP) }
        // Only scheme and host: these URLs can carry account tokens.
        Log.i(TAG, "home-screen art: ${watchNext.size} Watch Next, ${rows.size} row item(s); " +
            art.entries.joinToString { (pkg, urls) -> "$pkg ${urls.size}x ${hostOf(urls.first())}" })
        return art
    }

    /** The poster or thumbnail, whichever is landscape; one whose shape the app didn't say is checked once loaded. */
    private fun landscapeImage(c: Cursor): String? {
        val poster = c.str(POSTER)?.takeIf(::usable)
        val thumb = c.str(THUMB)?.takeIf(::usable)
        val posterRatio = c.long(POSTER_RATIO)
        val thumbRatio = c.long(THUMB_RATIO)
        return when {
            poster != null && posterRatio in LANDSCAPE -> poster
            thumb != null && thumbRatio in LANDSCAPE -> thumb
            poster != null && posterRatio !in KNOWN_RATIOS -> poster
            thumb != null && thumbRatio !in KNOWN_RATIOS -> thumb
            else -> null
        }
    }

    private fun usable(url: String) = Uri.parse(url).scheme?.lowercase() in SCHEMES

    private inline fun query(uri: Uri, projection: Array<String>, each: (Cursor) -> Unit) {
        try {
            context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
                while (c.moveToNext()) each(c)
            }
        } catch (e: Exception) {
            Log.w(TAG, "can't read $uri: $e")
        }
    }

    private fun Cursor.str(column: String): String? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let { getString(it) }

    private fun Cursor.long(column: String): Long? =
        getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let { getLong(it) }

    companion object {
        private const val TAG = "Cinema"
        private const val PER_APP = 3
        const val READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS"

        private val WATCH_NEXT = Uri.parse("content://android.media.tv/watch_next_program")
        private val PREVIEW_PROGRAMS = Uri.parse("content://android.media.tv/preview_program")
        private val CHANNELS = Uri.parse("content://android.media.tv/channel")

        private const val ID = "_id"
        private const val PACKAGE = "package_name"
        private const val ENGAGED = "last_engagement_time_utc_millis"
        private const val CHANNEL_ID = "channel_id"
        private const val WEIGHT = "weight"
        private const val TYPE = "type"
        private const val TYPE_PREVIEW = "TYPE_PREVIEW"
        private const val BROWSABLE = "browsable"
        private const val POSTER = "poster_art_uri"
        private const val POSTER_RATIO = "poster_art_aspect_ratio"
        private const val THUMB = "thumbnail_uri"
        private const val THUMB_RATIO = "poster_thumbnail_aspect_ratio"
        private val IMAGE_COLUMNS = arrayOf(POSTER, POSTER_RATIO, THUMB, THUMB_RATIO)

        // TvContract aspect ratios: 0 = 16:9, 1 = 3:2, 2 = 4:3, then 1:1, 2:3 and movie poster (portrait).
        private val LANDSCAPE = 0L..2L
        private val KNOWN_RATIOS = 0L..5L
        private val SCHEMES = setOf("https", "http", "content", "android.resource")

        fun hostOf(url: String): String = Uri.parse(url).let { "${it.scheme}://${it.host.orEmpty()}" }
    }
}
