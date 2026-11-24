package local.cinema.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Turns the remote's Google Play button into a Plex button.
 *
 * On this BRAVIA that button sends KEYCODE_BUTTON_8 (195), which Sony's vendor service turns into
 * "open Google TV". Key filtering in an accessibility service runs before that handler, so taking
 * the key here keeps Google TV closed. Every other key is passed on untouched; this service reads
 * no screen content and receives no accessibility events.
 */
class ButtonService : AccessibilityService() {

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode != REMAPPED_KEY) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) openPlex()
        return true // consume down, repeats and up, so the original action never runs
    }

    private fun openPlex() {
        val intent = packageManager.getLeanbackLaunchIntentForPackage(PlexClient.PLEX_PACKAGE)
            ?: packageManager.getLaunchIntentForPackage(PlexClient.PLEX_PACKAGE)
        if (intent == null) {
            Log.w(TAG, "Plex isn't installed")
            return
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        try {
            startActivity(intent)
            Log.i(TAG, "Google Play button: opened Plex")
        } catch (e: Exception) {
            Log.w(TAG, "couldn't open Plex: $e")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    private companion object {
        const val TAG = "CinemaButton"
        const val REMAPPED_KEY = KeyEvent.KEYCODE_BUTTON_8 // the remote's Google Play button
    }
}
