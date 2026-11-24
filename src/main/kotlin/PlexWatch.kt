package local.cinema.launcher

import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent

/**
 * Tells when Plex is ready for a link, from its accessibility events (fed by [ButtonService]; event
 * type and class name only, never screen content). Measured on this TV:
 *
 * - **Cold start** (Plex not running): its window appears, focus lands somewhere after about 3 s
 *   and on a cached Continue watching item after about 5 s, but a link then still fails with
 *   "Can't reach server". Its home list (a ScrollView) starts changing 6.5 to 10 s after the window,
 *   once the server's data arrives; a link sent then plays cleanly.
 * - **Warm start** (Plex already running): focus lands within 0.1 s of the window, the list doesn't
 *   change, and a link sent 1 s later plays cleanly.
 */
object PlexWatch {
    /** Whether the accessibility service is on, so these events arrive at all. */
    @Volatile var connected = false

    /** When Plex last became ready for a link (SystemClock.uptimeMillis), 0 = not yet. */
    @Volatile var readyAt = 0L
        private set

    /** When any Plex event last arrived: shows the events are flowing at all. */
    @Volatile var lastEventAt = 0L
        private set

    private var windowAt = 0L

    fun onEvent(type: Int, className: String?) {
        val now = SystemClock.uptimeMillis()
        lastEventAt = now
        when (type) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ->
                if (className?.endsWith("MainActivity") == true && now - windowAt > WINDOW_REPEAT_MS) windowAt = now
            AccessibilityEvent.TYPE_VIEW_FOCUSED ->
                if (windowAt > 0 && now - windowAt < WARM_FOCUS_MS) readyAt = now // warm: home already loaded
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ->
                if (windowAt > 0 && className == "android.widget.ScrollView") readyAt = now // cold: server data in
        }
    }

    private const val WARM_FOCUS_MS = 700L // warm focus came after 0.08 s; cold after 3 s
    private const val WINDOW_REPEAT_MS = 2_000L // a warm start reports the window twice, 0.1 s apart
}
