package local.cinema.launcher

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.DisplayMetrics
import android.util.Log
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A backdrop made from an app's own banner, for apps that publish no artwork: the banner large on
 * the right, on a wall of its own background colour. Bright banners are dimmed so the white text
 * and icons on top stay readable and a dark room isn't lit up.
 */
object BannerArt {
    private const val TAG = "Cinema"
    private const val WIDTH = 1280
    private const val HEIGHT = 720
    private const val MAX_BRIGHTNESS = 0.5f // of the brightest channel, 0..1

    /** Key for [showBackdrop]-style URL lists: "banner:<component>". */
    const val PREFIX = "banner:"

    fun keyFor(app: AppEntry) = PREFIX + app.component.flattenToString()

    /** Renders the backdrop for a "banner:" key; null if the app has no banner. Call off the main thread. */
    fun render(context: Context, key: String): Drawable? {
        val component = ComponentName.unflattenFromString(key.removePrefix(PREFIX)) ?: return null
        return try {
            val banner = loadBanner(context, component) ?: return null
            val wall = edgeColour(banner)
            val dim = min(1f, MAX_BRIGHTNESS / max(brightness(wall), 0.01f))

            val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.RGB_565) // opaque: half the memory
            val canvas = Canvas(bitmap)
            canvas.drawColor(scale(wall, dim))
            // The banner sits in the open area right of the title and above Continue watching
            // (the bottom fade covers most of the screen below that), starting below the top bar:
            // some banners are logo from edge to edge.
            val w = (WIDTH * 0.4f).roundToInt()
            val h = w * 9 / 16
            val left = (WIDTH * 0.70f).roundToInt() - w / 2
            val top = (HEIGHT * 0.31f).roundToInt() - h / 2
            banner.setBounds(left, top, left + w, top + h)
            if (dim < 1f) banner.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setScale(dim, dim, dim, 1f) })
            banner.draw(canvas)
            banner.colorFilter = null
            BitmapDrawable(context.resources, bitmap)
        } catch (e: Exception) {
            Log.w(TAG, "banner backdrop for $component failed: $e")
            null
        }
    }

    /** The banner at the highest density the app ships, so it stays sharp when drawn large. */
    private fun loadBanner(context: Context, component: ComponentName): Drawable? {
        val pm = context.packageManager
        val activity = pm.getActivityInfo(component, 0)
        val res = if (activity.banner != 0) activity.banner else activity.applicationInfo.banner
        if (res == 0) return null
        return pm.getResourcesForApplication(activity.applicationInfo)
            .getDrawableForDensity(res, DisplayMetrics.DENSITY_XXXHIGH, null)
            ?.mutate()
    }

    /** The average colour along the banner's border: its background, which the wall continues. */
    private fun edgeColour(banner: Drawable): Int {
        val small = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888)
        banner.setBounds(0, 0, 32, 18)
        banner.draw(Canvas(small))
        var r = 0L; var g = 0L; var b = 0L; var n = 0
        for (x in 0 until 32) for (y in 0 until 18) {
            if (x != 0 && x != 31 && y != 0 && y != 17) continue
            val c = small.getPixel(x, y)
            if (Color.alpha(c) < 128) continue
            r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++
        }
        small.recycle()
        return if (n == 0) Color.rgb(8, 9, 12) else Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    private fun brightness(c: Int) = max(Color.red(c), max(Color.green(c), Color.blue(c))) / 255f

    private fun scale(c: Int, f: Float) =
        Color.rgb((Color.red(c) * f).roundToInt(), (Color.green(c) * f).roundToInt(), (Color.blue(c) * f).roundToInt())
}
