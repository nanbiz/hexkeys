package io.github.tieo.hexkeys

import android.app.Activity
import android.app.AlertDialog
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
import android.widget.ImageView
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

        private const val THEME_COLUMNS = 3
        private const val MENU_HELP = 1
        private const val MENU_CREDITS = 2
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

        languages = TextView(this).apply {
            textSize = 15f
            setTextColor(ui.secondaryText)
        }
        column.addView(ui.heading(getString(R.string.typing)))
        column.addView(ui.card(
            ui.titled(getString(R.string.autocorrect), getString(R.string.autocorrect_info)),
            ui.segmented(
                Dictionaries.AUTOCORRECT_MODES.zip(listOf(getString(R.string.off), getString(R.string.autocorrect_obvious), getString(R.string.autocorrect_all))),
                Dictionaries.autocorrectMode(this),
            ) { id -> ui.preferences.edit().putString(Dictionaries.AUTOCORRECT_MODE, id).apply() },
            learningRow(),
            ui.navigationRow(getString(R.string.languages), languages) {
                startActivity(Intent(this, LanguagesActivity::class.java))
            },
        ))

        column.addView(ui.heading(getString(R.string.gestures)))
        val zones = DeleteZones(this, ui)
        column.addView(ui.card(
            ui.titled(getString(R.string.delete_from), getString(R.string.delete_from_info)),
            ui.segmented(
                listOf("left" to getString(R.string.left_edge), "right" to getString(R.string.right_edge)),
                if (ui.preferences.getBoolean(KeyboardView.DELETE_FROM_LEFT, false)) "left" else "right",
            ) { id ->
                ui.preferences.edit().putBoolean(KeyboardView.DELETE_FROM_LEFT, id == "left").apply()
                zones.fromLeft = id == "left"
            },
            ui.titled(getString(R.string.delete_zones), getString(R.string.delete_zones_info)),
            zones,
        ))

        column.addView(ui.heading(getString(R.string.voice), getString(R.string.voice_info)))
        column.addView(voiceCard())

        val look = ui.heading(getString(R.string.look))
        column.addView(look)
        column.addView(ui.card(
            ui.titled(getString(R.string.layout), getString(R.string.layout_info)),
            layoutPicker(),
            *leftKeyViews.toTypedArray(),
            ui.toggle(getString(R.string.edit_keys), KeyboardView.EDIT_KEYS, info = getString(R.string.edit_keys_info)) { preview.refresh() },
            ui.label(getString(R.string.theme)),
            themePicker(),
        ))

        column.addView(ui.heading(getString(R.string.size)))
        val sizes = ui.card(
            ui.slider(getString(R.string.size_all), KeyboardView.SIZE_ALL, KeyboardView.SIZE_RANGE, KeyboardView.SIZE_DEFAULT, info = getString(R.string.size_all_info)) { preview.refresh() },
            ui.slider(getString(R.string.size_keys), KeyboardView.SIZE_KEYS, KeyboardView.SIZE_RANGE, KeyboardView.SIZE_DEFAULT, info = getString(R.string.size_keys_info)) { preview.refresh() },
            ui.toggle(getString(R.string.number_row), KeyboardView.NUMBER_ROW, default = true, info = getString(R.string.number_row_hint)) {
                numberRowHeight.visibility = if (KeyboardView.numberRow(ui.preferences)) View.VISIBLE else View.GONE
                preview.refresh()
            },
            numberRowHeight,
        )
        column.addView(sizes)

        // Room at the end as tall as the preview, so everything scrolls up clear of it.
        val room = View(this)
        column.addView(room, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))
        val page = ui.page(column)
        setContentView(android.widget.FrameLayout(this).apply {
            addView(page)
            addView(preview, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        })
        // The keyboard as it will look stands where it will be, while the settings for its look are on screen.
        fun place() {
            val uncovered = page.scrollY + page.height - preview.height
            val shown = look.top < uncovered - ui.dp(64) && sizes.bottom > page.scrollY + ui.dp(64)
            preview.animate().translationY(if (shown) 0f else preview.height.toFloat()).setDuration(150).start()
            if (room.layoutParams.height != preview.height) room.layoutParams = room.layoutParams.apply { height = preview.height }
        }
        page.setOnScrollChangeListener { _, _, _, _, _ -> place() }
        preview.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> place() }
        title = getString(R.string.app_name)
        if (intent.getStringExtra(SECTION) == VOICE) showVoice(page)
    }

    /** Help and credits in the title bar's menu, the page itself being the settings. */
    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menu.add(0, MENU_HELP, 0, R.string.help)
        menu.add(0, MENU_CREDITS, 1, R.string.credits)
        return true
    }

    override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean {
        when (item.itemId) {
            MENU_HELP -> startActivity(Intent(this, HelpActivity::class.java))
            MENU_CREDITS -> startActivity(Intent(this, CreditsActivity::class.java))
            else -> return super.onOptionsItemSelected(item)
        }
        return true
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
    private val preview by lazy { KeyboardPreview(this) }

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
        val toggle = ui.toggle(getString(R.string.learn_new_words), Dictionaries.LEARNING, info = getString(R.string.learn_info))
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
        ui.slider(getString(R.string.size_numbers), KeyboardView.SIZE_NUMBERS, KeyboardView.SIZE_NUMBERS_RANGE, KeyboardView.SIZE_DEFAULT, info = getString(R.string.size_numbers_info)) {
            preview.refresh()
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
            ui.titled(getString(R.string.left_key), getString(R.string.left_key_info)),
            ui.segmented(
                KeyboardView.LEFT_KEYS.map { it to names.getValue(it) },
                ui.preferences.getString(KeyboardView.LEFT_KEY, null) ?: KeyboardView.DEFAULT_LEFT_KEY,
                columns = 4,
            ) { id ->
                ui.preferences.edit().putString(KeyboardView.LEFT_KEY, id).apply()
                preview.refresh()
            },
        ).onEach { it.visibility = if (KeyboardView.spaceMiddle(ui.preferences)) View.VISIBLE else View.GONE }
    }

    /** The two layouts, which the preview below shows as they are chosen. */
    private fun layoutPicker(): View = ui.segmented(
        listOf(KeyboardView.SPACE_BOTTOM to getString(R.string.layout_space_bottom), KeyboardView.SPACE_MIDDLE to getString(R.string.layout_space_middle)),
        if (KeyboardView.spaceMiddle(ui.preferences)) KeyboardView.SPACE_MIDDLE else KeyboardView.SPACE_BOTTOM,
    ) { id ->
        ui.preferences.edit().putString(KeyboardView.LAYOUT, id).apply()
        leftKeyViews.forEach { it.visibility = if (id == KeyboardView.SPACE_MIDDLE) View.VISIBLE else View.GONE }
        preview.refresh()
    }

    /** Every theme at once, the dark ones or the light ones, picked by a switch above them. */
    private fun themePicker(): View {
        val chosen = Theme.current(this)
        val (light, dark) = Theme.all(this).partition { it.isLight }
        val views = mutableListOf<View>()
        fun grid(themes: List<Theme>) = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            for (start in themes.indices step THEME_COLUMNS) {
                addView(LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    for (theme in themes.subList(start, minOf(start + THEME_COLUMNS, themes.size))) {
                        addView(themeTile(theme, chosen.id, views), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    }
                    // A short last row keeps its tiles as wide as the others.
                    repeat(start + THEME_COLUMNS - minOf(start + THEME_COLUMNS, themes.size)) {
                        addView(View(this@SettingsActivity), LinearLayout.LayoutParams(0, 0, 1f))
                    }
                })
            }
        }
        val grids = mapOf("dark" to grid(dark), "light" to grid(light))
        fun show(kind: String) = grids.forEach { (id, grid) -> grid.visibility = if (id == kind) View.VISIBLE else View.GONE }
        val kind = if (chosen.isLight) "light" else "dark"
        show(kind)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.segmented(listOf("dark" to getString(R.string.themes_dark), "light" to getString(R.string.themes_light)), kind) { show(it) })
            grids.values.forEach { addView(it) }
        }
    }

    private fun themeTile(theme: Theme, chosen: String, views: MutableList<View>): View {
        val tile = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
            addView(ThemePreview(this@SettingsActivity, theme), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(44)))
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
            preview.refresh()
        }
        ui.outline(tile, theme.id == chosen)
        return tile
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

    /** A [heading] with an [info] button right after it. */
    fun heading(text: String, info: String): View = withInfo(heading(text), info)

    /** A [label] with an [info] button right after it. */
    fun titled(text: String, info: String): View = withInfo(label(text), info)

    /** `title` and an [info] button on one line, the title's padding moved to the row so both centre on it. */
    private fun withInfo(title: TextView, info: String) = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(title.paddingLeft, title.paddingTop, 0, title.paddingBottom)
        addView(title.apply { setPadding(0, 0, 0, 0) })
        addView(info(info))
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

    /** [toggle] with an [info] button right after its label. */
    fun toggle(label: String, key: String, default: Boolean = false, info: String, changed: () -> Unit = {}) =
        LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(activity).apply {
                text = label
                setTextColor(this@Ui.text)
                textSize = 17f
            })
            addView(info(info))
            addView(View(activity), LinearLayout.LayoutParams(0, 0, 1f))
            addView(toggle("", key, default, changed).apply { contentDescription = label })
        }

    /** A small i that shows `text` when tapped, for what a setting does that its controls cannot show. */
    fun info(text: String) = ImageView(activity).apply {
        setImageResource(R.drawable.icon_info)
        imageTintList = ColorStateList.valueOf(secondaryText)
        contentDescription = activity.getString(R.string.info)
        val pad = dp(10)
        setPadding(pad, pad, pad, pad)
        layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
        setOnClickListener {
            AlertDialog.Builder(activity).setMessage(text).setPositiveButton(android.R.string.ok, null).show()
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
        info: String? = null,
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
                // Kept as it moves, so what shows the setting follows the finger.
                if (fromUser) {
                    preferences.edit().putInt(key, now()).apply()
                    changed()
                }
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
                if (info == null) {
                    addView(label(title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                } else {
                    addView(titled(title, info))
                    addView(View(activity), LinearLayout.LayoutParams(0, 0, 1f))
                }
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
/**
 * The number row as a delete slide crosses it, from the edge it comes in
 * from: the precise, growing and everything zones in the colours the
 * keyboard shows them in while sliding, with their two borders to drag.
 * Dragging the second border to the far end leaves out the everything zone.
 */
private class DeleteZones(context: Context, private val ui: Ui) : View(context) {
    private val preferences = ui.preferences
    private var precise = preferences.getInt(KeyboardView.DELETE_PRECISE, KeyboardView.DELETE_PRECISE_DEFAULT)
    private var all = preferences.getInt(KeyboardView.DELETE_ALL, KeyboardView.DELETE_ALL_DEFAULT)
    private val names = listOf(R.string.delete_zone_precise, R.string.delete_zone_growing, R.string.delete_zone_all).map { context.getString(it) }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ui.text
        textAlign = Paint.Align.CENTER
        textSize = ui.dp(14).toFloat()
    }
    private val handleWidth = ui.dp(6).toFloat()
    private val corner = ui.dp(12).toFloat()

    /** The border being dragged: 0 the growing zone's start, 1 the everything zone's, -1 none. */
    private var dragging = -1

    var fromLeft = preferences.getBoolean(KeyboardView.DELETE_FROM_LEFT, false)
        set(value) {
            field = value
            invalidate()
        }

    init {
        // Kept in step with the core's borders, which it holds the same way.
        precise = precise.coerceIn(KeyboardView.DELETE_PRECISE_LEAST, KeyboardView.DELETE_ALL_OFF - KeyboardView.DELETE_GROWING_LEAST)
        all = all.coerceIn(precise + KeyboardView.DELETE_GROWING_LEAST, KeyboardView.DELETE_ALL_OFF)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), ui.dp(56))
    }

    private val inset get() = handleWidth
    private val span get() = width - 2 * inset

    /** The x of `percent` of the width from the delete edge. */
    private fun x(percent: Int) = if (fromLeft) inset + span * percent / 100f else width - inset - span * percent / 100f

    override fun onDraw(canvas: Canvas) {
        val top = ui.dp(6).toFloat()
        val bottom = height - top
        val starts = listOf(0, precise, all, KeyboardView.DELETE_ALL_OFF)
        for (zone in 0..2) {
            if (starts[zone] >= starts[zone + 1]) continue
            val (left, right) = listOf(x(starts[zone]), x(starts[zone + 1])).sorted()
            // The zones meet in straight lines; only the bar's own ends are rounded.
            fill.color = KeyboardView.DELETE_ZONE_COLORS[zone] and 0x00FFFFFF or (0x90 shl 24)
            val path = Path()
            val rounded = floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)
            val atLeftEnd = left <= inset + 1
            val atRightEnd = right >= width - inset - 1
            if (atLeftEnd) rounded.fill(corner, 0, 2).also { rounded.fill(corner, 6, 8) }
            if (atRightEnd) rounded.fill(corner, 2, 6)
            path.addRoundRect(left, top, right, bottom, rounded, Path.Direction.CW)
            canvas.drawPath(path, fill)
            val name = names[zone]
            if (label.measureText(name) < right - left - ui.dp(8)) {
                canvas.drawText(name, (left + right) / 2, (top + bottom) / 2 - (label.ascent() + label.descent()) / 2, label)
            }
        }
        fill.color = ui.accent
        for (border in listOf(precise, all)) {
            val at = x(border)
            canvas.drawRoundRect(at - handleWidth / 2, 0f, at + handleWidth / 2, height.toFloat(), handleWidth / 2, handleWidth / 2, fill)
        }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent): Boolean {
        val percent = ((if (fromLeft) event.x - inset else width - inset - event.x) / span * 100).toInt()
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                val near = listOf(precise, all).map { kotlin.math.abs(x(it) - event.x) }
                dragging = when {
                    near.min() > ui.dp(32) -> -1
                    // Borders on top of each other: the one the finger moves towards.
                    near[1] <= near[0] -> 1
                    else -> 0
                }
                if (dragging < 0) return false
                parent.requestDisallowInterceptTouchEvent(true)
            }
            android.view.MotionEvent.ACTION_MOVE -> {
                val snapped = (percent + 2) / 5 * 5
                if (dragging == 0) {
                    precise = snapped.coerceIn(KeyboardView.DELETE_PRECISE_LEAST, minOf(all, KeyboardView.DELETE_ALL_OFF) - KeyboardView.DELETE_GROWING_LEAST)
                } else if (dragging == 1) {
                    all = snapped.coerceIn(precise + KeyboardView.DELETE_GROWING_LEAST, KeyboardView.DELETE_ALL_OFF)
                }
                invalidate()
            }
            android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                dragging = -1
                preferences.edit().putInt(KeyboardView.DELETE_PRECISE, precise).putInt(KeyboardView.DELETE_ALL, all).apply()
            }
        }
        return true
    }
}

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

