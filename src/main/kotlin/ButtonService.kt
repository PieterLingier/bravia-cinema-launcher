package local.cinema.launcher

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import android.view.accessibility.AccessibilityEvent

/**
 * Remote buttons, while this service is switched on:
 *
 * - **Assigned buttons → their app** ([RemoteButtons], set up in Cinema by holding OK on an app).
 *   Until changed, the Google Play button opens Plex: on this BRAVIA it sends KEYCODE_BUTTON_8 (195),
 *   which Sony's vendor service turns into "open Google TV". Key filtering in an accessibility
 *   service runs before that handler, so taking the key here keeps Google TV closed. While Cinema
 *   waits for a button to assign, the next one that isn't [RemoteButtons.RESERVED] goes to Cinema.
 * - **Home → Cinema, only while Cinema isn't Android's home app.** That's for TVs where Google's
 *   home screen stays switched on (on Google TV it also runs the settings panel), so Android would
 *   send Home there. When Cinema *is* the home app, Home is left to Android, unchanged.
 *
 * Every other key is passed on untouched. The service also receives Plex's window events (type and
 * class name only, never screen content) so [PlexWatch] can tell when Plex is ready for a link.
 *
 * **Start-up → Cinema, again only while Cinema isn't the home app.** When the TV starts or wakes
 * from standby, Android opens its default home screen without any key press, so the Home rule above
 * never sees it. For a short while after start-up or waking ([STARTUP_MS], ended early by the first
 * key press), whenever the default home screen's window shows up, the service opens Cinema in its
 * place. The key press ends it so that whatever the viewer opens on purpose afterwards is left alone
 * (Google TV's settings panel lives in its home app). Only the package of those events is used.
 */
class ButtonService : AccessibilityService() {

    /** Until when (SystemClock.uptimeMillis) the default home screen is replaced by Cinema; 0 = off. */
    @Volatile private var startupUntil = 0L

    /** When Cinema was last opened for that, so a burst of home-screen events opens it once. */
    private var openedAt = 0L

    /** The packages of the other home screens, whose windows trigger the start-up switch. */
    private var homePackages = emptySet<String>()

    private val prefs by lazy { Prefs(this) }
    private val main = Handler(Looper.getMainLooper())

