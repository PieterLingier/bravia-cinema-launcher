package local.cinema.launcher

import android.content.Context
import android.view.KeyEvent

/**
 * Remote buttons that open an app: assigned in Cinema (hold OK on an app, "Assign a remote button"),
 * acted on by [ButtonService], which sees the remote's keys before the TV's own software does.
 */
object RemoteButtons {
    /** The Google Play button on this BRAVIA's remote; Sony's software makes it open Google TV. */
    const val GOOGLE_PLAY_KEY = KeyEvent.KEYCODE_BUTTON_8

    /** Until the user assigns or removes a button: the Google Play button opens Plex. */
    val DEFAULT = mapOf(GOOGLE_PLAY_KEY to PlexClient.PLEX_PACKAGE)

    /** Buttons that keep their own job, so the remote stays usable: they can't be assigned. */
    val RESERVED = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_TV_POWER, KeyEvent.KEYCODE_WAKEUP, KeyEvent.KEYCODE_SLEEP,
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
        KeyEvent.KEYCODE_MUTE,
    )

    /**
     * Set while Cinema waits for the button to assign. [ButtonService] takes the next button that
     * isn't [RESERVED], clears this and calls it with the button's [id] on the main thread.
     */
    @Volatile var learner: ((Int) -> Unit)? = null

    /**
     * Which button [event] came from: its Android key code, or for a button Android has no name for
     * (key code 0, shared by all of them) minus its scan code. 0 for a [RESERVED] key, or one that
     * can't be told apart.
     */
    fun id(event: KeyEvent): Int = when {
        event.keyCode in RESERVED -> 0
        event.keyCode != KeyEvent.KEYCODE_UNKNOWN -> event.keyCode
        event.scanCode > 0 -> -event.scanCode
        else -> 0
    }

    /** A readable name for a button [id], such as "Prog red" or "Button 8 (Google Play)". */
    fun name(context: Context, id: Int): String {
        if (id < 0) return context.getString(R.string.key_scan_code, -id)
        val keyCode = id
        val raw = KeyEvent.keyCodeToString(keyCode)
        if (raw.all { it.isDigit() }) return context.getString(R.string.key_number, keyCode)
        val name = raw.removePrefix("KEYCODE_").replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
        return if (keyCode == GOOGLE_PLAY_KEY) context.getString(R.string.key_google_play, name) else name
    }
}
