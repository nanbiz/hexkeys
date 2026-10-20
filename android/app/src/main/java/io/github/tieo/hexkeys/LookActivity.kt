package io.github.tieo.hexkeys

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * How the keyboard looks: its layout, keys, theme and sizes, above the
 * keyboard as they make it, at its real size where it will stand.
 */
class LookActivity : LocalizedActivity() {

    private val ui by lazy { Ui(this) }
    private val preview by lazy { KeyboardPreview(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = ui.column()
        column.addView(ui.card(
            ui.titled(getString(R.string.layout), getString(R.string.layout_info)),
            layoutPicker(),
            *leftKeyViews.toTypedArray(),
            ui.toggle(getString(R.string.edit_keys), KeyboardView.EDIT_KEYS, info = getString(R.string.edit_keys_info)) { preview.refresh() },
        ))
        column.addView(ui.heading(getString(R.string.theme)))
        column.addView(ui.card(themePicker()))
        column.addView(ui.heading(getString(R.string.size)))
        column.addView(ui.card(
            ui.stepper(getString(R.string.size_all), KeyboardView.SIZE_ALL, KeyboardView.SIZE_RANGE, KeyboardView.SIZE_DEFAULT, info = getString(R.string.size_all_info)) { preview.refresh() },
            ui.stepper(getString(R.string.size_keys), KeyboardView.SIZE_KEYS, KeyboardView.SIZE_RANGE, KeyboardView.SIZE_DEFAULT, info = getString(R.string.size_keys_info)) { preview.refresh() },
            ui.toggle(getString(R.string.number_row), KeyboardView.NUMBER_ROW, default = true, info = getString(R.string.number_row_hint)) {
                numberRowHeight.visibility = if (KeyboardView.numberRow(ui.preferences)) View.VISIBLE else View.GONE
                preview.refresh()
            },
            numberRowHeight,
        ))
        // The settings scroll above the keyboard, which stays where it will be.
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(ui.page(column).apply {
                // The keyboard below keeps the navigation bar's room.
                setOnApplyWindowInsetsListener { view, insets ->
                    @Suppress("DEPRECATION")
                    view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, 0)
                    insets
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        title = getString(R.string.look)
    }

    /** How tall the number row is, offered while there is one. */
    private val numberRowHeight by lazy {
        ui.stepper(getString(R.string.size_numbers), KeyboardView.SIZE_NUMBERS, KeyboardView.SIZE_NUMBERS_RANGE, KeyboardView.SIZE_NUMBERS_DEFAULT, info = getString(R.string.size_numbers_info)) {
            preview.refresh()
        }.also { it.visibility = if (KeyboardView.numberRow(ui.preferences)) View.VISIBLE else View.GONE }
    }

    /** What the key left of space does, offered while space is in the middle. */
    private val leftKeyViews by lazy {
        val names = mapOf(
            "comma" to getString(R.string.left_key_comma), "backspace" to getString(R.string.left_key_backspace),
            "space" to getString(R.string.left_key_space),
        )
        listOf(
            ui.titled(getString(R.string.left_key), getString(R.string.left_key_info)),
            ui.segmented(
                KeyboardView.LEFT_KEYS.map { it to names.getValue(it) },
                ui.preferences.getString(KeyboardView.LEFT_KEY, null) ?: KeyboardView.DEFAULT_LEFT_KEY,
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
                addView(LinearLayout(this@LookActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    for (theme in themes.subList(start, minOf(start + THEME_COLUMNS, themes.size))) {
                        addView(themeTile(theme, chosen.id, views), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    }
                    // A short last row keeps its tiles as wide as the others.
                    repeat(start + THEME_COLUMNS - minOf(start + THEME_COLUMNS, themes.size)) {
                        addView(View(this@LookActivity), LinearLayout.LayoutParams(0, 0, 1f))
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
            addView(ThemePreview(this@LookActivity, theme), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(44)))
            addView(TextView(this@LookActivity).apply {
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

    private companion object {
        const val THEME_COLUMNS = 3
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

