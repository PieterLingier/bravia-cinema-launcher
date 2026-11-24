package local.cinema.launcher

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Two remote buttons, while this service is switched on:
 *
 * - **Google Play button → Plex.** On this BRAVIA that button sends KEYCODE_BUTTON_8 (195), which
 *   Sony's vendor service turns into "open Google TV". Key filtering in an accessibility service runs
 *   before that handler, so taking the key here keeps Google TV closed.
 * - **Home → Cinema, only while Cinema isn't Android's home app.** That's for TVs where Google's
 *   home screen stays switched on (on Google TV it also runs the settings panel), so Android would
 *   send Home there. When Cinema *is* the home app, Home is left to Android, unchanged.
 *
 * Every other key is passed on untouched; this service reads no screen content and receives no
 * accessibility events.
 */
class ButtonService : AccessibilityService() {

    override fun onKeyEvent(event: KeyEvent): Boolean = when (event.keyCode) {
        REMAPPED_KEY -> {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) openPlex()
            true // consume down, repeats and up, so the original action never runs
        }
        KeyEvent.KEYCODE_HOME -> {
            if (cinemaIsHome()) {
                false // Android's own Home handling already opens Cinema
            } else {
                if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) openCinema()
                true
            }
        }
        else -> false
    }

    /** Whether Android sends Home to Cinema by itself (Cinema is the default home app). */
    private fun cinemaIsHome(): Boolean {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
        return resolved?.activityInfo?.packageName == packageName
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
