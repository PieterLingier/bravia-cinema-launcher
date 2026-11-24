package local.cinema.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextClock
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.drawable.toBitmap
import androidx.core.view.updateLayoutParams
import androidx.leanback.widget.BaseGridView
import androidx.leanback.widget.HorizontalGridView
import androidx.leanback.widget.VerticalGridView
import androidx.palette.graphics.Palette
import coil.drawable.CrossfadeDrawable
import coil.imageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import coil.size.Scale
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/** The home screen (mockup A "Cinema"): a hero for the focused item, Continue watching, two rows of apps. */
class HomeActivity : Activity() {

    private val scope = MainScope()
    private lateinit var prefs: Prefs
    private lateinit var plex: PlexClient
    private lateinit var apps: AppRepo
    private lateinit var tvArt: TvArt

    private lateinit var backdrop: ImageView
    private lateinit var glow: View
    private lateinit var heroTitle: TextView
    private lateinit var heroMeta: TextView
    private lateinit var heroProgressRow: View
    private lateinit var heroProgress: ProgressBar
    private lateinit var heroLeft: TextView
    private lateinit var cwLabel: TextView
    private lateinit var appsLabel: TextView
    private lateinit var cwRow: HorizontalGridView
    private lateinit var appsGrid: VerticalGridView

    private lateinit var cwAdapter: CwAdapter
    private lateinit var appsAdapter: AppsAdapter

    private var cwJob: Job? = null
    private var appsJob: Job? = null
    private var artJob: Job? = null
    private var backdropJob: Job? = null
    private var playbackJob: Job? = null
    private var stopSignal: CompletableDeferred<Unit>? = null
    private var placedInitialFocus = false
    private val accents = HashMap<String, Int>()

    private var appArt: Map<String, List<String>> = emptyMap() // package -> backdrop images, best first
    private var focusedApp: AppEntry? = null
    private var backdropUrl: String? = null // the image the backdrop holds, shown or not
    private var backdropShown = false
    private val notLandscape = HashSet<String>()
    private val bannerBackdrops = HashMap<String, Drawable>() // 1280x720 each; only apps without artwork use them