    /** A button just taken for assigning: its repeats and key-up are swallowed too. */
    private var learntKey = 0

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = startStartup("screen on")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        // The viewer is using the remote: from now on, the screen they see is their choice.
        if (startupUntil != 0L && event.action == KeyEvent.ACTION_DOWN && event.keyCode !in WAKE_KEYS) {
            startupUntil = 0L
        }
        return learn(event) ?: handleKey(event)
    }

    /** While Cinema waits for a button to assign: takes it (true), or null when not learning. */
    private fun learn(event: KeyEvent): Boolean? {
        val button = RemoteButtons.id(event)
        if (learntKey != 0 && button == learntKey) {
            if (event.action == KeyEvent.ACTION_UP) learntKey = 0
            return true
        }
        val learner = RemoteButtons.learner ?: return null
        if (button == 0) return null // a reserved key (e.g. Back closes Cinema's prompt), or unidentifiable
        if (event.action == KeyEvent.ACTION_DOWN) {
            RemoteButtons.learner = null
            learntKey = button
            Log.i(TAG, "learnt button $button")
            main.post { learner(button) }
        }
        return true
    }

    private fun handleKey(event: KeyEvent): Boolean = when (event.keyCode) {
        KeyEvent.KEYCODE_HOME -> {
            if (cinemaIsHome()) {
                false // Android's own Home handling already opens Cinema
            } else {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) openCinema()
                true
            }
        }
        in RemoteButtons.RESERVED -> false
        else -> {
            val button = RemoteButtons.id(event)
            val app = prefs.buttonApps[button].takeIf { button != 0 }
            val intent = app?.let(::launchIntent)
            if (intent != null && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                try {
                    startActivity(intent)
                    Log.i(TAG, "button $button: opened $app")
                } catch (e: Exception) {
                    Log.w(TAG, "couldn't open $app: $e")
                }
            }
            // An assigned button: consume down, repeats and up, so its original action never runs.
            // If its app is gone, the button does what it did before.
            intent != null
        }
    }

    /** Whether Android sends Home to Cinema by itself (Cinema is the default home app). */
    private fun cinemaIsHome(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == packageName
    }

    /** Replace the default home screen by Cinema for [STARTUP_MS], unless Cinema is the home app. */
    private fun startStartup(why: String) {
        if (cinemaIsHome()) return
        startupUntil = SystemClock.uptimeMillis() + STARTUP_MS
        Log.i(TAG, "start-up ($why): the default home screen opens Cinema for ${STARTUP_MS / 1000} s")
    }

    /** The other home screens: Android's current default, plus Google's two (in case it resolves to a chooser). */
    private fun otherHomePackages(): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val default = packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        return GOOGLE_HOMES + listOfNotNull(default) - setOf(packageName, "android")
    }

    private fun openCinema() {
        val intent = Intent(this, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addCategory(Intent.CATEGORY_HOME)
        try {
            startActivity(intent)
            Log.i(TAG, "Home: opened Cinema (another app is the default home)")
        } catch (e: Exception) {
            Log.w(TAG, "couldn't open Cinema: $e")
        }
    }

    private fun launchIntent(app: String): Intent? =
        (packageManager.getLeanbackLaunchIntentForPackage(app) ?: packageManager.getLaunchIntentForPackage(app))
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)

    override fun onServiceConnected() {
        homePackages = otherHomePackages()
        // Also set in button_service.xml, but Android keeps the old settings after an app update
        // until the service is switched off and on, so set them here too.
        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_VIEW_FOCUSED
            packageNames = (homePackages + PlexClient.PLEX_PACKAGE).toTypedArray()
        }
        PlexWatch.connected = true
        ContextCompat.registerReceiver(
            this, screenOn, IntentFilter(Intent.ACTION_SCREEN_ON), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Android connects the service while the TV starts up. Later (switched on by hand, or after
        // an app update) it isn't start-up any more.
        if (SystemClock.elapsedRealtime() < BOOT_MS) startStartup("boot")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        PlexWatch.connected = false
        try {
            unregisterReceiver(screenOn)
        } catch (_: IllegalArgumentException) {
            // not registered
        }
        return super.onUnbind(intent)
    }

    /**
     * Window events from Plex (to see when Plex has loaded) and from the other home screens (to
     * replace them by Cinema at start-up); see button_service.xml.
     */
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()
        if (pkg == PlexClient.PLEX_PACKAGE) {
            PlexWatch.onEvent(event.eventType, event.className?.toString())
        } else if (pkg in homePackages && startupUntil != 0L) {
            val now = SystemClock.uptimeMillis()
            if (now > startupUntil) {
                startupUntil = 0L
            } else if (!HomeActivity.inFront && now - openedAt > REOPEN_MS) {
                openedAt = now
                Log.i(TAG, "start-up: the default home screen showed up, opening Cinema")
                openCinema()
            }
        }
    }

    override fun onInterrupt() = Unit

    private companion object {
        const val TAG = "CinemaButton"
        const val STARTUP_MS = 60_000L // how long after start-up or waking the home screen is replaced
        const val BOOT_MS = 5 * 60_000L // service connected this soon after boot: that's start-up
        const val REOPEN_MS = 1_500L // at most one Cinema launch per burst of home-screen events

        /** Google TV's home screen, and the older Android TV one. */
        val GOOGLE_HOMES = setOf("com.google.android.apps.tv.launcherx", "com.google.android.tvlauncher")

        /** Keys that wake the TV: their key-up can arrive after waking, so they don't end start-up. */
        val WAKE_KEYS = setOf(
            KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_TV_POWER, KeyEvent.KEYCODE_WAKEUP, KeyEvent.KEYCODE_SLEEP,
        )
    }
}
