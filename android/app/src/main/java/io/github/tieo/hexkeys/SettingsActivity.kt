package io.github.tieo.hexkeys

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * The keyboard's settings, which the launcher and the system's keyboard
 * settings both open: setting the keyboard up while it is not the active
 * one, a field to try it in, typing, gestures, looks, and the languages,
 * which have a page of their own.
 */
class SettingsActivity : LocalizedActivity() {

    companion object {
        /** Extra naming the part of the settings to show, such as [VOICE]. */
        const val SECTION = "section"
        const val VOICE = "voice"
    }

    private lateinit var setup: View
    private lateinit var setupButton: Button
    private lateinit var languages: TextView
    private val ui by lazy { Ui(this) }
    private val downloads = java.util.concurrent.Executors.newSingleThreadExecutor()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = ui.column()

        setupButton = ui.primaryButton("")
        setup = ui.card(setupButton)
        column.addView(setup)

        column.addView(ui.card(EditText(this).apply {
            hint = getString(R.string.try_keyboard)
            setTextColor(ui.text)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minHeight = ui.dp(48)
        }))

        column.addView(ui.heading(getString(R.string.app_language)))
        column.addView(ui.card(
            ui.segmented(
                listOf("en" to getString(R.string.language_english), "de" to getString(R.string.language_german)),
                AppLocale.current(this),
            ) { id ->
                ui.preferences.edit().putString(AppLocale.KEY, id).apply()
                recreate()
            },
        ))

        column.addView(ui.heading(getString(R.string.typing)))
        column.addView(ui.card(
            ui.label(getString(R.string.autocorrect)),
            ui.segmented(
                Dictionaries.AUTOCORRECT_MODES.zip(listOf(getString(R.string.off), getString(R.string.autocorrect_obvious), getString(R.string.autocorrect_all))),
                Dictionaries.autocorrectMode(this),
            ) { id -> ui.preferences.edit().putString(Dictionaries.AUTOCORRECT_MODE, id).apply() },
            learningRow(),
        ))

        column.addView(ui.heading(getString(R.string.gestures)))
        column.addView(ui.card(
            ui.label(getString(R.string.delete_from)),
            ui.segmented(
                listOf("left" to getString(R.string.left_edge), "right" to getString(R.string.right_edge)),
                if (ui.preferences.getBoolean(KeyboardView.DELETE_FROM_LEFT, false)) "left" else "right",
            ) { id -> ui.preferences.edit().putBoolean(KeyboardView.DELETE_FROM_LEFT, id == "left").apply() },
            ui.slider(
                getString(R.string.delete_all),
                KeyboardView.DELETE_ALL,
                range = KeyboardView.DELETE_ALL_LEAST..KeyboardView.DELETE_ALL_OFF,
                default = KeyboardView.DELETE_ALL_DEFAULT,
                describe = { if (it == KeyboardView.DELETE_ALL_OFF) getString(R.string.off) else getString(R.string.percent, it) },
            ),
            ui.label(getString(R.string.delete_all_hint)),
        ))

        column.addView(ui.heading(getString(R.string.voice)))
        column.addView(voiceCard())

        column.addView(ui.heading(getString(R.string.look)))
        layouts = layoutPicker()
        column.addView(ui.card(
            ui.label(getString(R.string.layout)),
            layouts,
            *leftKeyViews.toTypedArray(),
            ui.toggle(getString(R.string.edit_keys), KeyboardView.EDIT_KEYS) { layouts.invalidateAll() },
            ui.label(getString(R.string.theme)),
            themePicker(),
        ))

        column.addView(ui.heading(getString(R.string.size)))
        column.addView(ui.card(
            ui.slider(getString(R.string.size_all), KeyboardView.SIZE_ALL, KeyboardView.SIZE_RANGE, KeyboardView.SIZE_DEFAULT) { layouts.invalidateAll() },
            ui.slider(getString(R.string.size_keys), KeyboardView.SIZE_KEYS, KeyboardView.SIZE_RANGE, KeyboardView.SIZE_DEFAULT) { layouts.invalidateAll() },
            ui.toggle(getString(R.string.number_row), KeyboardView.NUMBER_ROW, default = true) {
                numberRowHeight.visibility = if (KeyboardView.numberRow(ui.preferences)) View.VISIBLE else View.GONE
                layouts.invalidateAll()
            },
            numberRowHeight,
            ui.label(getString(R.string.number_row_hint)),
        ))

