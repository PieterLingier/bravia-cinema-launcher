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
import android.view.KeyEvent
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
    private lateinit var cwHeader: View
    private lateinit var cwLabel: TextView
    private lateinit var cwSourceLabel: TextView
    private lateinit var cwHeader2: View
    private lateinit var cwLabel2: TextView
    private lateinit var cwSourceLabel2: TextView
    private lateinit var appsLabel: TextView
    private lateinit var cwRow: HorizontalGridView
    private lateinit var cwRow2: HorizontalGridView
    private lateinit var appsGrid: VerticalGridView // one Continue watching row: apps in rows of seven
    private lateinit var appsRow: HorizontalGridView // two Continue watching rows: one row of apps

    private lateinit var cwAdapter: CwAdapter
    private lateinit var cw2Adapter: CwAdapter
    private lateinit var appsAdapter: AppsAdapter

    /** Two Continue watching rows on screen (and the apps in one row). */
    private var twoRows = false

    /** Whichever of appsGrid and appsRow is showing the apps. */
    private val appsView: BaseGridView
        get() = if (twoRows) appsRow else appsGrid

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
    private var moving: String? = null // package of the app being moved
    private var okPressed = false
    private var orderBeforeMove: List<AppEntry> = emptyList()
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
        cwHeader = findViewById(R.id.cwHeader)
        cwLabel = findViewById(R.id.cwLabel)
        cwSourceLabel = findViewById(R.id.cwSource)
        cwHeader2 = findViewById(R.id.cwHeader2)
        cwLabel2 = findViewById(R.id.cwLabel2)
        cwSourceLabel2 = findViewById(R.id.cwSource2)
        appsLabel = findViewById(R.id.appsLabel)
        cwRow = findViewById(R.id.cwRow)
        cwRow2 = findViewById(R.id.cwRow2)
        appsGrid = findViewById(R.id.appsGrid)
        appsRow = findViewById(R.id.appsRow)

        val grotesk = ResourcesCompat.getFont(this, R.font.space_grotesk)
        heroTitle.typeface = grotesk
        heroTitle.setFontVariationSettings("'wght' 700")
        findViewById<TextClock>(R.id.time).apply {
            typeface = grotesk
            setFontVariationSettings("'wght' 500")
        }

        cwAdapter = CwAdapter(onFocus = { showCard(it, row = 0) }, onClick = ::openCard)
        cwRow.adapter = cwAdapter
        cwRow.itemAnimator = null // its add/change animations would cancel the focus zoom
        cwRow.horizontalSpacing = dp(14)
        alignRow(cwRow, dp(48 + 180 + 14))

        cw2Adapter = CwAdapter(onFocus = { showCard(it, row = 1) }, onClick = ::openCard).apply { compact = true }
        cwRow2.adapter = cw2Adapter
        cwRow2.itemAnimator = null
        cwRow2.horizontalSpacing = dp(14)
        alignRow(cwRow2, dp(48 + 144 + 14))

        appsRow.itemAnimator = null
        appsRow.horizontalSpacing = dp(12)
        alignRow(appsRow, dp(48 + 112 + 12))

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

        showLoading()
    }

    override fun onResume() {
        super.onResume()
        playbackJob?.cancel() // back on the home screen: stop nudging Plex
        loadApps()
        loadArt()
        refreshContinueWatching()
    }

    override fun onStart() {
        super.onStart()
        inFront = true
    }

    override fun onStop() {
        super.onStop()
        inFront = false
        endMove(keep = false) // screen off or another app in front mid-move: put the app back
        stopSignal?.complete(Unit) // another app (Plex) is now on screen
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        endMove(keep = false) // Home while moving an app: cancel the move
        // Home pressed while already on the home screen: back to the start of Continue watching.
        if (hasWindowFocus()) {
            cwRow.selectedPosition = 0
            if (twoRows) cwRow2.selectedPosition = 0
            appsView.selectedPosition = 0
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

    /**
     * The apps the Continue watching rows come from, top row first: the user's pick (Plex, Netflix,
     * or both), Plex if those are gone.
     */
    private fun cwSources(): List<String> =
        prefs.cwSources.filter { it in CW_SOURCES && isInstalled(it) }.distinct().take(2)
            .ifEmpty { listOf(PlexClient.PLEX_PACKAGE) }

    private fun isInstalled(pkg: String): Boolean =
        try {
            packageManager.getApplicationInfo(pkg, 0).enabled
        } catch (e: Exception) {
            false
        }

    private fun sourceName(pkg: String): String =
        getString(if (pkg == NETFLIX_PACKAGE) R.string.netflix else R.string.plex)

    /** Loads every Continue watching row (one per source), each shown as soon as it's in. */
    private fun refreshContinueWatching() {
        cwJob?.cancel()
        val sources = cwSources()
        applySections(two = sources.size == 2)
        cwSourceLabel.text = getString(R.string.from_app, sourceName(sources[0]))
        sources.getOrNull(1)?.let { cwSourceLabel2.text = getString(R.string.from_app, sourceName(it)) }
        cwJob = scope.launch {
            sources.forEachIndexed { row, source ->
                launch {
                    showCards(row, cardsFor(source))
                    // Plex's backdrop comes from Continue watching.
                    focusedApp?.takeIf { it.packageName == PlexClient.PLEX_PACKAGE && appsView.hasFocus() }?.let(::showApp)
                }
            }
        }
    }

    private suspend fun cardsFor(source: String): List<CwCard> {
        val name = sourceName(source)
        val nothing = { listOf(CwCard.Action(CwCard.Kind.OPEN_SOURCE, getString(R.string.nothing_title), getString(R.string.nothing_message, name), source)) }
        if (source == PlexClient.PLEX_PACKAGE) {
            return when (val state = withContext(Dispatchers.IO) { plex.continueWatching() }) {
                is CwState.NotLinked -> listOf(action(CwCard.Kind.LINK, R.string.link_title, getString(R.string.link_message)))
                is CwState.Failed -> listOf(action(CwCard.Kind.RETRY, R.string.unreachable_title, state.message))
                is CwState.Loaded -> if (state.items.isEmpty()) nothing() else state.items.map { CwCard.Item(it) }
            }
        }
        // Apps other than Plex: what they publish for the TV home screen (see TvArt).
        val items = withContext(Dispatchers.IO) { tvArt.continueWatching(source) }
        return when {
            items == null -> listOf(CwCard.Action(CwCard.Kind.GRANT, getString(R.string.grant_title, name), getString(R.string.grant_message)))
            items.isEmpty() -> nothing()
            else -> items.map { CwCard.Item(it) }
        }
    }

    private fun showCards(row: Int, cards: List<CwCard>) {
        val view = if (row == 0) cwRow else cwRow2
        val adapter = if (row == 0) cwAdapter else cw2Adapter
        val rowHadFocus = view.hasFocus()
        adapter.cards = cards
        if (row == 0 && !placedInitialFocus) {
            placedInitialFocus = true
            cwRow.selectedPosition = 0
            cwRow.requestFocus()
            cwRow.post { if (!cwRow.hasFocus()) cwRow.requestFocus() }
        }
        if (rowHadFocus || view.hasFocus()) {
            cards.getOrNull(view.selectedPosition.coerceIn(0, cards.lastIndex))?.let { showCard(it, row) }
        }
    }

    /** Loading placeholders in every Continue watching row, while they reload. */
    private fun showLoading() {
        val loading = listOf(action(CwCard.Kind.LOADING, R.string.loading, getString(R.string.loading_message)))
        showCards(0, loading)
        if (twoRows) showCards(1, loading)
    }

    /**
     * One Continue watching row: tiles of 180x102 dp, apps in rows of seven (the positions in
     * activity_home.xml). Two rows: both with 144x81 dp tiles, and the apps in one row that
     * scrolls sideways, in the space of the second app row.
     */
    private fun applySections(two: Boolean) {
        if (two == twoRows) return
        twoRows = two
        fun View.place(topDp: Int, heightDp: Int? = null) = updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = dp(topDp)
            if (heightDp != null) height = dp(heightDp)
        }
        if (two) {
            cwHeader.place(188)
            cwRow.place(208, 81 + 16)
            cwHeader2.place(303)
            cwRow2.place(323, 81 + 16)
            appsLabel.place(418)
            appsRow.place(438, 63 + 16)
            appsGrid.adapter = null // an adapter can only serve one view
            appsRow.adapter = appsAdapter
        } else {
            cwHeader.place(192)
            cwRow.place(214, 102 + 16)
            appsLabel.place(340)
            appsGrid.place(362, 152)
            appsRow.adapter = null
            appsGrid.adapter = appsAdapter
            cw2Adapter.cards = emptyList()
        }
        cwAdapter.compact = two
        alignRow(cwRow, dp(48 + (if (two) 144 else 180) + 14))
        val second = if (two) View.VISIBLE else View.GONE
        cwHeader2.visibility = second
        cwRow2.visibility = second
        appsRow.visibility = second
        appsGrid.visibility = if (two) View.GONE else View.VISIBLE
    }

    private fun loadApps(focus: String? = null) {
        if (moving != null) return // reloaded when the move ends
        appsJob?.cancel()
        appsJob = scope.launch {
            val row = withContext(Dispatchers.IO) { apps.homeRow() }
            appsAdapter.apps = row
            if (focus != null) {
                val index = row.indexOfFirst { it.packageName == focus }
                if (index >= 0) {
                    appsView.selectedPosition = index
                    appsView.requestFocus()
                }
            }
        }
    }

    /** The artwork apps publish for the TV home screen, refreshed each time Cinema comes back. */
    private fun loadArt() {
        artJob?.cancel()
        artJob = scope.launch {
            appArt = withContext(Dispatchers.IO) { tvArt.load() }
            focusedApp?.takeIf { appsView.hasFocus() }?.let(::showApp)
        }
    }

    /** Brightens the label of the focused section: 0 and 1 the Continue watching rows, 2 the apps. */
    private fun highlight(section: Int) {
        cwLabel.setTextColor(getColor(if (section == 0) R.color.text else R.color.text_muted))
        cwLabel2.setTextColor(getColor(if (section == 1) R.color.text else R.color.text_muted))
        appsLabel.setTextColor(getColor(if (section == 2) R.color.text else R.color.text_muted))
    }

    // ---- hero ----

    private fun showCard(card: CwCard, row: Int) {
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
        highlight(row)
    }

    private fun showApp(app: AppEntry) {
        focusedApp = app
        setHero(app.label, if (app.packageName == moving) getString(R.string.move_hint) else null, null, null)
        showBackdrop(artFor(app), glowColor = accentOf(app))
        highlight(2)
    }

    /**
     * Plex: the artwork of what you'd resume. Other apps: what they publish for the TV home screen.
     * Then, for all of them, a backdrop made from the app's banner.
     */
    private fun artFor(app: AppEntry): List<String> {
        val art = if (app.packageName == PlexClient.PLEX_PACKAGE) {
            (cwAdapter.cards + cw2Adapter.cards)
                .mapNotNull { (it as? CwCard.Item)?.item?.takeIf { i -> i.app == app.packageName }?.artUrl }.take(1)
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
            is CwCard.Item -> if (card.item.app == PlexClient.PLEX_PACKAGE) openInPlex(card.item) else openInApp(card.item)
            is CwCard.Action -> when (card.kind) {
                CwCard.Kind.LINK -> startActivity(Intent(this, PlexLinkActivity::class.java))
                CwCard.Kind.RETRY -> {
                    showLoading()
                    refreshContinueWatching()
                }
                CwCard.Kind.OPEN_SOURCE -> launchPackage(card.app ?: cwSources().first())
                CwCard.Kind.GRANT, CwCard.Kind.LOADING -> Unit
            }
        }
    }

    /**
     * A Watch Next item (Netflix…): its own link, as the app published it for the TV home screen,
     * which resumes it there. The link may only open that same app; if it fails, the app opens.
     */
    private fun openInApp(item: CwItem) {
        val intent = try {
            Intent.parseUri(item.deepLink, Intent.URI_INTENT_SCHEME)
        } catch (e: Exception) {
            null
        }
        if (intent != null) {
            if (intent.component?.packageName.let { it != null && it != item.app }) intent.component = null
            intent.selector = null
            intent.setPackage(item.app)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "${item.app} has no screen for its Watch Next link")
            } catch (e: Exception) {
                Log.w(TAG, "Watch Next link failed: ${e.javaClass.simpleName}")
            }
        }
        launchPackage(item.app)
    }

    /**
     * Plex quirks, measured on this TV: a Plex *started* by a link ignores that link and every later
     * one for minutes. A cold-started Plex shows "Can't reach server" for a link at 6 s, and plays it
     * cleanly at 9 s; it then takes 5-17 s until playback starts, and a new link in that time
     * restarts it with an "Item not known" error. So: open Plex normally, send the link (with the
     * resume offset) once Plex says it's ready (PlexWatch, via the accessibility service; without
     * it, at 9 s), and only retry if the server reports nothing playing 25 s later.
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
        val launchedUptime = SystemClock.uptimeMillis() // PlexWatch's clock
        startSafely(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

        playbackJob?.cancel()
        playbackJob = scope.launch {
            val toScreen = withTimeoutOrNull(6_000) {
                plexOnScreen.await()
                SystemClock.elapsedRealtime() - launched
            } ?: 6_000L
            // Wait for Plex to say it's ready (see PlexWatch), never longer than READY_MAX_MS. If
            // none of its events arrive at all (service off), fall back to the fixed wait.
            var ready: Long? = null
            if (PlexWatch.connected) {
                ready = withTimeoutOrNull((READY_MAX_MS - (SystemClock.elapsedRealtime() - launched)).coerceAtLeast(0)) {
                    while (PlexWatch.readyAt <= launchedUptime) {
                        val silent = PlexWatch.lastEventAt <= launchedUptime
                        if (silent && SystemClock.uptimeMillis() - launchedUptime > NO_EVENTS_MS) return@withTimeoutOrNull -1L
                        delay(100)
                    }
                    PlexWatch.readyAt - launchedUptime
                }
            }
            if (ready != null && ready >= 0) {
                Log.i(TAG, "Plex on screen after $toScreen ms, ready after $ready ms")
                delay(READY_MARGIN_MS)
            } else if (ready == null && PlexWatch.connected) {
                Log.i(TAG, "Plex on screen after $toScreen ms, no ready signal in $READY_MAX_MS ms")
            } else {
                // No accessibility service: a fixed wait, enough for a cold start.
                Log.i(TAG, "Plex on screen after $toScreen ms; first link at $FIRST_LINK_MS ms")
                delay((FIRST_LINK_MS - (SystemClock.elapsedRealtime() - launched)).coerceAtLeast(0))
            }
            val start = SystemClock.elapsedRealtime()
            var nextLink = 0L
            var nextCheck = 1500L
            var links = 0
            var canCheck = true
            // One link, then wait: Plex can take 5-17 s to start playing, and a new link in that
            // time restarts it ("Item not known…" errors). Retry once if nothing plays by then.
            while (true) {
                val elapsed = SystemClock.elapsedRealtime() - start
                if (elapsed > GIVE_UP_MS || links >= MAX_LINKS && elapsed >= nextLink || !canCheck && links >= 1) {
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
                    nextLink = elapsed + RETRY_AFTER_MS
                }
                if (canCheck && elapsed >= nextCheck) {
                    val at = withContext(Dispatchers.IO) { plex.playingAt(item.ratingKey) }
                    if (at == null) {
                        canCheck = false // e.g. a shared server: no way to confirm, keep the one link
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
        val pkg = app.packageName
        val sources = cwSources()
        // Plex and Netflix can each feed a Continue watching row, alone or one row each.
        val rowChoices: List<Pair<String, () -> Unit>> = when {
            pkg !in CW_SOURCES -> emptyList()
            pkg !in sources -> listOf(
                getString(R.string.use_for_cw) to { setCwSources(listOf(pkg), pkg) },
                getString(R.string.add_cw_row) to { setCwSources(sources + pkg, pkg) },
            )
            sources.size == 2 -> listOfNotNull(
                (getString(R.string.cw_row_first) to { setCwSources(listOf(pkg) + (sources - pkg), pkg) }).takeIf { sources[1] == pkg },
                getString(R.string.remove_cw_row) to { setCwSources(sources - pkg, pkg) },
            )
            else -> emptyList() // the only row: switch from the other app
        }
        val choices: List<Pair<String, () -> Unit>> = rowChoices + listOf(
            getString(R.string.move) to { startMove(app) },
            getString(R.string.remove_from_row) to {
                apps.remove(pkg)
                loadApps()
            },
        )
        AlertDialog.Builder(this, R.style.Theme_Cinema_Dialog)
            .setTitle(app.label)
            .setItems(choices.map { it.first }.toTypedArray()) { _, which -> choices[which].second() }
            .show()
    }

    /** Sets the Continue watching rows (one or two apps, top first), keeping focus on [focus]'s tile. */
    private fun setCwSources(sources: List<String>, focus: String) {
        val before = cwSources()
        prefs.cwSources = sources
        val message = if (sources.size == 2) {
            getString(R.string.cw_two_rows, sourceName(sources[0]), sourceName(sources[1]))
        } else {
            getString(R.string.cw_switched, sourceName(sources[0]))
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        refreshContinueWatching() // lays the screen out for one or two rows
        // A placeholder in the rows whose app changed; the others keep their tiles while they reload.
        val loading = listOf(action(CwCard.Kind.LOADING, R.string.loading, getString(R.string.loading_message)))
        sources.forEachIndexed { row, source -> if (before.getOrNull(row) != source) showCards(row, loading) }
        loadApps(focus = focus) // the apps may have moved between the grid and the single row
    }

    // ---- moving an app ----

    /** Lifts [app]: the arrows carry it through the grid, OK puts it down there, Back cancels. */
    private fun startMove(app: AppEntry) {
        appsJob?.cancel() // a reload mustn't reorder the row under the user
        moving = app.packageName
        okPressed = false
        orderBeforeMove = appsAdapter.apps
        appsAdapter.lifted = app.packageName
        focusLifted(appsAdapter.apps.indexOfFirst { it.packageName == app.packageName })
        showApp(app)
    }

    /** Keeps focus on the lifted tile (after the menu closes, focus could land elsewhere). */
    private fun focusLifted(index: Int) {
        if (index < 0) return
        appsView.selectedPosition = index
        if (!appsView.hasFocus()) appsView.requestFocus()
    }

    private fun endMove(keep: Boolean) {
        val pkg = moving ?: return
        moving = null
        appsAdapter.lifted = null
        if (keep) {
            apps.setRow(appsAdapter.apps.map { it.packageName })
        } else {
            appsAdapter.apps = orderBeforeMove
        }
        appsAdapter.apps.firstOrNull { it.packageName == pkg }?.let(::showApp) // drops the hint
        loadApps(focus = pkg)
    }

    /**
     * One step of the lifted app: Left/Right one place (wrapping across rows), Up/Down one row.
     * In the single row of apps (two Continue watching rows), only Left/Right.
     */
    private fun stepMove(pkg: String, keyCode: Int) {
        val from = appsAdapter.apps.indexOfFirst { it.packageName == pkg }
        if (from < 0) return
        val last = appsAdapter.itemCount - 1
        val to = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> from - 1
            KeyEvent.KEYCODE_DPAD_RIGHT -> from + 1
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (twoRows) from else stepRow(from, last, keyCode)
            else -> from
        }
        if (to !in 0..last || to == from) {
            focusLifted(from)
            return
        }
        appsAdapter.moveItem(from, to)
        focusLifted(to)
    }

    private fun stepRow(from: Int, last: Int, keyCode: Int): Int =
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> from - APP_COLUMNS
            // Down from a row above a shorter last row: to the end of the row.
            KeyEvent.KEYCODE_DPAD_DOWN ->
                if (from + APP_COLUMNS <= last) from + APP_COLUMNS
                else if (from / APP_COLUMNS < last / APP_COLUMNS) last
                else from
            else -> from
        }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val pkg = moving ?: return super.dispatchKeyEvent(event)
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN) stepMove(pkg, event.keyCode)
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A -> {
                // Place on release, and only for a press that started in move mode (not the
                // release of the OK that picked "Move" in the menu).
                if (event.action == KeyEvent.ACTION_DOWN) okPressed = true
                else if (event.action == KeyEvent.ACTION_UP && okPressed) endMove(keep = true)
            }
            KeyEvent.KEYCODE_BACK -> if (event.action == KeyEvent.ACTION_UP) endMove(keep = false)
            else -> return super.dispatchKeyEvent(event) // volume, media keys…
        }
        return true
    }

    companion object {
        /** Whether Cinema's home screen is on screen (between onStart and onStop). */
        @Volatile var inFront = false
            private set

        private const val TAG = "Cinema"
        private const val APP_COLUMNS = 7 // 7 x 112 dp tiles + 6 x 12 dp gaps fit the 864 dp between the margins
        private const val FIRST_LINK_MS = 9_000L // without the ready signal; cold start: a link at 6 s errors, at 9 s it plays
        private const val READY_MAX_MS = 15_000L // longest wait for Plex's ready signal (cold: 7-11 s)
        private const val NO_EVENTS_MS = 4_000L // no Plex events by then: they aren't coming, use FIRST_LINK_MS
        private const val READY_MARGIN_MS = 500L // after the signal (warm: a link 1 s after the window plays)
        private const val RETRY_AFTER_MS = 25_000L // playback can take up to ~17 s to start; don't interrupt it
        private const val MAX_LINKS = 2
        private const val GIVE_UP_MS = 60_000L
        private const val ACTION_VIEW_INPUTS = "com.android.tv.action.VIEW_INPUTS"
        private const val LIVE_TV_PACKAGE = "com.sony.dtv.tvx"
        private const val NETFLIX_PACKAGE = "com.netflix.ninja"
        private val CW_SOURCES = setOf(PlexClient.PLEX_PACKAGE, NETFLIX_PACKAGE) // apps that can feed Continue watching
    }
}
