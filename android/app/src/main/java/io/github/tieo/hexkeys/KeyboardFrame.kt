package io.github.tieo.hexkeys

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

/**
 * The keyboard view with the autofill service's suggestions laid over its
 * strip. The service's own button, which it marks pinned, sits in the
 * strip's right end slot and never scrolls away; the suggestions that fill
 * a field, accounts and passkeys, line up left of it and scroll when there
 * are more than fit. While any are shown they take the place of the words.
 */
@SuppressLint("ViewConstructor")
class KeyboardFrame(context: Context, val keyboard: KeyboardView, val emoji: EmojiPanel, val clipboard: ClipboardPanel) : FrameLayout(context) {

    private val chips = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val scroller = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(chips)
    }
    private val pinned = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
    private val gap = (4 * resources.displayMetrics.density).toInt()
    private val density = resources.displayMetrics.density

    /**
     * Searching emoji by name while the letters type the query: over the
     * strip a way back to the emoji and the query in a search field with
     * its cursor, and over the number row the emoji it finds, the recently
     * used until something is typed.
     */
    private val search = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
    }
    private val searchBack = android.widget.ImageView(context).apply {
        setImageResource(R.drawable.icon_arrow_back)
        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
    }
    private val searchBackspace = android.widget.ImageView(context).apply {
        setImageResource(R.drawable.icon_backspace)
        scaleType = android.widget.ImageView.ScaleType.CENTER_INSIDE
    }
    private var onSearchBackspace: () -> Unit = {}
    private val query = android.widget.TextView(context).apply {
        textSize = 17f
        maxLines = 1
        gravity = android.view.Gravity.CENTER_VERTICAL
        setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
        compoundDrawablePadding = (8 * density).toInt()
    }
    private val results = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = android.view.Gravity.CENTER_VERTICAL
    }
    private val resultScroller = HorizontalScrollView(context).apply {
        isHorizontalScrollBarEnabled = false
        addView(results)
        // Touches between the emoji must not reach the number keys underneath.
        isClickable = true
    }
    private var cursorShown = true
    private var queryText = ""
    private val blink = object : Runnable {
        override fun run() {
            cursorShown = !cursorShown
            paintQuery()
            postDelayed(this, 530)
        }
    }

    init {
        scroller.setOnScrollChangeListener { _, _, _, _, _ -> clipChips() }
        addView(keyboard)
        keyboard.onBarMoved = { requestLayout() }
        addView(scroller)
        addView(pinned)
        addView(emoji)
        emoji.visibility = GONE
        addView(clipboard)
        clipboard.visibility = GONE
        search.addView(searchBack, LinearLayout.LayoutParams(keyboard.stripSide, ViewGroup.LayoutParams.MATCH_PARENT))
        search.addView(query, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
            setMargins(0, gap, 0, gap)
        })
        // The results cover the number row, where deleting is; the query has its own backspace.
        search.addView(searchBackspace, LinearLayout.LayoutParams(keyboard.stripSide, ViewGroup.LayoutParams.MATCH_PARENT))
        repeatWhileHeld(searchBackspace) { onSearchBackspace() }
        addView(search)
        addView(resultScroller)
        search.visibility = GONE
        resultScroller.visibility = GONE
        show(emptyList())
    }

    val searching get() = search.visibility == VISIBLE

    /** Shows or hides the emoji search; `back` returns to the emoji, `backspace` takes the query's last letter. */
    fun showSearch(on: Boolean, back: () -> Unit = {}, backspace: () -> Unit = {}) {
        onSearchBackspace = backspace
        search.visibility = if (on) VISIBLE else GONE
        resultScroller.visibility = search.visibility
        // The results take the digits' place; the number row stays a bar right above the letters.
        keyboard.barAboveLetters = on
        keyboard.gestureBar = on || emojiShown || clipboardShown
        removeCallbacks(blink)
        if (on) {
            val theme = keyboard.theme
            search.setBackgroundColor(theme.background)
            resultScroller.setBackgroundColor(theme.background)
            searchBack.setColorFilter(theme.label)
            searchBackspace.setColorFilter(theme.label)
            searchBack.setOnClickListener { back() }
            query.setTextColor(theme.label)
            query.background = pill(context, theme.other)
            val magnifier = context.getDrawable(R.drawable.icon_search)!!.mutate()
            magnifier.setTint(theme.label)
            val size = (20 * density).toInt()
            magnifier.setBounds(0, 0, size, size)
            query.setCompoundDrawables(magnifier, null, null, null)
            showEmoji(false)
            post(blink)
        }
        requestLayout()
    }

    /** The query typed so far and the emoji it finds; tapping one picks it. */
    fun showResults(text: String, found: List<String>, pick: (String) -> Unit) {
        queryText = text
        cursorShown = true
        paintQuery()
        results.removeAllViews()
        for (emoji in found) {
            results.addView(android.widget.TextView(context).apply {
                this.text = emoji
                textSize = 28f
                gravity = android.view.Gravity.CENTER
                setPadding((8 * density).toInt(), 0, (8 * density).toInt(), 0)
                setOnClickListener { pick(emoji) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        resultScroller.scrollTo(0, 0)
    }

    /** The query with a cursor after it in the accent colour, blinking. */
    private fun paintQuery() {
        val text = android.text.SpannableStringBuilder(queryText).append("|")
        val color = if (cursorShown) keyboard.theme.accent else 0
        text.setSpan(android.text.style.ForegroundColorSpan(color), text.length - 1, text.length, 0)
        query.text = text
    }

    /** How wide the right end slot for the service's button can be. */
    val pinnedRoom get() = pinnedRoom(keyboard.measuredWidth)

    /**
     * Shows autofill suggestion views, each with whether the service pinned
     * it, which puts it in the right end slot; the rest line up from the left
     * and scroll. None hides them.
     */
    fun show(suggestions: List<Pair<View, Boolean>>) {
        chips.removeAllViews()
        pinned.removeAllViews()
        val marks = suggestions.map { it.second }
        for ((index, entry) in suggestions.withIndex()) {
            val view = entry.first
            val isPinned = marks[index]
            // The view comes sized to what the service rendered; keep that.
            val given = view.layoutParams
            val params = LinearLayout.LayoutParams(
                given?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                given?.height ?: ViewGroup.LayoutParams.MATCH_PARENT,
            )
            params.gravity = android.view.Gravity.CENTER_VERTICAL
            params.marginEnd = gap
            (if (isPinned) pinned else chips).addView(view, params)
        }
        val any = suggestions.isNotEmpty()
        scroller.visibility = if (chips.childCount > 0) VISIBLE else GONE
        pinned.visibility = if (pinned.childCount > 0) VISIBLE else GONE
        keyboard.chipsShown = any
        requestLayout()
    }

    /** Shows the emoji in place of the keys, or the keys again. */
    fun showEmoji(on: Boolean) {
        if (on) {
            emoji.searchable = keyboard.numbersHeight > 0
            emoji.apply(keyboard.theme)
            emoji.open()
            clipboard.visibility = GONE
        }
        fade(emoji, on)
        keyboard.gestureBar = searching || emojiShown || clipboardShown
        requestLayout()
    }

    val emojiShown get() = emoji.visibility == VISIBLE && emoji.tag != CLOSING

    /** Shows the texts copied in place of the keys, or the keys again. */
    fun showClipboard(on: Boolean) {
        if (on) {
            clipboard.apply(keyboard.theme)
            emoji.visibility = GONE
        }
        fade(clipboard, on)
        keyboard.gestureBar = searching || emojiShown || clipboardShown
        requestLayout()
    }

    val clipboardShown get() = clipboard.visibility == VISIBLE && clipboard.tag != CLOSING

    /**
     * Shows or hides a panel with a quick fade and a short rise, as other
     * keyboards switch views; one closing counts as closed at once.
     */
    private fun fade(panel: View, on: Boolean) {
        panel.animate().cancel()
        val rise = 12 * density
        if (on) {
            panel.tag = null
            if (panel.visibility != VISIBLE) {
                panel.alpha = 0f
                panel.translationY = rise
                panel.visibility = VISIBLE
            }
            panel.animate().alpha(1f).translationY(0f).setDuration(PANEL_MS).start()
        } else if (panel.visibility == VISIBLE) {
            panel.tag = CLOSING
            panel.animate().alpha(0f).translationY(rise).setDuration(PANEL_MS).withEndAction {
                panel.visibility = GONE
                panel.tag = null
            }.start()
        }
    }

    /** Where autofill's entries start: at the left end, over the settings and clipboard buttons, which give way to them. */
    private val chipsStart get() = gap

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        keyboard.measure(widthMeasureSpec, heightMeasureSpec)
        val keysTop = keyboard.headroom + keyboard.stripTall
        // The panels take the letters' place and leave the number row as a slim
        // bar for sliding to delete and to move the cursor.
        for (panel in listOf(emoji, clipboard)) {
            panel.measure(
                MeasureSpec.makeMeasureSpec(keyboard.measuredWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(
                    keyboard.measuredHeight - keysTop - keyboard.gestureBarHeight - keyboard.navigationBar, MeasureSpec.EXACTLY,
                ),
            )
        }
        val width = keyboard.measuredWidth
        val strip = MeasureSpec.makeMeasureSpec(maxOf(0, keyboard.stripTall - 2 * gap), MeasureSpec.EXACTLY)
        pinned.measure(MeasureSpec.makeMeasureSpec(width / 3, MeasureSpec.AT_MOST), strip)
        // Without a pinned entry the row runs to the right end.
        val pinnedWidth = if (pinned.childCount > 0) maxOf(pinned.measuredWidth, keyboard.stripSide) else gap
        scroller.measure(MeasureSpec.makeMeasureSpec(width - chipsStart - pinnedWidth, MeasureSpec.EXACTLY), strip)
        search.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(keyboard.stripTall, MeasureSpec.EXACTLY),
        )
        resultScroller.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(keyboard.numbersHeight - keyboard.gestureBarHeight, MeasureSpec.EXACTLY),
        )
        setMeasuredDimension(width, keyboard.measuredHeight)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        keyboard.layout(0, 0, keyboard.measuredWidth, keyboard.measuredHeight)
        val stripTop = keyboard.headroom + gap
        val width = keyboard.measuredWidth
        pinned.layout(width - pinned.measuredWidth, stripTop, width, stripTop + pinned.measuredHeight)
        val start = chipsStart
        val keysTop = keyboard.headroom + keyboard.stripTall
        val panelTop = keysTop + keyboard.gestureBarHeight
        emoji.layout(0, panelTop, width, panelTop + emoji.measuredHeight)
        clipboard.layout(0, panelTop, width, panelTop + clipboard.measuredHeight)
        scroller.layout(start, stripTop, start + scroller.measuredWidth, stripTop + scroller.measuredHeight)
        search.layout(0, keyboard.headroom, width, keyboard.headroom + search.measuredHeight)
        resultScroller.layout(0, keysTop, width, keysTop + resultScroller.measuredHeight)
        post { clipChips() }
    }

    /**
     * Autofill suggestions are drawn on surfaces of their own, which the
     * scroller does not clip; each is cut to the part inside it by hand.
     */
    private fun clipChips() {
        for (i in 0 until chips.childCount) {
            val chip = chips.getChildAt(i)
            val left = chip.left - scroller.scrollX
            val from = maxOf(0, -left)
            val to = minOf(chip.width, scroller.width - left)
            chip.clipBounds = if (to > from) Rect(from, 0, to, chip.height) else Rect()
        }
    }

    companion object {
        /** How wide the right end slot for the service's button can be on a keyboard `width` wide. */
        fun pinnedRoom(width: Int) = width / 3

        /**
         * How wide one suggestion left of that slot can be, on a keyboard
         * `width` wide: all the room from the left end, where the settings
         * and clipboard buttons give way, to the slot, so none is cut.
         */
        fun chipRoom(width: Int, density: Float): Int = width - pinnedRoom(width) - (8 * density).toInt()

        /** How long a panel takes to fade in or out, in ms. */
        const val PANEL_MS = 120L

        /** Marks a panel fading out. */
        const val CLOSING = "closing"
    }
}
