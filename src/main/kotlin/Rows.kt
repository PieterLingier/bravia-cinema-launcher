package local.cinema.launcher

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import kotlin.math.roundToInt

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

/** Grows a focused tile and lifts it a little, as in the mockup. */
fun animateFocus(view: View, focused: Boolean, scale: Float) {
    view.animate()
        .scaleX(if (focused) scale else 1f)
        .scaleY(if (focused) scale else 1f)
        .translationZ(if (focused) view.resources.displayMetrics.density * 10f else 0f)
        .setDuration(140)
        .start()
}

private fun View.clipToRoundedBackground() {
    outlineProvider = ViewOutlineProvider.BACKGROUND
    clipToOutline = true
}

/** A recycled tile can still carry the zoom of an earlier focus: match it to the current state. */
private fun resetFocusLook(view: View, scale: Float) {
    val focused = view.isFocused
    view.animate().cancel()
    view.scaleX = if (focused) scale else 1f
    view.scaleY = if (focused) scale else 1f
    view.translationZ = if (focused) view.resources.displayMetrics.density * 10f else 0f
}

/** A tile in the Continue watching row: a Plex item, or a single action (link, retry…). */
sealed class CwCard {
    data class Item(val item: CwItem) : CwCard()
    data class Action(val kind: Kind, val title: String, val message: String) : CwCard()

    enum class Kind { LOADING, LINK, RETRY, OPEN_PLEX }
}

class CwAdapter(
    private val onFocus: (CwCard) -> Unit,
    private val onClick: (CwCard) -> Unit,
) : RecyclerView.Adapter<CwAdapter.Holder>() {

    init {
        setHasStableIds(true)
    }

    var cards: List<CwCard> = emptyList()
        set(value) {
            if (value == field) return
            field = value
            notifyDataSetChanged()
        }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val image: ImageView = view.findViewById(R.id.cwImage)
        val badge: TextView = view.findViewById(R.id.cwBadge)
        val action: TextView = view.findViewById(R.id.cwAction)
        val progress: ProgressBar = view.findViewById(R.id.cwProgress)
    }

    override fun getItemCount() = cards.size

    override fun getItemId(position: Int): Long = when (val card = cards[position]) {
        is CwCard.Item -> card.item.ratingKey.hashCode().toLong()
        is CwCard.Action -> -1L - card.kind.ordinal
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_cw, parent, false)
        view.clipToRoundedBackground()
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val card = cards[position]
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            animateFocus(v, hasFocus, 1.08f)
            if (hasFocus) onFocus(card)
        }
        resetFocusLook(holder.itemView, 1.08f)
        holder.itemView.setOnClickListener { onClick(card) }
        when (card) {
            is CwCard.Item -> {
                val item = card.item
                holder.action.visibility = View.GONE
                holder.badge.visibility = View.VISIBLE
                holder.badge.text = item.badge
                holder.progress.visibility = if (item.progress > 0f) View.VISIBLE else View.GONE
                holder.progress.progress = (item.progress * 1000).roundToInt()
                holder.image.load(item.thumbUrl) {
                    crossfade(true)
                    size(400, 225)
                }
                holder.itemView.contentDescription =
                    listOf(item.title, item.subtitle, item.timeLeft).filter { it.isNotBlank() }.joinToString(", ")
            }
            is CwCard.Action -> {
                holder.image.dispose()
                holder.image.setImageDrawable(null)
                holder.badge.visibility = View.GONE
                holder.progress.visibility = View.GONE
                holder.action.visibility = View.VISIBLE
                holder.action.text = card.title
                holder.itemView.contentDescription = card.title
            }
        }
    }
}

class AppsAdapter(
    private val onFocus: (AppEntry) -> Unit,
    private val onClick: (AppEntry) -> Unit,
    private val onLongClick: (AppEntry) -> Unit,
    private val tileWidthDp: Int = 112,
    private val tileHeightDp: Int = 63,
    private val focusScale: Float = 1.1f,
) : RecyclerView.Adapter<AppsAdapter.Holder>() {

    init {
        setHasStableIds(true)
    }

    var apps: List<AppEntry> = emptyList()
        set(value) {
            val same = value.map { it.packageName } == field.map { it.packageName }
            field = value
            if (!same) notifyDataSetChanged()
        }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val banner: ImageView = view.findViewById(R.id.appBanner)
        val fallback: LinearLayout = view.findViewById(R.id.appFallback)
        val icon: ImageView = view.findViewById(R.id.appIcon)
        val name: TextView = view.findViewById(R.id.appName)
    }

    override fun getItemCount() = apps.size

    override fun getItemId(position: Int): Long = apps[position].packageName.hashCode().toLong()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        view.layoutParams = view.layoutParams.apply {
            width = parent.context.dp(tileWidthDp)
            height = parent.context.dp(tileHeightDp)
        }
        view.clipToRoundedBackground()
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val app = apps[position]
        holder.itemView.contentDescription = app.label
        holder.itemView.setOnFocusChangeListener { v, hasFocus ->
            animateFocus(v, hasFocus, focusScale)
            if (hasFocus) onFocus(app)
        }
        resetFocusLook(holder.itemView, focusScale)
        holder.itemView.setOnClickListener { onClick(app) }
        holder.itemView.setOnLongClickListener {
            onLongClick(app)
            true
        }
        if (app.banner != null) {
            holder.banner.visibility = View.VISIBLE
            holder.banner.setImageDrawable(app.banner)
            holder.fallback.visibility = View.GONE
        } else {
            holder.banner.visibility = View.GONE
            holder.fallback.visibility = View.VISIBLE
            holder.icon.setImageDrawable(app.icon)
            holder.name.text = app.label
        }
    }
}
