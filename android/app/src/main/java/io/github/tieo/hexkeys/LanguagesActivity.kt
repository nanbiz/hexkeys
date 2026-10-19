package io.github.tieo.hexkeys

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import java.util.concurrent.Executors

/**
 * The languages to suggest in: the ones in use on top, then every other
 * one, with a search over all of them. Checking a language downloads its
 * dictionary, unchecking removes it, and the last one stays.
 */
class LanguagesActivity : LocalizedActivity() {

    private val downloads = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val ui by lazy { Ui(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = ui.column()
        val search = EditText(this).apply {
            hint = getString(R.string.search_languages)
            setTextColor(ui.text)
            setSingleLine()
            minHeight = ui.dp(48)
        }
        column.addView(ui.card(search))

        val enabled = Dictionaries.enabled(this)
        val (used, others) = Dictionaries.catalog(this).partition { it.file in enabled }
        val inUse = ui.heading(getString(R.string.in_use))
        val more = ui.heading(getString(R.string.more_languages))
        val usedRows = used.map { it to languageRow(it) }
        val otherRows = others.map { it to languageRow(it) }
        column.addView(inUse)
        val usedCard = ui.card(*usedRows.map { it.second }.toTypedArray())
        column.addView(usedCard)
        column.addView(more)
        val otherCard = ui.card(*otherRows.map { it.second }.toTypedArray())
        column.addView(otherCard)

        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val query = s.toString().trim()
                for ((language, row) in usedRows + otherRows) {
                    row.visibility = if (language.matches(this@LanguagesActivity, query)) View.VISIBLE else View.GONE
                }
                val anyUsed = usedRows.any { it.second.visibility == View.VISIBLE }
                inUse.visibility = if (anyUsed) View.VISIBLE else View.GONE
                usedCard.visibility = inUse.visibility
                val anyOther = otherRows.any { it.second.visibility == View.VISIBLE }
                more.visibility = if (anyOther) View.VISIBLE else View.GONE
                otherCard.visibility = more.visibility
            }
        })

        setContentView(ui.page(column))
        title = getString(R.string.languages)
    }

    override fun onDestroy() {
        super.onDestroy()
        downloads.shutdownNow()
    }

    /** A language with a check box and, while its dictionary downloads, the progress. */
    private fun languageRow(language: Language): View {
        val context: Context = this
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ui.dp(52)
        }
        val box = CheckBox(this).apply {
            text = language.title(this@LanguagesActivity)
            textSize = 17f
            setTextColor(ui.text)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            isChecked = language.file in Dictionaries.enabled(context) && Dictionaries.isAvailable(context, language.file)
        }
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(ui.dp(96), ViewGroup.LayoutParams.WRAP_CONTENT)
            max = 1000
            visibility = View.GONE
        }
        row.addView(box)
        row.addView(progress)
        box.setOnCheckedChangeListener { view, checked ->
            val enabled = Dictionaries.enabled(context)
            if (!checked) {
                if (enabled.size <= 1) {
                    view.isChecked = true
                    return@setOnCheckedChangeListener
                }
                Dictionaries.setEnabled(context, enabled - language.file)
                Dictionaries.delete(context, language.file)
                return@setOnCheckedChangeListener
            }
            if (Dictionaries.isAvailable(context, language.file)) {
                Dictionaries.setEnabled(context, enabled + language.file)
                return@setOnCheckedChangeListener
            }
            box.isEnabled = false
            progress.progress = 0
            progress.visibility = View.VISIBLE
            downloads.execute {
                val ok = runCatching {
                    Dictionaries.download(context, language.file) { done ->
                        main.post { progress.progress = (done * 1000).toInt() }
                    }
                }.isSuccess
                main.post {
                    progress.visibility = View.GONE
                    box.isEnabled = true
                    if (ok) Dictionaries.setEnabled(context, Dictionaries.enabled(context) + language.file)
                    else box.isChecked = false
                }
            }
        }
        return row
    }
}
