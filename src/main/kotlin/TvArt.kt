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

    /**
     * What you're watching in [pkg], as Continue watching cards; null without READ_TV_LISTINGS.
     * Call from a background thread. Two places, as apps publish it for the TV home screen:
     *
     * - **Watch Next** items, newest first, with episode and progress (the Android TV standard).
     * - Items in the app's own rows described as "Continue watching …". Netflix does this: on this
     *   TV it publishes no Watch Next at all, but its one row starts with the titles you're
     *   watching, without episode or progress, linking to the title in Netflix.
     */
    fun continueWatching(pkg: String): List<CwItem>? {
        if (context.checkSelfPermission(READ_TV_LISTINGS) != PackageManager.PERMISSION_GRANTED) return null

        class Entry(val item: CwItem, val engaged: Long, val id: Long)
        val watchNext = ArrayList<Entry>()
        val projection = arrayOf(
            ID, PACKAGE, ENGAGED, PROGRAM_TYPE, TITLE, EPISODE_TITLE, SEASON, EPISODE,
            POSITION, DURATION, INTENT_URI, BROWSABLE,
        ) + IMAGE_COLUMNS
        query(WATCH_NEXT, projection) { c ->
            if (c.str(PACKAGE) != pkg || c.long(BROWSABLE) == 0L) return@query // 0: removed by the user
            val id = c.long(ID) ?: return@query
            val item = cardOf(c, pkg, "watchnext:$id") ?: return@query
            watchNext += Entry(item, c.long(ENGAGED) ?: 0, id)
        }

        val inRows = ArrayList<Entry>()
        query(PREVIEW_PROGRAMS, arrayOf(ID, PACKAGE, PROGRAM_TYPE, TITLE, EPISODE_TITLE, SEASON, EPISODE,
            POSITION, DURATION, INTENT_URI, DESCRIPTION, CHANNEL_ID) + IMAGE_COLUMNS) { c ->
            if (c.str(PACKAGE) != pkg) return@query
            val description = c.str(DESCRIPTION)?.takeIf { it.startsWith(CONTINUE_PREFIX, ignoreCase = true) } ?: return@query
            val id = c.long(ID) ?: return@query
            var item = cardOf(c, pkg, "row:$id") ?: return@query
            // "Continue watching <episode title>": Netflix names the episode you're on there.
            val episode = description.drop(CONTINUE_PREFIX.length).trim()
            if (item.subtitle.isBlank() && episode.isNotEmpty() && episode != item.title) item = item.copy(subtitle = episode)
            inRows += Entry(item, 0, id)
        }

        val items = (watchNext.sortedWith(compareByDescending<Entry> { it.engaged }.thenByDescending { it.id }) +
            inRows.sortedBy { it.id }) // the app's own order
            .map { it.item }
            .distinctBy { it.title + "\u0000" + it.subtitle }
        // No titles, links or image URLs in the log.
        Log.i(TAG, "Continue watching from $pkg: ${watchNext.size} Watch Next, ${inRows.size} from its rows; " +
            items.joinToString { item ->
                "[${item.badge.ifEmpty { "-" }} ${(item.progress * 100).toInt()}% link ${Uri.parse(item.deepLink).scheme} " +
                    "img ${item.thumbUrl?.let(::hostOf) ?: "none"}${if (item.artUrl != null) " landscape" else ""}]"
            })
        return items
    }

    /** A Continue watching card from a Watch Next or row item of [pkg]; null without a title or link. */
    private fun cardOf(c: Cursor, pkg: String, key: String): CwItem? {
        val link = c.str(INTENT_URI) ?: return null
        val show = c.str(TITLE)?.takeIf { it.isNotBlank() } ?: return null
        val duration = c.long(DURATION) ?: 0
        val position = c.long(POSITION) ?: 0
        val season = c.str(SEASON)?.takeIf { it.isNotBlank() }
        val episode = c.str(EPISODE)?.takeIf { it.isNotBlank() }
        val episodeTitle = c.str(EPISODE_TITLE)?.takeIf { it.isNotBlank() }
        val badge = when {
            season != null && episode != null -> "S$season · E$episode"
            episode != null -> "E$episode"
            c.long(PROGRAM_TYPE) == TYPE_MOVIE && duration > 0 -> "Film"
            else -> ""
        }
        val landscape = landscapeImage(c)
        return CwItem(
            ratingKey = key,
            title = show,
            badge = badge,
            subtitle = listOf(badge, episodeTitle.orEmpty()).filter { it.isNotBlank() }.joinToString(" · "),
            progress = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
            timeLeft = timeLeftText(duration, position),
            thumbUrl = landscape ?: c.str(THUMB)?.takeIf(::usable) ?: c.str(POSTER)?.takeIf(::usable),
            artUrl = landscape,
            deepLink = link,
            resumeSeconds = position / 1000,
            app = pkg,
        )
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
        private const val CONTINUE_PREFIX = "Continue watching" // Netflix's description of in-progress titles in its row
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
        private const val PROGRAM_TYPE = "type" // in program tables: TYPE_MOVIE = 0, TYPE_TV_EPISODE = 3…
        private const val TYPE_MOVIE = 0L
        private const val TITLE = "title"
        private const val EPISODE_TITLE = "episode_title"
        private const val SEASON = "season_display_number"
        private const val EPISODE = "episode_display_number"
        private const val POSITION = "last_playback_position_millis"
        private const val DURATION = "duration_millis"
        private const val INTENT_URI = "intent_uri"
        private const val DESCRIPTION = "short_description"
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