        languages = TextView(this).apply {
            textSize = 15f
            setTextColor(ui.secondaryText)
        }
        column.addView(ui.card(ui.navigationRow(getString(R.string.languages), languages) {
            startActivity(Intent(this, LanguagesActivity::class.java))
        }).apply { (layoutParams as LinearLayout.LayoutParams).topMargin = ui.dp(24) })

        column.addView(ui.card(ui.navigationRow(getString(R.string.help), TextView(this).apply {
            text = getString(R.string.help_summary)
            textSize = 15f
            setTextColor(ui.secondaryText)
        }) {
            startActivity(Intent(this, HelpActivity::class.java))
        }))

        column.addView(ui.card(ui.navigationRow(getString(R.string.credits), TextView(this).apply {
            text = getString(R.string.credits_summary)
            textSize = 15f
            setTextColor(ui.secondaryText)
        }) {
            startActivity(Intent(this, CreditsActivity::class.java))
        }))

        val page = ui.page(column)
        setContentView(page)
        title = getString(R.string.app_name)
        if (intent.getStringExtra(SECTION) == VOICE) showVoice(page)
    }

    /**
     * Opened from the microphone button while speaking is not set up yet:
     * the voice setting scrolled into view and lit up, and the microphone
     * asked for at once when a model is there already.
     */
    private fun showVoice(page: android.widget.ScrollView) {
        page.post {
            page.smoothScrollTo(0, voice.top - ui.dp(48))
            val highlight = GradientDrawable().apply {
                cornerRadius = ui.dp(20).toFloat()
                setColor(0x14FFFFFF)
                setStroke(ui.dp(2), ui.accent)
            }
            val plain = voice.background
            voice.background = highlight
            android.animation.ObjectAnimator.ofInt(highlight, "alpha", 255, 90, 255, 90, 255).apply {
                duration = 1600
                doOnEnd { voice.background = plain }
                start()
            }
        }
        if (!Voice.permitted(this)) {
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1)
        }
        // With speech input off, it switches on and its model starts downloading right away.
        if (!Voice.enabled(this)) startDefault()
    }

    private fun android.animation.Animator.doOnEnd(action: () -> Unit) = addListener(object : android.animation.AnimatorListenerAdapter() {
        override fun onAnimationEnd(animation: android.animation.Animator) = action()
    })

    override fun onResume() {
        super.onResume()
        // Coming back from the system settings: show only the step still missing.
        val manager = getSystemService(InputMethodManager::class.java)
        val enabled = manager.enabledInputMethodList.any { it.packageName == packageName }
        val current = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.startsWith("$packageName/") == true
        setup.visibility = if (enabled && current) View.GONE else View.VISIBLE
        setupButton.text = if (enabled) getString(R.string.switch_to_keyboard) else getString(R.string.enable_keyboard)
        setupButton.setOnClickListener {
            if (enabled) manager.showInputMethodPicker()
            else startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        }
        val chosen = Dictionaries.enabled(this)
        languages.text = Dictionaries.catalog(this).filter { it.file in chosen }.joinToString(", ") { it.title(this) }
        microphone.visibility = if (Voice.permitted(this) || !Voice.enabled(this)) View.GONE else View.VISIBLE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // The keyboard picker is a dialog over this window, not a new screen.
        if (hasFocus) onResume()
    }

    private lateinit var microphone: Button
    private lateinit var voice: View

    /** Switches speech input on, for the microphone button arriving with it off. */
    private var startDefault: () -> Unit = {}
    private lateinit var layouts: LayoutTiles

    /**
     * Speech input off or on, its model's download progress while it
     * downloads, and the button to allow the microphone while it is not
     * allowed. Off deletes the model, which takes 670 MB.
     */
    private fun voiceCard(): View {
        Voice.cleanUp(this)
        val progress = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            visibility = View.GONE
        }
        val model = Voice.SPEECH_MODEL
        val options = listOf<Pair<String, CharSequence>>("off" to getString(R.string.off), "on" to getString(R.string.voice_on, model.megabytes))
        fun switchOn() {
            if (Voice.downloaded(this)) {
                ui.preferences.edit().putString(Voice.MODEL, model.id).apply()
                recreate()
                return
            }
            progress.progress = 0
            progress.visibility = View.VISIBLE
            downloads.execute {
                val ok = runCatching {
                    Voice.download(this) { done -> runOnUiThread { progress.progress = (done * 1000).toInt() } }
                }.isSuccess
                runOnUiThread {
                    progress.visibility = View.GONE
                    if (ok) ui.preferences.edit().putString(Voice.MODEL, model.id).apply()
                    recreate()
                }
            }
        }
        startDefault = { switchOn() }
        val choice = ui.segmented(options, if (Voice.enabled(this)) "on" else "off", columns = 2) { id ->
            if (id == "on") switchOn() else {
                Voice.delete(this)
                recreate()
            }
        }
        microphone = ui.primaryButton(getString(R.string.allow_microphone)).apply {
            setOnClickListener { requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 1) }
        }
        voice = ui.card(choice, progress, microphone)
        return voice
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        onResume()
    }

    /** The learning switch, with deleting what was learned beside it once there is something. */
    private fun learningRow(): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val toggle = ui.toggle(getString(R.string.learn_new_words), Dictionaries.LEARNING)
        row.addView(toggle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (Dictionaries.learned(this).isNotEmpty()) {
            val clear = ui.textButton(getString(R.string.forget))
            clear.setOnClickListener {
                Dictionaries.deleteLearned(this)
                clear.visibility = View.GONE
            }
            row.addView(clear)
        }
        return row
    }

    /** How tall the number row is, offered while there is one. */
    private val numberRowHeight by lazy {
        ui.slider(getString(R.string.size_numbers), KeyboardView.SIZE_NUMBERS, KeyboardView.SIZE_NUMBERS_RANGE, KeyboardView.SIZE_DEFAULT) {
            layouts.invalidateAll()
        }.also { it.visibility = if (KeyboardView.numberRow(ui.preferences)) View.VISIBLE else View.GONE }
    }

    /** What the key left of space does, offered while space is in the middle. */
    private val leftKeyViews by lazy {
        val names = mapOf(
            "comma" to getString(R.string.left_key_comma), "backspace" to getString(R.string.left_key_backspace),
            "emoji" to getString(R.string.left_key_emoji), "clipboard" to getString(R.string.left_key_clipboard),
            "voice" to getString(R.string.left_key_voice), "hide" to getString(R.string.left_key_hide),
            "space" to getString(R.string.left_key_space),
        )
        listOf(
            ui.label(getString(R.string.left_key)),
            ui.segmented(
                KeyboardView.LEFT_KEYS.map { it to names.getValue(it) },
                ui.preferences.getString(KeyboardView.LEFT_KEY, null) ?: KeyboardView.DEFAULT_LEFT_KEY,
                columns = 4,
            ) { id ->
                ui.preferences.edit().putString(KeyboardView.LEFT_KEY, id).apply()
                layouts.invalidateAll()
            },
        ).onEach { it.visibility = if (KeyboardView.spaceMiddle(ui.preferences)) View.VISIBLE else View.GONE }
    }

    /** The layouts side by side as tiles with a drawing of their keys, the chosen one outlined. */
    private fun layoutPicker(): LayoutTiles {
        val tiles = LayoutTiles(this)
        val chosen = if (KeyboardView.spaceMiddle(ui.preferences)) KeyboardView.SPACE_MIDDLE else KeyboardView.SPACE_BOTTOM
        val views = mutableListOf<View>()
        for ((id, name) in listOf(KeyboardView.SPACE_BOTTOM to getString(R.string.layout_space_bottom), KeyboardView.SPACE_MIDDLE to getString(R.string.layout_space_middle))) {
            val preview = LayoutPreview(this, id == KeyboardView.SPACE_MIDDLE)
            tiles.previews += preview
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
                addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                addView(TextView(this@SettingsActivity).apply {
                    text = name
                    textSize = 13f
                    setTextColor(ui.text)
                    gravity = Gravity.CENTER
                    setPadding(0, ui.dp(6), 0, 0)
                })
            }
            views += tile
            tile.setOnClickListener {
                views.forEach { ui.outline(it, it === tile) }
                ui.preferences.edit().putString(KeyboardView.LAYOUT, id).apply()
                leftKeyViews.forEach { it.visibility = if (id == KeyboardView.SPACE_MIDDLE) View.VISIBLE else View.GONE }
            }
            ui.outline(tile, id == chosen)
            tiles.addView(tile, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(ui.dp(2), 0, ui.dp(2), 0)
            })
        }
        return tiles
    }

    /** Every theme as a tile with its preview and name, the chosen one outlined. */
    private fun themePicker(): View {
        val tiles = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chosen = Theme.current(this).id
        val all = Theme.all(this)
        val views = mutableListOf<View>()
        for (theme in all) {
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
                addView(ThemePreview(this@SettingsActivity, theme), LinearLayout.LayoutParams(ui.dp(104), ui.dp(44)))
                addView(TextView(this@SettingsActivity).apply {
                    text = theme.name
                    textSize = 13f
                    setTextColor(ui.text)
                    gravity = Gravity.CENTER
                    setPadding(0, ui.dp(6), 0, 0)
                })
            }
            views += tile
            tile.setOnClickListener {
                views.forEach { ui.outline(it, it === tile) }
                ui.preferences.edit().putString(Theme.KEY, theme.id).apply()
                layouts.invalidateAll()
            }
            ui.outline(tile, theme.id == chosen)
            tiles.addView(tile, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = ui.dp(8)
            })
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(tiles)
        }
    }
}

