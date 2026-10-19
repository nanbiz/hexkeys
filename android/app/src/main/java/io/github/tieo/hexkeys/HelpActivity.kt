package io.github.tieo.hexkeys

import android.os.Bundle
import android.widget.TextView

/** A short explanation of everything the keyboard can do, in the app's language. */
class HelpActivity : LocalizedActivity() {

    private val ui by lazy { Ui(this) }

    /** Each section's title and text. */
    private val sections by lazy {
        listOf(
            R.string.help_typing_title to R.string.help_typing_body,
            R.string.help_keys_title to R.string.help_keys_body,
            R.string.help_space_title to R.string.help_space_body,
            R.string.help_delete_title to R.string.help_delete_body,
            R.string.help_look_title to R.string.help_look_body,
            R.string.help_more_title to R.string.help_more_body,
            R.string.help_languages_title to R.string.help_languages_body,
            R.string.help_privacy_title to R.string.help_privacy_body,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = ui.column()
        for ((title, body) in sections) {
            column.addView(ui.heading(getString(title)))
            column.addView(ui.card(TextView(this).apply {
                text = getString(body)
                textSize = 15f
                setTextColor(ui.text)
                setLineSpacing(0f, 1.15f)
                setPadding(0, ui.dp(8), 0, ui.dp(8))
            }))
        }
        setContentView(ui.page(column))
        title = getString(R.string.help_title)
    }
}