    private val packageChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            loadApps()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)
        prefs = Prefs(this)
        plex = PlexClient(prefs)
        apps = AppRepo(this, prefs)
        tvArt = TvArt(this)

        backdrop = findViewById(R.id.backdrop)
        glow = findViewById(R.id.glow)
        heroTitle = findViewById(R.id.heroTitle)
        heroMeta = findViewById(R.id.heroMeta)
        heroProgressRow = findViewById(R.id.heroProgressRow)
        heroProgress = findViewById(R.id.heroProgress)
        heroLeft = findViewById(R.id.heroLeft)
        cwLabel = findViewById(R.id.cwLabel)
        appsLabel = findViewById(R.id.appsLabel)
        cwRow = findViewById(R.id.cwRow)
        appsGrid = findViewById(R.id.appsGrid)

        val grotesk = ResourcesCompat.getFont(this, R.font.space_grotesk)
        heroTitle.typeface = grotesk
        heroTitle.setFontVariationSettings("'wght' 700")
        findViewById<TextClock>(R.id.time).apply {
            typeface = grotesk
            setFontVariationSettings("'wght' 500")
        }

        cwAdapter = CwAdapter(onFocus = ::showCard, onClick = ::openCard)
        cwRow.adapter = cwAdapter
        cwRow.itemAnimator = null // its add/change animations would cancel the focus zoom
        cwRow.horizontalSpacing = dp(14)
        alignRow(cwRow, dp(48 + 180 + 14))

        appsAdapter = AppsAdapter(onFocus = ::showApp, onClick = { apps.launch(this, it) }, onLongClick = ::editApp)
        appsGrid.adapter = appsAdapter
        appsGrid.itemAnimator = null
        appsGrid.setNumColumns(APP_COLUMNS)
        appsGrid.horizontalSpacing = dp(12)
        appsGrid.verticalSpacing = dp(10)
        // Two rows fit; moving down past them scrolls one row at a time and keeps the focused row
        // in the lower slot (the top row stays put until then).
        appsGrid.windowAlignment = BaseGridView.WINDOW_ALIGN_BOTH_EDGE
        appsGrid.windowAlignmentOffsetPercent = BaseGridView.WINDOW_ALIGN_OFFSET_PERCENT_DISABLED
        appsGrid.windowAlignmentOffset = dp(8 + 63 + 10)
        appsGrid.itemAlignmentOffsetPercent = 0f
        appsGrid.itemAlignmentOffset = 0
        // Left/Right stop at the ends of both rows: see focusOut* in activity_home.xml.

        val inputs = findViewById<ImageButton>(R.id.btnInputs)
        val allApps = findViewById<ImageButton>(R.id.btnAllApps)
        val settings = findViewById<ImageButton>(R.id.btnSettings)
        listOf(inputs, allApps, settings).forEach { button ->
            button.setOnFocusChangeListener { v, hasFocus -> animateFocus(v, hasFocus, 1.1f) }
            button.nextFocusDownId = R.id.cwRow
        }
        // The top bar ends at Inputs and Settings: don't let Left/Right jump into the rows.
        inputs.nextFocusLeftId = inputs.id
        settings.nextFocusRightId = settings.id
        inputs.setOnClickListener { openInputs() }
        allApps.setOnClickListener { startActivity(Intent(this, AllAppsActivity::class.java)) }
        settings.setOnClickListener { startSafely(Intent(Settings.ACTION_SETTINGS)) }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageChanges, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        showCards(listOf(action(CwCard.Kind.LOADING, R.string.loading, getString(R.string.loading_message))))
    }

    override fun onResume() {
        super.onResume()
        playbackJob?.cancel() // back on the home screen: stop nudging Plex
        loadApps()
        loadArt()
        refreshContinueWatching()
    }

    override fun onStop() {
        super.onStop()
        stopSignal?.complete(Unit) // another app (Plex) is now on screen
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while already on the home screen: back to the start of Continue watching.
        if (hasWindowFocus()) {
            cwRow.selectedPosition = 0
            appsGrid.selectedPosition = 0
            cwRow.requestFocus()
        }
    }

    @Deprecated("A launcher has nothing to go back to; Back returns to the first row.")
    override fun onBackPressed() {
        cwRow.selectedPosition = 0
        cwRow.requestFocus()
    }

    override fun onDestroy() {
        unregisterReceiver(packageChanges)
        scope.cancel()
        super.onDestroy()
    }

    // ---- rows ----

    private fun alignRow(row: HorizontalGridView, focusX: Int) {
        // Keep the focused tile one tile in from the left edge once the row starts scrolling.
        row.windowAlignment = BaseGridView.WINDOW_ALIGN_BOTH_EDGE
        row.windowAlignmentOffsetPercent = BaseGridView.WINDOW_ALIGN_OFFSET_PERCENT_DISABLED
        row.windowAlignmentOffset = focusX
        row.itemAlignmentOffsetPercent = 0f
        row.itemAlignmentOffset = 0
    }

    private fun action(kind: CwCard.Kind, title: Int, message: String) = CwCard.Action(kind, getString(title), message)

    private fun refreshContinueWatching() {
        cwJob?.cancel()
        cwJob = scope.launch {
            val state = withContext(Dispatchers.IO) { plex.continueWatching() }
            val cards = when (state) {
                is CwState.NotLinked -> listOf(action(CwCard.Kind.LINK, R.string.link_title, getString(R.string.link_message)))
                is CwState.Failed -> listOf(action(CwCard.Kind.RETRY, R.string.unreachable_title, state.message))
                is CwState.Loaded ->
                    if (state.items.isEmpty()) {
                        listOf(action(CwCard.Kind.OPEN_PLEX, R.string.nothing_title, getString(R.string.nothing_message)))
                    } else {
                        state.items.map { CwCard.Item(it) }
                    }
            }
            showCards(cards)
            // Plex's backdrop comes from Continue watching.
            focusedApp?.takeIf { it.packageName == PlexClient.PLEX_PACKAGE && appsGrid.hasFocus() }?.let(::showApp)
        }
    }

    private fun showCards(cards: List<CwCard>) {
        val rowHadFocus = cwRow.hasFocus()
        cwAdapter.cards = cards
        if (!placedInitialFocus) {
            placedInitialFocus = true
            cwRow.selectedPosition = 0
            cwRow.requestFocus()
            cwRow.post { if (!cwRow.hasFocus()) cwRow.requestFocus() }
        }
        if (rowHadFocus || cwRow.hasFocus()) {
            cards.getOrNull(cwRow.selectedPosition.coerceIn(0, cards.lastIndex))?.let { showCard(it) }
        }
    }

    private fun loadApps(focus: String? = null) {
        appsJob?.cancel()
        appsJob = scope.launch {
            val row = withContext(Dispatchers.IO) { apps.homeRow() }
            appsAdapter.apps = row
            if (focus != null) {
                val index = row.indexOfFirst { it.packageName == focus }
                if (index >= 0) {
                    appsGrid.selectedPosition = index
                    appsGrid.requestFocus()
                }
            }
        }
    }

    /** The artwork apps publish for the TV home screen, refreshed each time Cinema comes back. */
    private fun loadArt() {
        artJob?.cancel()
        artJob = scope.launch {
            appArt = withContext(Dispatchers.IO) { tvArt.load() }
            focusedApp?.takeIf { appsGrid.hasFocus() }?.let(::showApp)
        }
    }

    // ---- hero ----

    private fun showCard(card: CwCard) {
        focusedApp = null
        when (card) {
            is CwCard.Item -> {
                val item = card.item
                setHero(item.title, item.subtitle, item.progress, item.timeLeft)
                showBackdrop(listOfNotNull(item.artUrl), glowColor = null)
            }
            is CwCard.Action -> {
                setHero(card.title, card.message, null, null)
                showBackdrop(emptyList(), glowColor = null)
            }
        }
        cwLabel.setTextColor(getColor(R.color.text))
        appsLabel.setTextColor(getColor(R.color.text_muted))
    }

    private fun showApp(app: AppEntry) {
        focusedApp = app
        setHero(app.label, null, null, null)
        showBackdrop(artFor(app), glowColor = accentOf(app))
        cwLabel.setTextColor(getColor(R.color.text_muted))
        appsLabel.setTextColor(getColor(R.color.text))
    }

    /**
     * Plex: the artwork of what you'd resume. Other apps: what they publish for the TV home screen.
     * Then, for all of them, a backdrop made from the app's banner.
     */
    private fun artFor(app: AppEntry): List<String> {
        val art = if (app.packageName == PlexClient.PLEX_PACKAGE) {
            cwAdapter.cards.mapNotNull { (it as? CwCard.Item)?.item?.artUrl }.take(1)
        } else {
            appArt[app.packageName].orEmpty()
        }
        return art + BannerArt.keyFor(app)
    }

    private fun setHero(title: String, meta: String?, progress: Float?, left: String?) {
        heroTitle.text = title
        heroMeta.text = meta.orEmpty()
        heroMeta.visibility = if (meta.isNullOrBlank()) View.GONE else View.VISIBLE
        if (!left.isNullOrBlank()) {
            // Not started yet: show only the running time, without an empty bar.
            val started = (progress ?: 0f) > 0f
            heroProgressRow.visibility = View.VISIBLE
            heroProgress.visibility = if (started) View.VISIBLE else View.GONE
            heroProgress.progress = ((progress ?: 0f) * 1000).roundToInt()
            // setLayoutParams (not just mutating them) so the start margin is resolved again.
            heroLeft.updateLayoutParams<ViewGroup.MarginLayoutParams> { marginStart = if (started) dp(10) else 0 }
            heroLeft.text = left
        } else {
            heroProgressRow.visibility = View.GONE
        }
    }

    /**
     * The full-screen image behind the hero: the first of [urls] that loads as a landscape image,
     * cross-faded in once loaded. With none, the backdrop fades out and [glowColor] (an app's own
     * colour), if given, lights the screen instead. An app's glow also shows while its image
     * loads, unless another backdrop is still on screen.
     */
    private fun showBackdrop(urls: List<String>, glowColor: Int?) {
        backdropJob?.cancel()
        val candidates = urls.filterNot { it in notLandscape }
        if (candidates.isEmpty()) {
            hideBackdrop()
            if (glowColor != null) showGlow(glowColor) else hideGlow()
            return
        }
        if (candidates.first() == backdropUrl) {
            revealBackdrop()
            return
        }
        if (glowColor != null && !backdropShown) showGlow(glowColor) else hideGlow()
        backdropJob = scope.launch {
            delay(150) // don't fetch every backdrop while the user scrolls past
            for (url in candidates) {
                val image = loadBackdrop(url) ?: continue
                val previous = backdrop.drawable
                if (backdropShown && previous != null) {
                    val fade: Drawable = CrossfadeDrawable(previous, image, Scale.FILL, durationMillis = 300)
                    backdrop.setImageDrawable(fade)
                    (fade as Animatable).start()
                } else {
                    backdrop.setImageDrawable(image)
                }
                backdropUrl = url
                revealBackdrop()
                return@launch
            }
            hideBackdrop()
            if (glowColor != null) showGlow(glowColor)
        }
    }

    /** Loads [url] for the backdrop: null if it fails, or isn't landscape (a portrait poster would crop badly). */
    private suspend fun loadBackdrop(url: String): Drawable? {
        if (url.startsWith(BannerArt.PREFIX)) {
            bannerBackdrops[url]?.let { return it }
            val made = withContext(Dispatchers.Default) { BannerArt.render(this@HomeActivity, url) }
            if (made != null) bannerBackdrops[url] = made
            return made
        }
        val request = ImageRequest.Builder(this)
            .data(url)
            .size(1600, 900)
            .precision(Precision.INEXACT)
            .build()
        val result = imageLoader.execute(request)
        val image = (result as? SuccessResult)?.drawable
        // Log only scheme and host: image URLs can carry account tokens.
        if (image == null) {
            Log.i(TAG, "backdrop from ${TvArt.hostOf(url)} failed: ${(result as? ErrorResult)?.throwable?.javaClass?.simpleName}")
            return null
        }
        if (image.intrinsicWidth < image.intrinsicHeight * 1.2f) {
            notLandscape += url
            Log.i(TAG, "backdrop from ${TvArt.hostOf(url)} is ${image.intrinsicWidth}x${image.intrinsicHeight}: not landscape")
            return null
        }
        Log.i(TAG, "backdrop ${image.intrinsicWidth}x${image.intrinsicHeight} from ${TvArt.hostOf(url)}")
        return image
    }

    private fun revealBackdrop() {
        backdropShown = true
        backdrop.animate().alpha(1f).setDuration(250).start()
        hideGlow()
    }

    private fun hideBackdrop() {
        backdropShown = false
        backdrop.animate().alpha(0f).setDuration(250).start()
    }

    private fun showGlow(color: Int) {
        glow.background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(ColorUtils.setAlphaComponent(color, 150), Color.TRANSPARENT)
            gradientRadius = resources.displayMetrics.widthPixels * 0.55f
            setGradientCenter(0.78f, 0.32f)
        }
        glow.animate().alpha(1f).setDuration(250).start()
    }

    private fun hideGlow() {
        glow.animate().alpha(0f).setDuration(250).start()
    }

    /** The app's own colour, taken from its banner, for the glow behind the hero. */
    private fun accentOf(app: AppEntry): Int = accents.getOrPut(app.packageName) {
        val art = app.banner ?: app.icon
        if (art == null) {
            getColor(R.color.accent)
        } else {
            try {
                val palette = Palette.from(art.toBitmap(96, 54)).generate()
                palette.getVibrantColor(palette.getDominantColor(getColor(R.color.accent)))
            } catch (e: Exception) {
                getColor(R.color.accent)
            }
        }
    }

    // ---- actions ----

    private fun openCard(card: CwCard) {
        when (card) {
            is CwCard.Item -> openInPlex(card.item)
            is CwCard.Action -> when (card.kind) {
                CwCard.Kind.LINK -> startActivity(Intent(this, PlexLinkActivity::class.java))
                CwCard.Kind.RETRY -> {
                    showCards(listOf(action(CwCard.Kind.LOADING, R.string.loading, getString(R.string.loading_message))))
                    refreshContinueWatching()
                }
                CwCard.Kind.OPEN_PLEX -> launchPackage(PlexClient.PLEX_PACKAGE)
                CwCard.Kind.LOADING -> Unit
            }
        }
    }

    /**
     * Plex quirks, measured on this TV: a Plex *started* by a link ignores that link and every later
     * one for minutes; a Plex started normally ignores links for its first seconds, then follows
     * them; and extra links while it's loading do no harm. So: open Plex normally, wait, then send
     * the link (with the resume offset) every 3 s until the server reports the item playing.
     * Coming back to the home screen stops this (onResume). Sending links while Plex is in front
     * needs "display over other apps" (README).
     */
    private fun openInPlex(item: CwItem) {
        val launch = packageManager.getLeanbackLaunchIntentForPackage(PlexClient.PLEX_PACKAGE)
            ?: packageManager.getLaunchIntentForPackage(PlexClient.PLEX_PACKAGE)
        if (launch == null) {
            Toast.makeText(this, getString(R.string.cant_open, getString(R.string.plex)), Toast.LENGTH_SHORT).show()
            return
        }
        val link = Intent(Intent.ACTION_VIEW, Uri.parse(item.deepLink))
            .setPackage(PlexClient.PLEX_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val plexOnScreen = CompletableDeferred<Unit>().also { stopSignal = it }
        val launched = SystemClock.elapsedRealtime()
        startSafely(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

        playbackJob?.cancel()
        playbackJob = scope.launch {
            // A cold-starting Plex must first read its own start intent (a few seconds after its
            // window appears); a link that arrives before that becomes its start link, and then it
            // ignores links altogether. Whether Plex was already running can't be told reliably
            // from here (its window appears after ~0.7 s either way), so always wait.
            val toScreen = withTimeoutOrNull(6_000) {
                plexOnScreen.await()
                SystemClock.elapsedRealtime() - launched
            } ?: 6_000L
            Log.i(TAG, "Plex on screen after $toScreen ms; first link at $FIRST_LINK_MS ms")
            delay((FIRST_LINK_MS - (SystemClock.elapsedRealtime() - launched)).coerceAtLeast(0))
            val start = SystemClock.elapsedRealtime()
            var nextLink = 0L
            var nextCheck = 1500L
            var links = 0
            var canCheck = true
            while (true) {
                val elapsed = SystemClock.elapsedRealtime() - start
                if (elapsed > 36_000 || (!canCheck && links >= 4)) {
                    Log.i(TAG, "stopped sending ${item.ratingKey} after $links link(s), ${elapsed} ms")
                    return@launch
                }
                if (elapsed >= nextLink) {
                    try {
                        startActivity(link)
                    } catch (e: Exception) {
                        Log.w(TAG, "link failed: $e")
                        return@launch
                    }
                    links++
                    nextLink = elapsed + 3_000
                }
                if (canCheck && elapsed >= nextCheck) {
                    val at = withContext(Dispatchers.IO) { plex.playingAt(item.ratingKey) }
                    if (at == null) {
                        canCheck = false // e.g. a shared server: send a few links, then stop
                    } else if (at >= 0) {
                        Log.i(TAG, "playback of ${item.ratingKey} confirmed at ${at / 1000} s (asked for ${item.resumeSeconds} s) " +
                            "after ${SystemClock.elapsedRealtime() - start} ms, $links link(s)")
                        return@launch
                    }
                    nextCheck = SystemClock.elapsedRealtime() - start + 1_500
                }
                delay(200)
            }
        }
    }

    private fun openInputs() {
        val intent = Intent(ACTION_VIEW_INPUTS)
        val handlers = packageManager.queryIntentActivities(intent, 0)
            .map { it.activityInfo }
            .filter { it.packageName != packageName }
        // Prefer Sony's own input picker over the stock launcher's.
        val pick = handlers.firstOrNull { it.packageName == "com.android.systemui" } ?: handlers.firstOrNull()
        if (pick != null) {
            startSafely(intent.setClassName(pick.packageName, pick.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            launchPackage(LIVE_TV_PACKAGE)
        }
    }

    private fun launchPackage(pkg: String) {
        val intent = packageManager.getLeanbackLaunchIntentForPackage(pkg) ?: packageManager.getLaunchIntentForPackage(pkg)
        if (intent == null) {
            Toast.makeText(this, getString(R.string.cant_open, pkg), Toast.LENGTH_SHORT).show()
            return
        }
        startSafely(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun startSafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            val what = intent.component?.packageName ?: intent.action.orEmpty()
            Toast.makeText(this, getString(R.string.cant_open, what), Toast.LENGTH_SHORT).show()
        }
    }

    private fun editApp(app: AppEntry) {
        val choices = arrayOf(getString(R.string.move_left), getString(R.string.move_right), getString(R.string.remove_from_row))
        AlertDialog.Builder(this, R.style.Theme_Cinema_Dialog)
            .setTitle(app.label)
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> apps.move(app.packageName, -1)
                    1 -> apps.move(app.packageName, 1)
                    else -> apps.remove(app.packageName)
                }
                loadApps(focus = if (which == 2) null else app.packageName)
            }
            .show()
    }

    private companion object {
        const val TAG = "Cinema"
        const val APP_COLUMNS = 7 // 7 x 112 dp tiles + 6 x 12 dp gaps fit the 864 dp between the margins
        const val FIRST_LINK_MS = 5_500L // measured: a cold-started Plex is safe to link after ~4 s
        const val ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS"
        const val LIVE_TV_PACKAGE = "com.sony.dtv.tvx"
    }
}