/**
 * The look the settings pages share: sections of rounded cards on the
 * window background, segmented buttons for short choices, the system's
 * accent colour where Android has one.
 */
class Ui(private val activity: Activity) {
    private val density = activity.resources.displayMetrics.density
    fun dp(value: Int) = (value * density).toInt()

    val preferences get() = Dictionaries.preferences(activity)

    val accent: Int = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        activity.getColor(android.R.color.system_accent1_200)
    } else {
        0xFF8AB4F8.toInt()
    }
    private val onAccent = 0xFF101318.toInt()

    /** Text colours: the system theme's own defaults are dim enough to look disabled. */
    val text = 0xFFECEDF1.toInt()
    val secondaryText = 0xFFC2C5CC.toInt()
    private val cardColor = 0x14FFFFFF
    private val idleColor = 0x1FFFFFFF

    fun column() = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(8), dp(16), dp(24))
    }

    /** A scrolling page around `content`, kept out from under the system bars. */
    fun page(content: View) = ScrollView(activity).apply {
        addView(content)
        setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
    }

    fun rounded(color: Int, radius: Int = 16) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
    }

    fun heading(text: String) = TextView(activity).apply {
        this.text = text
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(accent)
        setPadding(dp(4), dp(20), 0, dp(8))
    }

    fun label(text: String) = TextView(activity).apply {
        this.text = text
        textSize = 15f
        setTextColor(secondaryText)
        setPadding(0, dp(12), 0, dp(8))
    }

    fun card(vararg children: View) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(cardColor, 20)
        setPadding(dp(16), dp(8), dp(16), dp(12))
        for (child in children) addView(child)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        }
    }

    fun toggle(label: String, key: String, default: Boolean = false, changed: () -> Unit = {}) = Switch(activity).apply {
        text = label
        setTextColor(this@Ui.text)
        textSize = 17f
        minHeight = dp(52)
        isChecked = preferences.getBoolean(key, default)
        setOnCheckedChangeListener { _, checked ->
            preferences.edit().putBoolean(key, checked).apply()
            changed()
        }
    }

    fun primaryButton(text: String) = Button(activity).apply {
        this.text = text
        isAllCaps = false
        textSize = 16f
        setTextColor(onAccent)
        background = rounded(accent, 24)
        minHeight = dp(52)
    }

    fun textButton(text: String) = TextView(activity).apply {
        this.text = text
        textSize = 15f
        setTextColor(accent)
        gravity = Gravity.CENTER
        minHeight = dp(48)
        setPadding(dp(16), 0, dp(4), 0)
    }

    /** A row with a title and a summary below it that opens another page. */
    fun navigationRow(title: String, summary: TextView, open: () -> Unit) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(64)
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(activity).apply {
                text = title
                textSize = 17f
                setTextColor(this@Ui.text)
            })
            addView(summary)
        }
        addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(activity).apply {
            text = "›"
            textSize = 24f
            setTextColor(secondaryText)
        })
        setOnClickListener { open() }
    }

    /**
     * Buttons side by side, `columns` to a row, of which one is chosen:
     * `options` are id to name, `chosen` the id chosen now; `choose` runs
     * when another is tapped.
     */
    fun segmented(
        options: List<Pair<String, CharSequence>>,
        chosen: String,
        columns: Int = options.size,
        choose: (String) -> Unit,
    ): View {
        val grid = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val buttons = mutableListOf<Pair<String, TextView>>()
        // Every button as tall as the label with the most lines, counting a long one as wrapping once.
        val width = activity.resources.displayMetrics.widthPixels / density / columns
        val lines = options.maxOf { (_, name) ->
            name.toString().split('\n').sumOf { line -> if (line.length * 8.5f > width - 24) 2 else 1 }
        }
        val tallest = dp(24 + 20 * lines)
        fun paint(current: String) {
            for ((id, button) in buttons) {
                val on = id == current
                button.background = rounded(if (on) accent else idleColor, 12)
                button.setTextColor(if (on) onAccent else 0xFFE8E8EC.toInt())
            }
        }
        for (start in options.indices step columns) {
            // Not aligned on the text's baseline, which drops a two-line button below its neighbour.
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                isBaselineAligned = false
            }
            for ((id, name) in options.subList(start, minOf(start + columns, options.size))) {
                val button = TextView(activity).apply {
                    text = name
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setPadding(dp(6), 0, dp(6), 0)
                    setOnClickListener {
                        paint(id)
                        choose(id)
                    }
                }
                buttons += id to button
                row.addView(button, LinearLayout.LayoutParams(0, tallest, 1f).apply {
                    setMargins(dp(3), dp(3), dp(3), dp(3))
                })
            }
            grid.addView(row)
        }
        paint(chosen)
        return grid
    }

    /**
     * A number between the ends of `range` in steps, kept under `key` once the
     * finger lifts; `describe` says what a value reads as beside the label.
     */
    fun slider(
        title: String,
        key: String,
        range: IntRange,
        default: Int,
        step: Int = 5,
        describe: (Int) -> String = { "$it %" },
        changed: () -> Unit = {},
    ): View {
        val value = TextView(activity).apply {
            textSize = 15f
            setTextColor(accent)
        }
        val bar = SeekBar(activity).apply {
            max = (range.last - range.first) / step
            progress = (preferences.getInt(key, default).coerceIn(range) - range.first) / step
            progressTintList = ColorStateList.valueOf(accent)
            thumbTintList = ColorStateList.valueOf(accent)
        }
        fun now() = range.first + bar.progress * step
        value.text = describe(now())
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                value.text = describe(now())
            }

            override fun onStartTrackingTouch(bar: SeekBar) {}

            override fun onStopTrackingTouch(bar: SeekBar) {
                preferences.edit().putInt(key, now()).apply()
                changed()
            }
        })
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(label(title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(value)
            })
            addView(bar)
        }
    }

    /** Marks a tile as chosen with an accent outline. */
    fun outline(view: View, on: Boolean) {
        view.background = GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setStroke(dp(2), if (on) accent else 0x00000000)
        }
    }
}