/**
 * The keyboard as the settings make it, at its real size: the strip's room,
 * then the keys in the chosen layout, theme and sizes, as wide as the screen.
 */
private class KeyboardPreview(context: Context) : View(context) {
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
        val spaceMiddle = KeyboardView.spaceMiddle(preferences)
        handle = Core.create(width.toFloat(), stretch, numberScale, 400, spaceMiddle, leftKey, editKeys, !KeyboardView.numberRow(preferences))
        theme = Theme.current(context)
        setMeasuredDimension(width, strip + Core.height(handle).toInt() + paddingBottom)
    }

    private val strip = (KeyboardView.STRIP_DP * resources.displayMetrics.density).toInt()

    /** Drawn anew from the settings as they are now. */
    fun refresh() {
        requestLayout()
        invalidate()
    }

    init {
        // Below it the navigation bar, as below the keyboard.
        setOnApplyWindowInsetsListener { view, insets ->
            @Suppress("DEPRECATION")
            view.setPadding(0, 0, 0, insets.systemWindowInsetBottom)
            insets
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (handle != 0L) Core.destroy(handle)
        handle = 0L
    }

    override fun onDraw(canvas: Canvas) {
        if (handle == 0L) return
        paint.color = theme.background
        paint.pathEffect = null
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        canvas.translate(0f, strip.toFloat())
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

