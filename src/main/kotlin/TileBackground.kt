package local.cinema.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * How to draw an app tile whose banner doesn't fill it the way Netflix's or YouTube's does.
 *
 * - **A logo on a see-through background**: drawn as is, it floats on the dark tile. It gets a plain
 *   fill behind it.
 * - **A logo on a plain grey background** (SmartTube's banner is its red logo on #343434): the grey
 *   reads as an empty tile next to the others. The app's icon is drawn large on a plain fill instead.
 *
 * The fill is white behind dark or colourful logos (as Netflix and YouTube do) and near-black behind
 * light ones (as Plex does). Every other banner is left alone ([forApp] returns null).
 */
object TileBackground {

    /** [image] fills the tile ([fit] false, like a banner) or sits centred in it ([fit] true, an icon), on [fill]. */
    class Style(val image: Drawable, val fill: Int, val fit: Boolean)

    private const val W = 64
    private const val H = 36

    /** Below this share of opaque pixels along the border, the banner has no background of its own. */
    private const val OPAQUE_BORDER = 0.5f

    /** A border whose pixels all stay this close (0..255, per channel) to their average is plain. */
    private const val PLAIN_SPREAD = 10

    /** A plain border counts as grey when its channels differ by at most this much (0..255)... */
    private const val GREY_SPREAD = 16

    /** ...and its brightness (0..1) lies in this range: not black like Plex's, not white like Netflix's. */
    private const val GREY_MIN = 0.12f
    private const val GREY_MAX = 0.45f

    /** Logos lighter than this (relative luminance, 0..1) get the dark fill, the rest white. */
    private const val LIGHT_LOGO = 0.5

    private val WHITE = Color.rgb(245, 245, 245)
    private val DARK = Color.rgb(8, 9, 12)

    /** The style for an app's tile, or null to draw its banner as it comes. */
    fun forApp(banner: Drawable, icon: Drawable?): Style? {
        val pixels = render(banner)
        val border = borderOf(pixels)
        val opaque = border.filter { Color.alpha(it) >= 128 }
        return when {
            opaque.size < border.size * OPAQUE_BORDER ->
                fillFor(pixels)?.let { Style(banner, it, fit = false) }
            icon != null && isPlainGrey(opaque) ->
                fillFor(render(icon))?.let { Style(icon, it, fit = true) }
            else -> null
        }
    }

    /** [d] drawn at W×H, as ARGB pixels; the drawable's own bounds are put back afterwards. */
    private fun render(d: Drawable): IntArray {
        val bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val bounds = d.copyBounds()
        try {
            d.setBounds(0, 0, W, H)
            d.draw(Canvas(bitmap))
            return IntArray(W * H).also { bitmap.getPixels(it, 0, W, 0, 0, W, H) }
        } finally {
            d.setBounds(bounds)
            bitmap.recycle()
        }
    }

    private fun borderOf(pixels: IntArray): List<Int> =
        (0 until W * H).filter { i -> val x = i % W; val y = i / W; x == 0 || y == 0 || x == W - 1 || y == H - 1 }
            .map { pixels[it] }

    /** Whether every border pixel is (nearly) the same neutral, mid-dark grey. */
    private fun isPlainGrey(border: List<Int>): Boolean {
        if (border.isEmpty()) return false
        val r = border.sumOf { Color.red(it) } / border.size
        val g = border.sumOf { Color.green(it) } / border.size
        val b = border.sumOf { Color.blue(it) } / border.size
        val plain = border.all {
            abs(Color.red(it) - r) <= PLAIN_SPREAD && abs(Color.green(it) - g) <= PLAIN_SPREAD &&
                abs(Color.blue(it) - b) <= PLAIN_SPREAD
        }
        val brightness = max(r, max(g, b)) / 255f
        return plain && max(r, max(g, b)) - min(r, min(g, b)) <= GREY_SPREAD && brightness in GREY_MIN..GREY_MAX
    }

    /** White or near-black, whichever suits the logo in [pixels] (its opaque part); null if it's empty. */
    private fun fillFor(pixels: IntArray): Int? {
        var weight = 0.0
        var luminance = 0.0
        for (c in pixels) {
            val alpha = Color.alpha(c) / 255.0
            if (alpha > 0) {
                luminance += alpha * relativeLuminance(c)
                weight += alpha
            }
        }
        if (weight == 0.0) return null
        return if (luminance / weight > LIGHT_LOGO) DARK else WHITE
    }

    /** WCAG relative luminance of an sRGB colour, 0 (black) to 1 (white). */
    private fun relativeLuminance(c: Int): Double {
        fun channel(v: Int): Double {
            val s = v / 255.0
            return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(Color.red(c)) + 0.7152 * channel(Color.green(c)) + 0.0722 * channel(Color.blue(c))
    }
}
