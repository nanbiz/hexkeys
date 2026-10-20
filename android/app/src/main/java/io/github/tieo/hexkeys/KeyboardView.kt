package io.github.tieo.hexkeys

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import org.json.JSONArray
import org.json.JSONObject

/**
 * Draws the keys the core lays out and passes touches back to it.
 *
 * Outlines come from the core and tile the keyboard; each is drawn with a
 * stroke in the background colour, which reads as the gap between keys
 * without changing where a touch lands.
 *
 * Above the keys runs the suggestion strip: three words, the best in the
 * middle; tapping one hands it to [onPick] with how many characters before
 * the cursor it replaces.
 *
 * The view is taller than strip and keys by the core's headroom, a
 * transparent band on top where a long-press popup over the top row can
 * appear. The input method reports only strip and keys as its content and
 * touchable area, so the band neither pushes the app up nor takes its touches.
 */
@SuppressLint("ViewConstructor")
class KeyboardView(
    context: Context,
    private val onAction: (kind: String, text: String) -> Unit,
    private val onPick: (word: String, replaces: Int) -> Unit,
    private val onSettings: () -> Unit,
    private val onClipboard: () -> Unit,
    private val onPasswords: () -> Unit,
    private val onVoice: () -> Unit,
    private val onHeld: (send: Boolean) -> Unit = {},
    private val onFreshClip: () -> Unit = {},
) : View(context) {

    private var handle = 0L
    private var paths: List<Path> = emptyList()

    /** Height of the transparent band above the strip. */
    var headroom = 0
        private set

    private val density = resources.displayMetrics.density
    private val fullStrip = (STRIP_DP * density).toInt()

    /** Landscape leaves the strip out for the keys' height, as it does the number row. */
    private val landscape get() = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    private val stripHeight get() = if (landscape) 0 else fullStrip
    private var slots = arrayOf("", "", "")
    private var replaces = 0

    /** What autocorrect would put in place of the word being typed, with how many code points it replaces. */
    var correction: Pair<String, Int>? = null

    /** The obvious fix of the typed word, such as its capital, with what it replaces. */
    var fix: Pair<String, Int>? = null
        private set

    /** The word being typed when no dictionary has it, which learning counts. */
    var unknown: String? = null
        private set
    /** Width of the strip's end slots: the settings button left, the autofill service's button right. */
    val stripSide get() = fullStrip + (4 * density).toInt()

    /** Height of the suggestion strip. */
    val stripTall get() = stripHeight

    /** What holding a letter offers, as the core's `lexiconAccents` gives it. */
    var accents = ""
        set(value) {
            field = value
            if (handle != 0L) Core.setAccents(handle, value)
        }

    /** Speech input's state: listening since [listeningSince], and stretches still being recognised. */
    var listening = false
        set(value) {
            val starting = value && !field
            field = value
            if (starting) {
                listeningSince = SystemClock.uptimeMillis()
                levels.clear()
            }
            invalidate()
        }
    var recognising = 0
        set(value) {
            field = value
            invalidate()
        }

    /** A text just copied, offered in the strip to paste; empty for none. */
    var freshClip = ""
        set(value) {
            field = value
            invalidate()
        }

    /** Dictated text no field took, shown with a button to put it in and one to drop it. */
    var heldText = ""
        set(value) {
            field = value
            invalidate()
        }
    private var listeningSince = 0L

    /** The loudness of the latest tenths of a second, the newest last, for the waveform. */
    private val levels = ArrayDeque<Float>()

    fun voiceLevel(level: Float) {
        levels.addLast(level)
        while (levels.size > LEVELS) levels.removeFirst()
        invalidate()
    }

    /** What the delete slide under way has taken, shown in the strip while it lasts. */
    var deletedText = ""
        set(value) {
            field = value
            invalidate()
        }

    /**
     * The languages on the space bar as short codes, the one being written
     * first, and whether it was picked rather than guessed.
     */
    var spaceLanguages = emptyList<String>()
        set(value) {
            field = value
            invalidate()
        }
    /** Space keys on the layer shown, and how many of them this frame has drawn. */
    private var spaceKeys = 0
    private var spacesDrawn = 0

    /** How wide each space key is drawn, left to right, which the language codes on it must fit. */
    private var spaceWidths = FloatArray(0)
    private val spaceBounds = RectF()

    /** Whether the first of [spaceLanguages] is known to be the one written. */
    var spaceInUse = false

    var spacePinned = false
        set(value) {
            field = value
            invalidate()
        }

    /** Enter's icon, from the field's action. */
    var enterIcon = R.drawable.icon_keyboard_return
        set(value) {
            field = value
            invalidate()
        }

    /** Whether a password manager is set up, which the button at the strip's right end opens. */
    var passwordsAvailable = false
        set(value) {
            field = value
            invalidate()
        }

    /** The password manager's own app icon, for its button; the key icon without one. */
    var passwordIcon: android.graphics.drawable.Drawable? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Autofill chips cover the words, which then are not drawn. */
    var chipsShown = false
        set(value) {
            field = value
            invalidate()
        }

    /** The slot a finger went down on, or -1 when the touch is not in the strip. */
    private var stripTouch = -1

    /** Height of the number row, which emoji search covers with what it finds. */
    val numbersHeight get() = if (handle != 0L) Core.numbersBottom(handle).toInt() else 0

    /** Where the keys start, below headroom and strip. */
    private val keysTop get() = headroom + stripHeight

    /** Room below the keys for the navigation bar, which the window draws under. */
    var navigationBar = 0
        private set

    private val backgroundPaint = Paint()
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val popupPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f * density
        strokeJoin = Paint.Join.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    /** The small sign on a punctuation key for what flicking it types. */
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }
    private val stripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 17 * resources.displayMetrics.scaledDensity
    }
    private val dividerPaint = Paint().apply {
        strokeWidth = density
    }
    private val deletePaint = Paint()
    private val cursorPaint = Paint()
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
    }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    var theme: Theme = Theme.current(context)
        set(value) {
            field = value
            applyTheme()
            invalidate()
        }

    private fun applyTheme() {
        backgroundPaint.color = theme.background
        // The gap between keys is the background showing through.
        gapPaint.color = theme.background
        popupPaint.color = theme.popup
        selectedPaint.color = theme.accent
        labelPaint.color = theme.label
        hintPaint.color = theme.label
        hintPaint.alpha = 0xA8
        stripPaint.color = theme.label
        dividerPaint.color = theme.other
        trailPaint.color = theme.accent
        trailPaint.alpha = 0xB0
    }

    /** A key's colour: its letter's QWERTZ row colour, or the colour of other keys. */
    private fun keyColor(index: Int): Int {
        val row = Core.keyRow(handle, index)
        return if (row in theme.rows.indices) theme.rows[row] else theme.other
    }

    /** Calls the core back when a hold it is timing has lasted long enough. */
    private val tick = Runnable {
        if (handle != 0L) act(Core.tick(handle, SystemClock.uptimeMillis()))
    }

    init {
        applyTheme()
        setOnApplyWindowInsetsListener { _, insets ->
            fitNavigationBar(insets)
            insets
        }
    }

    /**
     * Keeps room below the keys for the navigation bar. Below Android 11 the
     * input method window ends above it. From 11 on the navigation bar inset
     * covers only the gesture handle; the back and keyboard switch buttons the
     * system draws for an input method need the taller tappable inset. Both
     * count even while the app typed in hides the navigation bar: the system
     * draws the bar over the keyboard all the same.
     */
    private fun fitNavigationBar(insets: WindowInsets) {
        val bottom = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = WindowInsets.Type.navigationBars()
            val tappable = WindowInsets.Type.tappableElement()
            val sizes = listOf(
                insets.getInsets(bars).bottom,
                insets.getInsets(tappable).bottom,
                insets.getInsetsIgnoringVisibility(bars).bottom,
                insets.getInsetsIgnoringVisibility(tappable).bottom,
            )
            if (logging) GestureLog.write(context, JSONObject().put("time", System.currentTimeMillis()).put("insets", sizes.joinToString(" ")))
            sizes.max()
        } else {
            0
        }
        if (bottom != navigationBar) {
            navigationBar = bottom
            requestLayout()
        }
    }

    /**
     * Writes down in the debug log where the keyboard's window is against the
     * screen, with the heights that place it, so a keyboard sitting too low
     * on the phone shows in the log with what it was laid out for.
     */
    fun logPlacement(event: String) {
        if (!logging) return
        val location = IntArray(2)
        getLocationOnScreen(location)
        val metrics = resources.displayMetrics
        GestureLog.write(context, JSONObject()
            .put("time", System.currentTimeMillis())
            .put("placement", event)
            .put("top", location[1])
            .put("height", height)
            .put("measured", measuredHeight)
            .put("window", rootView.height)
            .put("navigationBar", navigationBar)
            .put("screen", "${metrics.widthPixels}x${metrics.heightPixels}")
            .put("orientation", resources.configuration.orientation))
    }

    /**
     * A new field: the letters with shift off, as the keyboard opens on them.
     * The room for the navigation bar is fitted to the window's insets as
     * they are now, not only when they change.
     */
    fun startInput() {
        if (handle != 0L) Core.reset(handle)
        rootWindowInsets?.let { fitNavigationBar(it) }
        invalidate()
    }

    /**
     * While a panel covers the letters, or the emoji search's results the
     * digits, the number row is a slim bar of its cells without their
     * digits, for sliding to delete and to move the cursor; taps on it type
     * nothing. Under a panel it is at the top of the number row's place,
     * during the search at its bottom, right above the letters.
     */
    var gestureBar = false
        set(value) {
            if (value == field) return
            field = value
            // The number row shrinks into the bar and grows back rather than jumping.
            barAnimator?.cancel()
            barAnimator = android.animation.ValueAnimator.ofFloat(barProgress, if (value) 1f else 0f).apply {
                // From the next frame on, after the panel opening with it is laid out.
                startDelay = 16
                duration = BAR_MS
                interpolator = android.view.animation.DecelerateInterpolator()
                addUpdateListener {
                    barProgress = it.animatedValue as Float
                    invalidate()
                    onBarMoved()
                }
                start()
            }
        }
    private var barAnimator: android.animation.ValueAnimator? = null

    /** How far the number row has shrunk into the bar, 0 not at all, 1 fully. */
    private var barProgress = 0f

    /** The number row's height as drawn now, of its full height. */
    private val barScale get() = 1f - (1f - GESTURE_BAR) * barProgress

    /** Called as the bar changes height, for what lies below it to follow. */
    var onBarMoved: () -> Unit = {}
    var barAboveLetters = false
        set(value) {
            field = value
            invalidate()
        }

    /** How tall the number row is as a bar, and how far below the strip the bar starts. */
    val gestureBarHeight get() = (numbersHeight * barScale).toInt()
    private val barTop get() = if (barAboveLetters) numbersHeight - gestureBarHeight else 0

    /** Whether the finger went down on the bar, whose taps type nothing. */
    private var downOnBar = false

    /** A touch's height in the keys' own terms: the bar stands for the whole number row. */
    private fun keysY(y: Float): Float {
        val inKeys = y - keysTop
        if (barProgress == 0f || inKeys < 0) return inKeys
        // Below the bar during the search are the letters, where they always are.
        if (barAboveLetters && inKeys >= numbersHeight) return inKeys
        // The bar stands for the number row, and a finger leaving it stays on the row's
        // edge: its height counts two and a half times, which would send the cursor
        // to other lines at the slightest drift.
        return ((inKeys - barTop) / barScale).coerceIn(0f, maxOf(0f, numbersHeight - 1f))
    }

    /** A label of the page shown, to tell a page change from other redraws, and the page's fade-in. */
    private var pageMark = ""
    private var pageFade = 1f
    private val labelFade = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
        duration = PAGE_MS
        addUpdateListener {
            pageFade = it.animatedValue as Float
            invalidate()
        }
    }

    /** The width and layout settings the keys were last laid out for. */
    private var builtFor = ""

    /** How tall the keys are against regular hexagons: flatter in landscape. */
    private var stretch = STRETCH

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val preferences = Dictionaries.preferences(context)
        val spaceMiddle = spaceMiddle(preferences)
        val leftKey = preferences.getString(LEFT_KEY, null) ?: DEFAULT_LEFT_KEY
        val editKeys = preferences.getBoolean(EDIT_KEYS, false)
        val sizes = sizes(preferences)
        val numberRow = numberRow(preferences)
        // The keys are laid out again only when what they depend on changed: every
        // panel shown over them measures the keyboard too, and building it anew
        // each time held up opening the emoji and their search.
        // Landscape is short: the number row and the strip go, deleting is the backspace
        // key's, and the keys flatten to fit [LANDSCAPE_KEYS] of the height and the strip's.
        val shape = "$width/$spaceMiddle/$leftKey/$editKeys/$landscape/$numberRow/${sizes.first}/${sizes.second}"
        if (handle == 0L || shape != builtFor) {
            if (handle != 0L) Core.destroy(handle)
            stretch = STRETCH
            if (landscape) {
                val regular = Core.create(width.toFloat(), STRETCH, 1f, 0, spaceMiddle, leftKey, editKeys, true)
                val room = resources.displayMetrics.heightPixels * LANDSCAPE_KEYS + fullStrip
                stretch = minOf(STRETCH, STRETCH * room / Core.height(regular))
                Core.destroy(regular)
            }
            stretch *= sizes.first
            handle = Core.create(
                width.toFloat(), stretch, sizes.second, ViewConfiguration.getLongPressTimeout().toLong(),
                spaceMiddle, leftKey, editKeys, landscape || !numberRow,
            )
            builtFor = shape
            // Freed on the words thread after whatever it is still doing with it.
            val old = letters
            letters = Core.letters(handle)
            if (old != 0L) Core.words.execute { Core.lettersFree(old) }
            Core.setAccents(handle, accents)
            headroom = Core.headroom(handle).toInt()
            Core.setStrip(handle, stripHeight.toFloat())
            loadOutlines()
        }
        Core.configure(
            handle, preferences.getBoolean(DELETE_FROM_LEFT, false),
            preferences.getInt(DELETE_WORDS, DELETE_WORDS_DEFAULT), preferences.getInt(DELETE_ENDLESS, DELETE_ENDLESS_DEFAULT),
        )
        setMeasuredDimension(width, keysTop + Core.height(handle).toInt() + navigationBar)
    }

    /**
     * The number row along the delete edge is kept from the system's back
     * gesture, so a slide in from that edge reaches the keyboard. Android
     * honours this for at most 200 dp of an edge's height.
     */
    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val band = (EXCLUSION_WIDTH * density).toInt()
        val fromLeft = Dictionaries.preferences(context).getBoolean(DELETE_FROM_LEFT, false)
        val x = if (fromLeft) 0 else width - band
        // Delete slides start in the number row, so only its edge is kept.
        val numbers = if (handle != 0L) Core.numbersBottom(handle).toInt() else 0
        systemGestureExclusionRects = listOf(Rect(x, keysTop, x + band, keysTop + numbers))
    }

    /**
     * The keyboard's letter layer for the [Core.words] thread, copied anew
     * with every keyboard; 0 before the first.
     */
    var letters = 0L
        private set

    /** The text before the cursor the strip shown was worked out for. */
    var stripBefore: String? = null
        private set

    /** Shows a strip the core worked out for `before`. */
    fun showStrip(before: String, strip: Array<String>) {
        stripBefore = before
        slots = arrayOf(strip[0], strip[1], strip[2])
        replaces = strip[3].toInt()
        correction = strip[4].takeIf { it.isNotEmpty() }?.let { it to replaces }
        unknown = strip[5].takeIf { it.isNotEmpty() }
        fix = strip[6].takeIf { it.isNotEmpty() }?.let { it to replaces }
        invalidate()
    }

    /** The last swipe's path for [Core.decodeSwipe]. */
    fun swiped(): FloatArray = if (handle == 0L) FloatArray(0) else Core.swiped(handle)

    /** Shows a swipe's words, the one committed in the middle; a choice replaces it. */
    fun showSwipe(words: Array<String>) {
        stripBefore = null
        slots = arrayOf(words.getOrElse(1) { "" }, words[0], words.getOrElse(2) { "" })
        replaces = words[0].codePointCount(0, words[0].length)
        correction = null
        fix = null
        unknown = null
        invalidate()
    }

    fun clearSuggestions() {
        stripBefore = null
        slots = arrayOf("", "", "")
        replaces = 0
        correction = null
        fix = null
        unknown = null
        invalidate()
    }

    /** Every layer tiles the same area, so a layer change only swaps the outlines. */
    private fun loadOutlines() {
        paths = (0 until Core.keyCount(handle)).map { path(Core.outline(handle, it)) }
        // Corners rounded in drawing only; touches still go by the sharp hexagons.
        val corners = android.graphics.CornerPathEffect(Core.cellWidth(handle) * CORNER)
        for (paint in listOf(keyPaint, gapPaint, popupPaint, selectedPaint)) paint.pathEffect = corners
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(tick)
        if (handle != 0L) Core.destroy(handle)
        handle = 0L
        val old = letters
        letters = 0L
        if (old != 0L) Core.words.execute { Core.lettersFree(old) }
    }

    override fun onDraw(canvas: Canvas) {
        if (handle == 0L) return
        canvas.drawRect(0f, headroom.toFloat(), width.toFloat(), height.toFloat(), backgroundPaint)
        if (stripHeight > 0) drawStrip(canvas)
        canvas.save()
        canvas.translate(0f, keysTop.toFloat())
        // Labels fit the key's height as well as its width, which flatter keys have less of.
        val cellWidth = Core.cellWidth(handle) * minOf(1f, stretch * LABEL_FLATTENING)
        val pressed = Core.pressed(handle)
        // Holding space turns the keys into a touchpad for the cursor: their labels fade.
        labelPaint.alpha = ((if (Core.movingCursor(handle)) 0x40 else 0xFF) * pageFade).toInt()
        val shiftState = Core.shiftState(handle)
        // While the keys are a touchpad for the cursor, no key is pressed.
        val moving = Core.movingCursor(handle)
        spaceKeys = paths.indices.count { Core.label(handle, it) == "space" }
        spaceWidths = paths.indices.filter { Core.label(handle, it) == "space" }.map {
            paths[it].computeBounds(spaceBounds, false)
            spaceBounds.width()
        }.toFloatArray()
        spacesDrawn = 0
        for ((index, path) in paths.withIndex()) {
            val digits = Core.inNumberRow(handle, index)
            val onBar = barProgress > 0f && digits
            // Under a panel the letters are covered.
            if (gestureBar && !digits && !barAboveLetters) continue
            if (onBar) {
                canvas.save()
                canvas.translate(0f, barTop.toFloat())
                canvas.scale(1f, barScale)
            }
            val color = keyColor(index)
            // The bar is one strip to slide on: its cells do not light up one by one.
            val lit = (index == pressed || index == flashKey) && !(digits && gestureBar) && !moving
            keyPaint.color = if (lit) theme.pressed(color) else color
            // Shift shows its state: lit for the next letter, in the accent colour while locked.
            val label = Core.label(handle, index)
            if (label == "⇧") when (shiftState) {
                1 -> keyPaint.color = theme.pressed(color)
                2 -> keyPaint.color = theme.accent
            }
            canvas.drawPath(path, keyPaint)
            canvas.drawPath(path, gapPaint)
            if (onBar) {
                canvas.restore()
                // The digits fade as the row shrinks into the bar.
                if (barProgress >= 1f) continue
                val alpha = labelPaint.alpha
                labelPaint.alpha = (alpha * (1f - barProgress)).toInt()
                val at = Core.labelPosition(handle, index)
                // Smaller as the row shrinks, as if the row were seen from further off.
                drawLabel(canvas, label, floatArrayOf(at[0], barTop + at[1] * barScale), cellWidth * barScale)
                labelPaint.alpha = alpha
                continue
            }
            val position = Core.labelPosition(handle, index)
            val hint = Core.keyHint(handle, index)
            if (label == SYMBOLS_KEY) {
                // The emoji a flick up opens, small above the symbols, as flick hints sit.
                drawLabel(canvas, label, floatArrayOf(position[0], position[1] + cellWidth * HINT_BELOW), cellWidth)
                drawIcon(canvas, R.drawable.icon_mood, position[0], position[1] - cellWidth * HINT_ABOVE, cellWidth * 0.22f, hintPaint.color)
            } else if (hint.isEmpty()) {
                drawLabel(canvas, Core.label(handle, index), position, cellWidth)
            } else {
                // The alternative sits on the side the flick goes to, the key's own sign moved off it.
                val side = -1f
                val shifted = floatArrayOf(position[0], position[1] - side * cellWidth * HINT_BELOW)
                drawLabel(canvas, Core.label(handle, index), shifted, cellWidth)
                hintPaint.textSize = cellWidth * 0.28f
                drawCentred(canvas, hint, position[0], position[1] + side * cellWidth * HINT_ABOVE, hintPaint)
            }
        }
        val choices = if (gestureBar) 0 else Core.popupCount(handle)
        if (choices > 0) {
            val selected = Core.popupSelected(handle)
            for (index in 0 until choices) {
                val path = path(Core.popupOutline(handle, index))
                canvas.drawPath(path, if (index == selected) selectedPaint else popupPaint)
                canvas.drawPath(path, gapPaint)
                drawLabel(canvas, Core.popupLabel(handle, index), Core.popupLabelPosition(handle, index), cellWidth)
            }
        }
        // A delete slide shows its zones faintly from where it started and washes the keys
        // it has crossed, from there to the finger, each in its zone's colour.
        val deleting = Core.deleting(handle)
        if (deleting.size == 3) {
            val fromLeft = Dictionaries.preferences(context).getBoolean(DELETE_FROM_LEFT, false)
            fun at(fromEdge: Float) = if (fromLeft) fromEdge else width - fromEdge
            val bottom = Core.height(handle)
            val (words, endless) = Core.deleteBorders(handle).let { it[0] to it[1] }
            // The zones run from where the slide started, as their sizes do.
            val start = if (fromLeft) deleting[0] else width - deleting[0]
            val starts = floatArrayOf(start, words, endless, width.toFloat())
            for (zone in 0..2) {
                if (starts[zone] >= starts[zone + 1]) continue
                deletePaint.color = DELETE_ZONE_COLORS[zone] and 0x00FFFFFF or (0x18 shl 24)
                canvas.drawRect(minOf(at(starts[zone]), at(starts[zone + 1])), 0f, maxOf(at(starts[zone]), at(starts[zone + 1])), bottom, deletePaint)
            }
            // The wash runs from the start to the finger, each zone it crossed in its colour.
            val reached = if (fromLeft) deleting[1] else width - deleting[1]
            for (zone in 0..2) {
                val end = minOf(starts[zone + 1], reached)
                if (starts[zone] >= end) continue
                deletePaint.color = DELETE_ZONE_COLORS[zone]
                canvas.drawRect(minOf(at(starts[zone]), at(end)), 0f, maxOf(at(starts[zone]), at(end)), bottom, deletePaint)
            }
        }
        // Moving the cursor fades the labels; the bar, which has none, takes the accent colour instead.
        if (Core.movingCursor(handle) && barProgress > 0f) {
            cursorPaint.color = theme.accent
            cursorPaint.alpha = 0x60
            canvas.drawRect(0f, barTop.toFloat(), width.toFloat(), (barTop + gestureBarHeight).toFloat(), cursorPaint)
        }
        val trail = Core.trail(handle)
        if (trail.size >= 4) {
            val path = Path()
            path.moveTo(trail[0], trail[1])
            for (i in 2 until trail.size step 2) path.lineTo(trail[i], trail[i + 1])
            canvas.drawPath(path, trailPaint)
        }
        canvas.restore()
    }

    /**
     * The settings button at the left end, a slot as wide at the right end
     * for the autofill service's button, and three equal word slots between
     * them, the middle one bold; long words shrink to fit their slot.
     */
    private fun drawStrip(canvas: Canvas) {
        val middle = headroom + stripHeight / 2f
        val iconSize = stripHeight * 0.55f
        // Autofill's entries take the place of the settings, clipboard and voice
        // buttons, the whole strip being theirs to line up in.
        if (!chipsShown) {
            drawIcon(canvas, R.drawable.icon_settings, stripSide / 2f, middle, iconSize, theme.label)
            drawIcon(canvas, R.drawable.icon_content_paste, stripSide * 1.5f, middle, iconSize, theme.label)
        }
        if (passwordsAvailable && !chipsShown) {
            val icon = passwordIcon
            if (icon != null) {
                val half = (iconSize * 0.6f).toInt()
                val x = (width - stripSide / 2f).toInt()
                icon.setBounds(x - half, middle.toInt() - half, x + half, middle.toInt() + half)
                icon.draw(canvas)
            } else {
                drawIcon(canvas, R.drawable.icon_key, width - stripSide / 2f, middle, iconSize, theme.label)
            }
        }
        val micX = width - stripSide * 1.5f
        if (listening) {
            drawIcon(canvas, R.drawable.icon_stop_circle, micX, middle, iconSize * 1.15f, theme.accent)
        } else if (!chipsShown) {
            drawIcon(canvas, R.drawable.icon_mic, micX, middle, iconSize, theme.label)
        }
        if (listening || recognising > 0) {
            drawDictation(canvas, middle)
            return
        }
        if (heldText.isNotEmpty()) {
            drawHeld(canvas, middle, iconSize)
            return
        }
        if (freshClip.isNotEmpty() && !chipsShown) {
            drawFreshClip(canvas, middle, iconSize)
            return
        }
        if (Core.deleting(handle).isNotEmpty() && deletedText.isNotEmpty()) {
            drawDeleted(canvas, middle)
            return
        }
        if (Core.movingCursor(handle) && cursorContext != null) {
            drawCursorContext(canvas, middle)
            return
        }
        if (chipsShown) return
        val slotWidth = (width - 4f * stripSide) / 3f
        for ((index, word) in slots.withIndex()) {
            if (word.isEmpty()) continue
            stripPaint.isFakeBoldText = index == 1
            stripPaint.textSize = 17 * resources.displayMetrics.scaledDensity
            val room = slotWidth - 12 * density
            val measured = stripPaint.measureText(word)
            if (measured > room) stripPaint.textSize *= room / measured
            val baseline = middle - (stripPaint.descent() + stripPaint.ascent()) / 2
            canvas.drawText(word, 2 * stripSide + slotWidth * (index + 0.5f), baseline, stripPaint)
        }
        for (index in 1..2) {
            val x = 2 * stripSide + slotWidth * index
            canvas.drawLine(x, headroom + stripHeight * 0.3f, x, headroom + stripHeight * 0.7f, dividerPaint)
        }
    }

    /** In place of the words, a text just copied in a pill with the clipboard's icon, to paste with a tap. */
    private fun drawFreshClip(canvas: Canvas, middle: Float, iconSize: Float) {
        val left = 2 * stripSide + 4 * density
        val right = width - 2 * stripSide - 4 * density
        stripPaint.isFakeBoldText = false
        stripPaint.textSize = 15 * resources.displayMetrics.scaledDensity
        val icon = iconSize * 0.8f
        val room = right - left - icon - 36 * density
        var shown = freshClip.replace('\n', ' ')
        if (stripPaint.measureText(shown) > room) {
            while (shown.length > 1 && stripPaint.measureText("$shown…") > room) shown = withoutLastCharacter(shown)
            shown += "…"
        }
        val width = icon + 8 * density + stripPaint.measureText(shown) + 28 * density
        val start = (left + right - width) / 2
        val half = stripHeight * 0.32f
        pillPaint.color = theme.other
        canvas.drawRoundRect(start, middle - half, start + width, middle + half, half, half, pillPaint)
        drawIcon(canvas, R.drawable.icon_content_paste, start + 14 * density + icon / 2, middle, icon, theme.label)
        val textX = start + 14 * density + icon + 8 * density + stripPaint.measureText(shown) / 2
        canvas.drawText(shown, textX, middle - (stripPaint.descent() + stripPaint.ascent()) / 2, stripPaint)
    }

    /**
     * In place of the words, dictated text no field took: a button to drop
     * it at the left, the text's end, and a button to put it in at the right.
     */
    private fun drawHeld(canvas: Canvas, middle: Float, iconSize: Float) {
        val left = 2 * stripSide
        val right = width - 2 * stripSide
        drawIcon(canvas, R.drawable.icon_close, left + stripSide / 2f, middle, iconSize, theme.label)
        drawIcon(canvas, R.drawable.icon_send, right - stripSide / 2f, middle, iconSize, theme.accent)
        stripPaint.isFakeBoldText = false
        stripPaint.textSize = 15 * resources.displayMetrics.scaledDensity
        val room = right - left - 2 * stripSide - 8 * density
        // The end of a long text, which is what was said last.
        var shown = heldText
        while (shown.length > 1 && stripPaint.measureText("…$shown") > room) shown = shown.drop(1)
        if (shown != heldText) shown = "…$shown"
        canvas.drawText(shown, (left + right) / 2f, middle - (stripPaint.descent() + stripPaint.ascent()) / 2, stripPaint)
    }

    /**
     * In place of the words while dictating: the voice as bars, newest at
     * the right, and how long it has been listening. Once listening stops
     * while text is still being recognised, three dots pulse in their place.
     */
    private fun drawDictation(canvas: Canvas, middle: Float) {
        if (!listening) {
            val gap = 14 * density
            val phase = (SystemClock.uptimeMillis() % 1200) / 400f
            wavePaint.color = theme.accent
            for (i in 0..2) {
                val lit = 1f - minOf(kotlin.math.abs(phase - i), 3f - kotlin.math.abs(phase - i)).coerceAtMost(1f)
                wavePaint.alpha = (0x50 + 0xAF * lit).toInt()
                canvas.drawCircle(width / 2f + (i - 1) * gap, middle, 3.5f * density, wavePaint)
            }
            postInvalidateOnAnimation()
            return
        }
        val left = 2 * stripSide + 8 * density
        stripPaint.isFakeBoldText = false
        stripPaint.textSize = 15 * resources.displayMetrics.scaledDensity
        val seconds = ((SystemClock.uptimeMillis() - listeningSince) / 1000).toInt()
        val clock = "%d:%02d".format(seconds / 60, seconds % 60)
        val clockWidth = stripPaint.measureText("00:00")
        val right = width - 2 * stripSide - clockWidth - 12 * density
        canvas.drawText(clock, right + 6 * density + clockWidth / 2, middle - (stripPaint.descent() + stripPaint.ascent()) / 2, stripPaint)
        postInvalidateDelayed(250)
        val step = (right - left) / LEVELS
        wavePaint.color = theme.accent
        wavePaint.alpha = 0xFF
        wavePaint.strokeWidth = step * 0.6f
        val tallest = stripHeight * 0.32f
        for ((index, level) in levels.withIndex()) {
            val x = right - (levels.size - index - 0.5f) * step
            // Loudness spans a wide range; its square root shows quiet speech too.
            val half = maxOf(1.5f * density, tallest * kotlin.math.sqrt((level * 8f).coerceAtMost(1f)))
            canvas.drawLine(x, middle - half, x, middle + half, wavePaint)
        }
    }

    /** In place of the words during a delete slide: what it has taken so far, struck through, its end at the right. */
    private fun drawDeleted(canvas: Canvas, middle: Float) {
        val left = 2 * stripSide + 8 * density
        val right = width - 2 * stripSide - 8 * density
        stripPaint.isFakeBoldText = false
        stripPaint.textSize = 17 * resources.displayMetrics.scaledDensity
        var shown = deletedText
        while (shown.length > 1 && stripPaint.measureText("…$shown") > right - left) shown = shown.drop(1)
        if (shown != deletedText) shown = "…$shown"
        val textWidth = stripPaint.measureText(shown)
        val x = right - textWidth / 2
        val baseline = middle - (stripPaint.descent() + stripPaint.ascent()) / 2
        stripPaint.color = DELETE_TEXT
        stripPaint.isStrikeThruText = true
        canvas.drawText(shown, x, baseline, stripPaint)
        stripPaint.isStrikeThruText = false
        stripPaint.color = theme.label
    }

    /** The text before and after the cursor while it is being moved, for the strip; null otherwise. */
    var cursorContext: Pair<String, String>? = null
        set(value) {
            field = value
            invalidate()
        }

    /** In place of the words while the cursor moves: the text around it, the cursor in the accent colour in the middle. */
    private fun drawCursorContext(canvas: Canvas, middle: Float) {
        val (before, after) = cursorContext ?: return
        val left = 2 * stripSide + 8 * density
        val right = width - 2 * stripSide - 8 * density
        val centre = (left + right) / 2
        stripPaint.isFakeBoldText = false
        stripPaint.textSize = 17 * resources.displayMetrics.scaledDensity
        val baseline = middle - (stripPaint.descent() + stripPaint.ascent()) / 2
        val room = centre - left - 4 * density
        var shownBefore = before.replace('\n', ' ')
        while (shownBefore.isNotEmpty() && stripPaint.measureText(shownBefore) > room) shownBefore = withoutFirstCharacter(shownBefore)
        var shownAfter = after.replace('\n', ' ')
        while (shownAfter.isNotEmpty() && stripPaint.measureText(shownAfter) > room) shownAfter = withoutLastCharacter(shownAfter)
        val align = stripPaint.textAlign
        stripPaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(shownBefore, centre - 3 * density, baseline, stripPaint)
        stripPaint.textAlign = Paint.Align.LEFT
        canvas.drawText(shownAfter, centre + 3 * density, baseline, stripPaint)
        stripPaint.textAlign = align
        cursorPaint.color = theme.accent
        cursorPaint.alpha = 0xFF
        canvas.drawRect(centre - 1.2f * density, middle - stripHeight * 0.28f, centre + 1.2f * density, middle + stripHeight * 0.28f, cursorPaint)
    }

    private val icons = mutableMapOf<Int, android.graphics.drawable.Drawable>()

    /** Draws one of the Material Symbols icons centred on a point, `size` across, tinted. */
    private fun drawIcon(canvas: Canvas, id: Int, cx: Float, cy: Float, size: Float, color: Int) {
        val icon = icons.getOrPut(id) { context.getDrawable(id)!!.mutate() }
        icon.setTint(color)
        val half = size / 2
        icon.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
        icon.draw(canvas)
    }

    /**
     * The strip's part at x: -2 the settings button and -1 the clipboard button
     * at its left end, 0 to 2 the word slots, 4 the microphone and 3 the
     * right end, which holds the password manager's button.
     */
    private fun stripSlot(x: Float): Int = when {
        x < stripSide -> -2
        x < 2 * stripSide -> -1
        x >= width - stripSide -> 3
        x >= width - 2 * stripSide -> 4
        else -> ((x - 2 * stripSide) / ((width - 4f * stripSide) / 3f)).toInt().coerceIn(0, 2)
    }

    /** Single characters large, words such as "space" smaller so they fit their key. */
    private fun drawLabel(canvas: Canvas, label: String, position: FloatArray, cellWidth: Float) {
        // Space carries the languages in their order, the one being written first and
        // brightest, faint while it is a guess; of two space keys the first carries
        // that one and the second the rest.
        if (label == "space") {
            var shown = when {
                spaceKeys == 1 -> spaceLanguages
                spacesDrawn == 0 -> spaceLanguages.take(1)
                else -> spaceLanguages.drop(1)
            }
            val first = spacesDrawn == 0
            spacesDrawn++
            if (shown.isEmpty()) return
            val alpha = labelPaint.alpha
            // A narrow space shrinks its codes, then leaves out the last ones, rather than spill over its neighbours.
            val room = (spaceWidths.getOrNull(spacesDrawn - 1) ?: 0f) * SPACE_LABEL_ROOM
            labelPaint.textSize = cellWidth * 0.2f
            fun wide(codes: List<String>) = codes.sumOf { labelPaint.measureText(it).toDouble() }.toFloat() +
                labelPaint.measureText("  ") * (codes.size - 1)
            if (room > 0f && wide(shown) > room) {
                labelPaint.textSize = maxOf(labelPaint.textSize * room / wide(shown), cellWidth * 0.13f)
                var fits = shown
                while (fits.size > 1 && wide(fits) > room) fits = fits.dropLast(1)
                shown = fits
            }
            val gap = labelPaint.measureText("  ")
            val widths = shown.map { labelPaint.measureText(it) }
            var x = position[0] - (widths.sum() + gap * (shown.size - 1)) / 2
            val y = position[1] - (labelPaint.descent() + labelPaint.ascent()) / 2
            val align = labelPaint.textAlign
            labelPaint.textAlign = Paint.Align.LEFT
            for ((i, code) in shown.withIndex()) {
                val inUse = first && i == 0 && spaceInUse
                labelPaint.alpha = when {
                    inUse && spacePinned -> alpha
                    inUse -> alpha * 3 / 5
                    else -> alpha / 4
                }
                canvas.drawText(code, x, y, labelPaint)
                x += widths[i] + gap
            }
            labelPaint.textAlign = align
            labelPaint.alpha = alpha
            return
        }
        (if (label == "⏎") enterIcon else KEY_ICONS[label])?.let { id ->
            drawIcon(canvas, id, position[0], position[1], cellWidth * 0.36f, labelPaint.color)
            return
        }
        val single = label.codePointCount(0, label.length) == 1
        labelPaint.textSize = cellWidth * if (single) 0.36f else 0.24f
        if (single && label in INK_CENTRED) {
            drawCentred(canvas, label, position[0], position[1], labelPaint)
            return
        }
        val baseline = position[1] - (labelPaint.descent() + labelPaint.ascent()) / 2
        canvas.drawText(label, position[0], baseline, labelPaint)
    }

    private val glyphBounds = Rect()

    /**
     * Draws text centred on its own ink rather than on the line it sits on:
     * a full stop or comma sits low on the line and would look misplaced.
     */
    private fun drawCentred(canvas: Canvas, text: String, x: Float, y: Float, paint: Paint) {
        paint.getTextBounds(text, 0, text.length, glyphBounds)
        canvas.drawText(text, x, y - glyphBounds.exactCenterY(), paint)
    }

    private fun path(corners: FloatArray) = Path().apply {
        moveTo(corners[0], corners[1])
        for (i in 2 until corners.size step 2) lineTo(corners[i], corners[i + 1])
        close()
    }

    /** The text before the cursor for the gesture log, null where it must not be kept. */
    var logContext: () -> String? = { null }

    /** The touch being written down for [GestureLog], in debug builds. */
    private var record: JSONObject? = null
    private var recordPoints = JSONArray()
    private var recordActions = JSONArray()
    private var recordDown = 0L
    private val logging = GestureLog.enabled()

    /**
     * Writes down a step that held up the main thread for [SLOW_MS] or
     * more, so lag on the phone shows in the log with what caused it.
     */
    fun slow(step: String, ms: Long) {
        if (logging && ms >= SLOW_MS) {
            GestureLog.write(context, JSONObject().put("time", System.currentTimeMillis()).put("slow", step).put("ms", ms))
        }
    }

    /** Adds what came of the touch being recorded, such as the words a swipe spelled. */
    fun note(kind: String, text: String) {
        if (record != null) recordActions.put(JSONObject().put("kind", kind).put("text", text))
    }

    /** The field takes a password or PIN, whose touches the debug log leaves out. */
    var secret = false

    private fun startRecord(x: Float, y: Float, time: Long) {
        if (!logging || secret) return
        recordPoints = JSONArray()
        recordActions = JSONArray()
        recordDown = time
        val pressed = Core.pressed(handle)
        record = JSONObject()
            .put("time", System.currentTimeMillis())
            .put("layout", if (spaceMiddle(Dictionaries.preferences(context))) SPACE_MIDDLE else SPACE_BOTTOM)
            .put("width", width)
            .put("cellWidth", Core.cellWidth(handle).toDouble())
            .put("key", if (pressed >= 0) Core.label(handle, pressed) else "")
            .put("editKeys", Dictionaries.preferences(context).getBoolean(EDIT_KEYS, false))
            .put("deleteFromLeft", Dictionaries.preferences(context).getBoolean(DELETE_FROM_LEFT, false))
            .put("before", logContext())
        addPoint(x, y, time)
    }

    private fun addPoint(x: Float, y: Float, time: Long) {
        if (record != null) recordPoints.put(JSONArray().put(x.toInt()).put(y.toInt()).put(time - recordDown))
    }

    private fun finishRecord(how: String) {
        val done = record ?: return
        record = null
        done.put("end", how).put("points", recordPoints).put("actions", recordActions)
        GestureLog.write(context, done)
    }

    /** The finger the core is following, or -1. */
    private var finger = -1
    private var fingerX = 0f
    private var fingerY = 0f


    /**
     * Touches go to the core, which tells taps, flicks and holds apart; the
     * view only redraws, passes on what they mean, and wakes the core when
     * a hold it is timing is due.
     *
     * The core follows one finger. A second finger coming down ends the
     * first finger's touch where it is, as typing with two thumbs rolls from
     * one key to the next before the first thumb has lifted; the core then
     * follows the new finger and ignores the old one.
     */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (handle == 0L) return false
        val index = event.actionIndex
        val time = event.eventTime
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (finger >= 0) {
                    lift(fingerX, fingerY, time, "rollover")
                }
                val x = event.getX(index)
                val y = keysY(event.getY(index))
                stripTouch = if (y < 0) stripSlot(x) + 2 else -1
                if (stripTouch >= 0) {
                    finger = -1
                    return true
                }
                finger = event.getPointerId(index)
                downOnBar = gestureBar && y < numbersHeight
                downTime = time
                fingerX = x
                fingerY = y
                Core.touchDown(handle, x, y, time)
                startRecord(x, y, time)
            }
            MotionEvent.ACTION_MOVE -> {
                val at = event.findPointerIndex(finger)
                if (at < 0) return true
                fingerX = event.getX(at)
                fingerY = keysY(event.getY(at))
                addPoint(fingerX, fingerY, time)
                val action = Core.touchMove(handle, fingerX, fingerY, time)
                // A move that does nothing yet can still start something timed, as
                // resting at a side does for the cursor.
                if (action[0] != "nothing") act(action) else schedule()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val x = event.getX(index)
                val y = keysY(event.getY(index))
                if (stripTouch >= 0 && event.actionMasked == MotionEvent.ACTION_UP) {
                    val slot = stripSlot(x)
                    val touched = stripTouch - 2
                    stripTouch = -1
                    if (slot != touched || y >= 0) return true
                    if (chipsShown && (slot < 0 || slot == 4)) return true
                    if (slot == -2) {
                        onSettings()
                        return true
                    }
                    if (slot == -1) {
                        onClipboard()
                        return true
                    }
                    if (slot == 3) {
                        if (passwordsAvailable) onPasswords()
                        return true
                    }
                    if (slot == 4) {
                        onVoice()
                        return true
                    }
                    if (freshClip.isNotEmpty() && heldText.isEmpty() && !listening && recognising == 0 && !chipsShown) {
                        onFreshClip()
                        return true
                    }
                    if (heldText.isNotEmpty() && !listening && recognising == 0) {
                        when {
                            x < 3 * stripSide -> onHeld(false)
                            x >= width - 3 * stripSide -> onHeld(true)
                        }
                        return true
                    }
                    val word = slots.getOrElse(slot) { "" }
                    if (!chipsShown && word.isNotEmpty()) {
                        onPick(word, replaces)
                        if (logging) {
                            GestureLog.write(context, JSONObject().put("time", System.currentTimeMillis()).put("picked", word))
                        }
                    }
                    return true
                }
                if (event.getPointerId(index) == finger) lift(x, y, time, "lift")
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                Core.touchCancel(handle)
                finishRecord("cancel")
                finger = -1
                stripTouch = -1
            }
        }
        schedule()
        invalidate()
        return true
    }

    /** When the followed finger went down. */
    private var downTime = 0L

    /** The key lit after its tap has ended, until [FLASH_MS] after it went down, or -1. */
    private var flashKey = -1
    private val unflash = Runnable {
        flashKey = -1
        invalidate()
    }

    /** Ends the followed finger's touch at a point and acts on it. */
    private fun lift(x: Float, y: Float, time: Long, how: String) {
        finger = -1
        cursorContext = null
        // A tap quicker than a frame or two would never be seen lit.
        val pressed = Core.pressed(handle)
        if (pressed >= 0) {
            flashKey = pressed
            removeCallbacks(unflash)
            postDelayed(unflash, (downTime + FLASH_MS - time).coerceIn(FLASH_MS / 2, FLASH_MS))
        }
        addPoint(x, y, time)
        act(Core.touchUp(handle, x, y, time))
        finishRecord(how)
    }

    private fun act(action: Array<String>) {
        val (kind, text) = action
        // The bar only slides; a digit tapped or held there is dropped.
        if (downOnBar && kind == "commit") return
        note(kind, text)
        if (kind == "redraw") {
            loadOutlines()
            // Another page's labels come in with a quick fade.
            val first = if (paths.isNotEmpty()) Core.label(handle, paths.size / 2) else ""
            if (first != pageMark) {
                pageMark = first
                labelFade.cancel()
                labelFade.start()
            }
        }
        onAction(kind, text)
        schedule()
        invalidate()
    }

    private fun schedule() {
        removeCallbacks(tick)
        val due = Core.deadline(handle)
        if (due >= 0) postDelayed(tick, (due - SystemClock.uptimeMillis()).coerceAtLeast(0))
    }

    companion object {
        /** The key to the symbols, which a flick up on opens the emoji. */
        private const val SYMBOLS_KEY = "@#€"

        /**
         * How far a label with a small sign above it moves down, and how far
         * up the sign sits, in key widths: close enough together that the
         * pair fits under the points reaching into the bottom row.
         */
        private const val HINT_BELOW = 0.10f
        private const val HINT_ABOVE = 0.20f

        /** How tall the number row is as the bar under a panel, of its full height. */
        private const val GESTURE_BAR = 0.4f

        /** How long another page's labels take to fade in, in ms. */
        private const val PAGE_MS = 120L

        /** How long the number row takes to shrink into the bar or grow back, in ms. */
        private const val BAR_MS = 180L

        /** Regular hexagons: at seven to a row they are already finger-sized. */
        private const val STRETCH = 1.0f

        /** The share of the screen's height the keys take in landscape, besides the strip's height they take over there and the navigation bar. */
        private const val LANDSCAPE_KEYS = 0.55f

        /** How much flatter keys may get before their labels shrink with them. */
        private const val LABEL_FLATTENING = 1.25f

        /** Keys drawn as an icon instead of their label. */
        val KEY_ICONS = mapOf(
            "⌫" to R.drawable.icon_backspace,
            "⏎" to R.drawable.icon_keyboard_return,
            "⇧" to R.drawable.icon_shift,
        )

        /** Marks that sit high or low on the line and look misplaced unless centred on their ink. */
        private val INK_CENTRED = setOf(".", ",", ";", ":", "'", "\"", "…", "!", "?", "¡", "¿")

        /** The wash over keys a delete slide has crossed, and the colour of the text it took. */
        private const val DELETE_TEXT = 0xFFFF6B6B.toInt()

        /** How round the keys' corners are drawn, in key widths. */
        const val CORNER = 0.12f

        /** Tenths of a second of voice the waveform shows. */
        private const val LEVELS = 48

        /** Main thread time from which a step counts as slow in the gesture log, in ms. */
        private const val SLOW_MS = 8L

        /** How long a tapped key stays lit at least, so a quick tap shows, in ms. */
        private const val FLASH_MS = 90L

        /** Height of the suggestion strip in dp. */
        const val STRIP_DP = 44

        /** How much of the space key's width its language codes may take. */
        private const val SPACE_LABEL_ROOM = 0.8f

        /** Width of the delete edge kept from the back gesture, in dp. */
        private const val EXCLUSION_WIDTH = 32

        /** Preference naming the letter layout: [SPACE_BOTTOM] or, unless chosen, [SPACE_MIDDLE]. */
        const val LAYOUT = "layout"
        const val SPACE_MIDDLE = "space_middle"
        const val SPACE_BOTTOM = "hexkeys"

        fun spaceMiddle(preferences: android.content.SharedPreferences) = preferences.getString(LAYOUT, null) != SPACE_BOTTOM

        /**
         * Preference naming the key left of space in the middle layout, one of
         * [LEFT_KEYS], which are the names the core's `space_middle_with` knows.
         */
        const val LEFT_KEY = "left_key"
        const val DEFAULT_LEFT_KEY = "comma"
        val LEFT_KEYS = listOf("comma", "backspace", "space")

        /** Preference: delete swipes come in from the left edge. */
        const val DELETE_FROM_LEFT = "delete_from_left"

        /**
         * Preferences: where a delete slide's words zone starts, after the letters zone,
         * and where its endless zone starts, in percent of the width from the edge it
         * comes in from; [DELETE_ENDLESS_OFF] for no endless zone. The same numbers as
         * the core's `DELETE_WORDS_PERCENT`, `DELETE_WORDS_LEAST`, `DELETE_ENDLESS_PERCENT`,
         * `DELETE_WORDS_ROOM` and `DELETE_ENDLESS_OFF`.
         */
        const val DELETE_WORDS = "delete_words_percent"
        const val DELETE_WORDS_DEFAULT = 45
        const val DELETE_WORDS_LEAST = 10
        const val DELETE_ENDLESS = "delete_endless_percent"
        const val DELETE_ENDLESS_DEFAULT = 90
        const val DELETE_WORDS_ROOM = 10
        const val DELETE_ENDLESS_OFF = 100

        /**
         * Preferences: the last letters the letters zone deletes one by one, and the last
         * words the slide has deleted by the words zone's far end.
         */
        const val DELETE_LETTERS = "delete_letters"
        const val DELETE_LETTERS_DEFAULT = 20
        val DELETE_LETTERS_RANGE = 3..30
        const val DELETE_WORD_WORDS = "delete_word_words"
        const val DELETE_WORD_WORDS_DEFAULT = 10
        val DELETE_WORD_WORDS_RANGE = 2..30

        /** The zones' colours, letters, words and endless: amber, orange and red, ever stronger. */
        val DELETE_ZONE_COLORS = intArrayOf(0x50F5A524, 0x60F0703A, 0x80E5484D.toInt())

        /**
         * Preferences for how big the keyboard is, in percent: all of it, the letter keys on
         * their own, and the number row on its own; and whether there is a number row.
         */
        const val SIZE_ALL = "size_all"
        const val SIZE_KEYS = "size_keys"
        const val SIZE_NUMBERS = "size_numbers"
        const val NUMBER_ROW = "number_row"
        val SIZE_RANGE = 70..130
        val SIZE_NUMBERS_RANGE = 50..150
        const val SIZE_DEFAULT = 100

        fun numberRow(preferences: android.content.SharedPreferences) = preferences.getBoolean(NUMBER_ROW, true)

        /**
         * The factor the keys' height is made of, from the whole keyboard's size and the
         * letter keys', and the number row's height against that of the keys: the number row
         * is as tall as its own size says, whatever the letter keys are set to.
         */
        fun sizes(preferences: android.content.SharedPreferences): Pair<Float, Float> {
            fun percent(key: String, range: IntRange) = preferences.getInt(key, SIZE_DEFAULT).coerceIn(range) / 100f
            val all = percent(SIZE_ALL, SIZE_RANGE)
            val keys = percent(SIZE_KEYS, SIZE_RANGE)
            return all * keys to percent(SIZE_NUMBERS, SIZE_NUMBERS_RANGE) / keys
        }

        /** Preference adding shift and backspace keys to the layout. */
        const val EDIT_KEYS = "edit_keys"

    }
}