/**
 * A strip of the keyboard in a theme's colours: one hexagon per QWERTZ row
 * colour and one in the colour of the other keys, on the theme's background.
 */
private class ThemePreview(context: Context, private val theme: Theme) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val corner = resources.displayMetrics.density * 10

    override fun onDraw(canvas: Canvas) {
        paint.color = theme.background
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), corner, corner, paint)
        val colors = theme.rows + theme.other
        val gap = resources.displayMetrics.density * 4
        val cell = (width - gap) / colors.size
        val radius = minOf(cell - gap, height - 2 * gap) / 2f
        for ((index, color) in colors.withIndex()) {
            paint.color = color
            val cx = gap / 2 + cell * (index + 0.5f)
            val cy = height / 2f
            val hexagon = Path()
            for (corner in 0 until 6) {
                val angle = Math.toRadians(60.0 * corner - 90)
                val x = cx + radius * Math.cos(angle).toFloat()
                val y = cy + radius * Math.sin(angle).toFloat()
                if (corner == 0) hexagon.moveTo(x, y) else hexagon.lineTo(x, y)
            }
            hexagon.close()
            canvas.drawPath(hexagon, paint)
        }
    }
}

/** The layout tiles in a row, with their drawings to redraw when the keys change. */
private class LayoutTiles(context: Context) : LinearLayout(context) {
    val previews = mutableListOf<LayoutPreview>()

