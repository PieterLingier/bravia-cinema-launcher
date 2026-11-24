package local.cinema.launcher

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.widget.Toast

data class AppEntry(
    val packageName: String,
    val component: ComponentName,
    val label: String,
    val banner: Drawable?,
    val icon: Drawable?,
)

/** Installed TV apps, and which of them make up the home row. */
class AppRepo(private val context: Context, private val prefs: Prefs) {
    private val pm = context.packageManager

    /** Every app with a TV home-screen entry, except this launcher. */
    fun allApps(withImages: Boolean = true): List<AppEntry> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .filter { it.activityInfo.packageName != context.packageName }
            .distinctBy { it.activityInfo.packageName }
            .map { info ->
                val activity = info.activityInfo
                AppEntry(
                    packageName = activity.packageName,
                    component = ComponentName(activity.packageName, activity.name),
                    label = info.loadLabel(pm).toString(),
                    banner = if (withImages) activity.loadBanner(pm) ?: activity.applicationInfo.loadBanner(pm) else null,
                    icon = if (withImages) info.loadIcon(pm) else null,
                )
            }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * The home row: the user's order once they've edited it, otherwise the default media
     * apps. Newly installed apps that aren't utilities are added at the end.
     */
    fun homeRow(all: List<AppEntry> = allApps()): List<AppEntry> {
        val byPackage = all.associateBy { it.packageName }
        val installed = all.map { it.packageName }
        val saved = prefs.rowOrder
        val order = if (saved == null) {
            DEFAULT_ORDER + installed.filter { it !in DEFAULT_ORDER && it !in NOT_IN_ROW }
        } else {
            val known = prefs.knownPackages
            val newcomers = installed.filter { it !in known && it !in NOT_IN_ROW && it !in saved }
            if (newcomers.isNotEmpty()) prefs.rowOrder = saved + newcomers
            saved + newcomers
        }
        prefs.knownPackages = installed.toSet()
        return order.distinct().mapNotNull { byPackage[it] }
    }

    fun rowPackages(): List<String> = homeRow(allApps(withImages = false)).map { it.packageName }

    fun move(packageName: String, delta: Int) {
        val row = rowPackages().toMutableList()
        val from = row.indexOf(packageName)
        if (from < 0) return
        val to = (from + delta).coerceIn(0, row.lastIndex)
        if (to == from) return
        row.removeAt(from)
        row.add(to, packageName)
        prefs.rowOrder = row
    }

    fun remove(packageName: String) {
        prefs.rowOrder = rowPackages() - packageName
    }

    fun add(packageName: String) {
        prefs.rowOrder = rowPackages() - packageName + packageName
    }

    fun launch(activity: Activity, app: AppEntry) {
        val intent = pm.getLeanbackLaunchIntentForPackage(app.packageName)
            ?: Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setComponent(app.component)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(activity, activity.getString(R.string.cant_open, app.label), Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        /** The media apps on this TV, in the order of mockup A. */
        val DEFAULT_ORDER = listOf(
            "com.plexapp.android",
            "com.netflix.ninja",
            "com.google.android.youtube.tv",
            "com.disney.disneyplus",
            "nl.streamgroup.vlaanderen",
            "be.vmma.vtm.zenderapp",
            "be.vrt.vrtnu",
            "be.goplay.app",
            "be.telenet.tv",
            "com.dazn",
            "com.apple.atve.sony.appletv",
            "com.nousguide.android.rbtv",
            "com.google.android.youtube.tvmusic",
            "com.sony.dtv.tvx",
        )

        /** Launchable apps that aren't media apps: reachable from "All apps" instead. */
        val NOT_IN_ROW = setOf(
            "com.android.vending",
            "com.google.android.play.games",
            "com.google.android.videos",
            "com.android.tv.settings",
            "com.sony.dtv.livingfit",
            "com.sony.dtv.notificationcenter",
            "com.sony.dtv.smarthelp",
            "com.sony.dtv.timers",
            "com.sony.dtv.recapp",
            "com.sony.dtv.smartmediaapp",
            "com.sony.dtv.osat.music",
            "com.sony.dtv.sonyselect",
            "com.vewd.core.integration.dia",
        )
    }
}
