package io.github.tieo.hexkeys

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * Emoji in place of the keys: every group in one list that scrolls on, the
 * recently used first, each under its name; above it the search button and
 * an icon per group, the one in view marked, which jumps to its group; at
 * the bottom the way back to the letters, space and backspace. Only emoji
 * the phone's font draws are offered.
 */
@SuppressLint("ViewConstructor")
class EmojiPanel(
    context: Context,
    private val onEmoji: (String) -> Unit,
    private val onSpace: () -> Unit,
    private val onBackspace: () -> Unit,
    private val onClose: () -> Unit,
    private val onSearch: () -> Unit,
) : LinearLayout(context) {

    private val density = resources.displayMetrics.density
    private fun dp(value: Int) = (value * density).toInt()

    /** The groups as name and emoji, in Unicode's order. */
    private val groups: List<Pair<String, List<String>>>

    /** The list's rows: a group's name, or up to [COLUMNS] of its emoji. */
    private sealed interface Row {
        val group: Int
        data class Name(override val group: Int, val name: String) : Row
        data class Emoji(override val group: Int, val emoji: List<String>) : Row
    }
    private var rows: List<Row> = emptyList()

    /** Where each group's name row is, the recently used being group 0. */
    private var starts: List<Int> = emptyList()

    /** The group marked in the bar: the one at the top of the list. */
    private var marked = 0

    private val bar = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val list = ListView(context).apply {
        divider = null
        dividerHeight = 0
        isVerticalScrollBarEnabled = false
        selector = GradientDrawable()
    }
    private val bottom = PanelKeys(context, onClose, onSpace, onBackspace)
    private var theme = Theme.current(context)

    init {
        orientation = VERTICAL
        // Touches between the panel's controls must not reach the keys underneath.
        isClickable = true
        val paint = Paint()
        groups = context.assets.open("emoji.tsv").bufferedReader().readLines().mapNotNull { line ->
            val (name, emoji) = line.split('\t').takeIf { it.size == 2 } ?: return@mapNotNull null
            name to emoji.split(' ').filter { paint.hasGlyph(it) }
        }
        // One inset and one gap throughout, circles and pills for the controls.
        setPadding(dp(PANEL_INSET), dp(PANEL_INSET), dp(PANEL_INSET), dp(PANEL_INSET))
        addView(bar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(PANEL_CONTROL)))
        addView(list, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(PANEL_INSET / 2) })
        addView(bottom, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(PANEL_CONTROL)).apply { topMargin = dp(PANEL_INSET / 2) })
        list.setOnScrollListener(object : AbsListView.OnScrollListener {
            override fun onScrollStateChanged(view: AbsListView, state: Int) {}
            override fun onScroll(view: AbsListView, first: Int, visible: Int, total: Int) {
                val group = rows.getOrNull(first)?.group ?: return
                if (group != marked) {
                    marked = group
                    markBar()
                }
            }
        })
        apply(theme)
    }

    /** Whether there is a number row for the search's results to take the place of. */
    var searchable = true
        set(value) {
            if (field == value) return
            field = value
            if (rows.isNotEmpty()) buildBar()
        }

    /** Follows the keyboard's theme; the same theme again changes nothing. */
    fun apply(theme: Theme) {
        if (theme == this.theme && rows.isNotEmpty()) return
        this.theme = theme
        builtRecent = null
        setBackgroundColor(theme.background)
        bottom.apply(theme)
        buildBar()
        open()
    }

    /** The recently used the rows were built with; the rows are built again only when they changed. */
    private var builtRecent: List<String>? = null

    /** The app's language the rows' group names were written in. */
    private var builtLanguage = ""

    /** Opens at the top: the recently used, or the first group when there are none. */
    fun open() {
        if (recent() != builtRecent || AppLocale.current(context) != builtLanguage) buildRows()
        list.setSelection(0)
        marked = rows.firstOrNull()?.group ?: 0
        markBar()
    }

    /** The recently used, most recent first. */
    fun recent(): List<String> =
        Dictionaries.preferences(context).getString(RECENT, "").orEmpty().split(' ').filter { it.isNotEmpty() }

    /** Puts an emoji first among the recently used. */
    fun remember(emoji: String) {
        val list = (listOf(emoji) + recent().filter { it != emoji }).take(RECENT_COUNT)
        Dictionaries.preferences(context).edit().putString(RECENT, list.joinToString(" ")).apply()
    }

    /** A group's name in the app's language, as the emoji list names it in English. */
    private fun groupTitle(name: String): String {
        val id = when (name) {
            "Smileys & Emotion" -> R.string.emoji_smileys
            "People & Body" -> R.string.emoji_people
            "Animals & Nature" -> R.string.emoji_animals
            "Food & Drink" -> R.string.emoji_food
            "Travel & Places" -> R.string.emoji_travel
            "Activities" -> R.string.emoji_activities
            "Objects" -> R.string.emoji_objects
            "Symbols" -> R.string.emoji_symbols
            "Flags" -> R.string.emoji_flags
            else -> return name
        }
        return AppLocale.text(context, id)
    }

    private fun buildRows() {
        builtRecent = recent()
        builtLanguage = AppLocale.current(context)
        val all = listOf(AppLocale.text(context, R.string.emoji_recent) to builtRecent.orEmpty()) + groups.map { (name, emoji) -> groupTitle(name) to emoji }
        val built = mutableListOf<Row>()
        val at = mutableListOf<Int>()
        for ((group, entry) in all.withIndex()) {
            val (name, emoji) = entry
            at += built.size
            if (emoji.isEmpty()) continue
            built += Row.Name(group, name)
            emoji.chunked(COLUMNS).forEach { built += Row.Emoji(group, it) }
        }
        rows = built
        starts = at
        list.adapter = Rows()
    }

    /**
     * The search button, then a button per group with its icon. Without a
     * number row there is no search: its results take the number row's place.
     */
    private fun buildBar() {
        bar.removeAllViews()
        val search = ImageView(context).apply {
            setImageResource(R.drawable.icon_search)
            setColorFilter(theme.label)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            background = pill(theme.other)
            setOnClickListener { onSearch() }
        }
        if (searchable) bar.addView(search, LayoutParams(dp(PANEL_CONTROL * 2), ViewGroup.LayoutParams.MATCH_PARENT).apply { marginEnd = dp(PANEL_INSET / 2) })
        for ((group, icon) in GROUP_ICONS.withIndex()) {
            val mark = ImageView(context).apply {
                setImageResource(icon)
                scaleType = ImageView.ScaleType.CENTER_INSIDE
                setPadding(dp(9), dp(9), dp(9), dp(9))
            }
            val cell = FrameLayout(context)
            cell.addView(mark, FrameLayout.LayoutParams(dp(PANEL_CONTROL - 4), dp(PANEL_CONTROL - 4), Gravity.CENTER))
            cell.setOnClickListener {
                // A group with no emoji, the recently used before any, has none to show.
                val start = starts.getOrNull(group) ?: return@setOnClickListener
                if (start < rows.size && rows[start].group == group) list.setSelection(start)
            }
            bar.addView(cell, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        markBar()
    }

    /** The group in view in a filled circle, the others plain and dimmer. */
    private fun markBar() {
        for (group in GROUP_ICONS.indices) {
            val cell = bar.getChildAt(group + 1) as? FrameLayout ?: continue
            val mark = cell.getChildAt(0) as ImageView
            val chosen = group == marked
            mark.setColorFilter(theme.label)
            mark.alpha = if (chosen) 1f else 0.55f
            mark.background = if (chosen) GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(theme.other)
            } else null
        }
    }

    private fun pill(color: Int) = pill(context, color)

    private inner class Rows : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(position: Int) = if (rows[position] is Row.Name) 0 else 1
        override fun isEnabled(position: Int) = false

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View = when (val row = rows[position]) {
            is Row.Name -> (convertView as? TextView ?: TextView(context).apply {
                textSize = 13f
                setPadding(dp(PANEL_INSET / 2), dp(10), 0, dp(4))
            }).apply {
                text = row.name
                setTextColor(theme.label)
                alpha = 0.6f
            }
            is Row.Emoji -> (convertView as? LinearLayout ?: LinearLayout(context).apply {
                orientation = HORIZONTAL
                repeat(COLUMNS) {
                    addView(TextView(context).apply {
                        textSize = 26f
                        gravity = Gravity.CENTER
                        minHeight = dp(48)
                    }, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                }
            }).apply {
                for (i in 0 until COLUMNS) {
                    val cell = getChildAt(i) as TextView
                    val emoji = row.emoji.getOrNull(i)
                    cell.text = emoji.orEmpty()
                    cell.setOnClickListener(emoji?.let { e ->
                        View.OnClickListener {
                            remember(e)
                            onEmoji(e)
                        }
                    })
                    cell.isClickable = emoji != null
                }
            }
        }
    }

    private companion object {
        const val COLUMNS = 8
        const val RECENT = "recent_emoji"
        const val RECENT_COUNT = 32


        /** An icon for the recently used and for each group of emoji.tsv, in its order. */
        val GROUP_ICONS = listOf(
            R.drawable.icon_schedule, R.drawable.icon_mood, R.drawable.icon_emoji_people, R.drawable.icon_pets,
            R.drawable.icon_restaurant, R.drawable.icon_directions_car, R.drawable.icon_sports_soccer,
            R.drawable.icon_lightbulb, R.drawable.icon_emoji_symbols, R.drawable.icon_flag,
        )
    }
}

/**
 * Makes `view` act once when touched and again and again while held, as a
 * held backspace deletes on: after a long press's wait, every
 * [HELD_REPEAT_MS].
 */
@SuppressLint("ClickableViewAccessibility")
fun repeatWhileHeld(view: View, action: () -> Unit) {
    val repeat = object : Runnable {
        override fun run() {
            action()
            view.postDelayed(this, HELD_REPEAT_MS)
        }
    }
    view.setOnTouchListener { touched, event ->
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                touched.isPressed = true
                action()
                touched.postDelayed(repeat, android.view.ViewConfiguration.getLongPressTimeout().toLong())
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                touched.isPressed = false
                touched.removeCallbacks(repeat)
            }
        }
        true
    }
}

/** Pause between deletions while a backspace button is held, in ms, as on the keyboard's own. */
private const val HELD_REPEAT_MS = 60L