    fun invalidateAll() = previews.forEach { it.requestLayout() }
}

/**
 * A layout's keys as the keyboard draws them, in the chosen theme, shrunk
 * to the width it is given; with shift and backspace when they are set up.
 */
private class LayoutPreview(context: Context, private val spaceMiddle: Boolean) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labels = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val gap = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private var handle = 0L
    private var theme = Theme.current(context)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        if (handle != 0L) Core.destroy(handle)
        val preferences = Dictionaries.preferences(context)
        val editKeys = preferences.getBoolean(KeyboardView.EDIT_KEYS, false)
        val leftKey = preferences.getString(KeyboardView.LEFT_KEY, null) ?: KeyboardView.DEFAULT_LEFT_KEY
        val (stretch, numberScale) = KeyboardView.sizes(preferences)
        handle = Core.create(width.toFloat(), stretch, numberScale, 400, spaceMiddle, leftKey, editKeys, !KeyboardView.numberRow(preferences))
        theme = Theme.current(context)
        setMeasuredDimension(width, Core.height(handle).toInt())
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (handle != 0L) Core.destroy(handle)
        handle = 0L
    }

    override fun onDraw(canvas: Canvas) {
        if (handle == 0L) return
        val corner = resources.displayMetrics.density * 8
        paint.color = theme.background
        paint.pathEffect = null
        canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), corner, corner, paint)
        val cell = Core.cellWidth(handle)
        gap.color = theme.background
        gap.strokeWidth = cell * 0.06f
        val corners = android.graphics.CornerPathEffect(cell * KeyboardView.CORNER)
        paint.pathEffect = corners
        gap.pathEffect = corners
        labels.color = theme.label
        for (index in 0 until Core.keyCount(handle)) {
            val corners = Core.outline(handle, index)
            val path = Path()
            path.moveTo(corners[0], corners[1])
            for (i in 2 until corners.size step 2) path.lineTo(corners[i], corners[i + 1])
            path.close()
            val row = Core.keyRow(handle, index)
            paint.color = if (row in theme.rows.indices) theme.rows[row] else theme.other
            canvas.drawPath(path, paint)
            canvas.drawPath(path, gap)
            val (x, y) = Core.labelPosition(handle, index).let { it[0] to it[1] }
            val label = Core.label(handle, index)
            if (label == "space") continue
            val icon = KeyboardView.KEY_ICONS[label]
            if (icon != null) {
                val drawable = context.getDrawable(icon)!!.mutate()
                drawable.setTint(theme.label)
                val half = (cell * 0.2f).toInt()
                drawable.setBounds(x.toInt() - half, y.toInt() - half, x.toInt() + half, y.toInt() + half)
                drawable.draw(canvas)
                continue
            }
            labels.textSize = cell * if (label.codePointCount(0, label.length) == 1) 0.4f else 0.24f
            canvas.drawText(label, x, y - (labels.descent() + labels.ascent()) / 2, labels)
        }
    }
}

