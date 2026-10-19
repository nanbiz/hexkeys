package io.github.tieo.hexkeys

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * What the keyboard is built from and the terms each part comes under: the
 * libraries and data it ships, and those it downloads, the dictionaries of
 * the languages in use among them. Tapping a part shows its license's text,
 * kept in the assets' licenses folder by SPDX identifier.
 */
class CreditsActivity : LocalizedActivity() {

    private val ui by lazy { Ui(this) }

    /** A part of the keyboard: what it is, who made it, and its license's SPDX identifiers. */
    private class Part(val name: String, val by: String, val licenses: List<String>)

    private val parts by lazy { listOf(
        Part(getString(R.string.credit_english_dictionary), "Android Open Source Project", listOf("Apache-2.0")),
        Part(
            getString(R.string.credit_context_counts),
            "Leipzig Corpora Collection: D. Goldhahn, T. Eckart, U. Quasthoff, Building Large Monolingual " +
                "Dictionaries at the Leipzig Corpora Collection: From 100 to 200 Languages, LREC 2012",
            listOf("CC-BY-4.0"),
        ),
        Part(getString(R.string.credit_emoji), "Unicode emoji data and Unicode CLDR", listOf("Unicode-3.0")),
        Part(getString(R.string.credit_icons), "Material Symbols by Google", listOf("Apache-2.0")),
        Part(getString(R.string.credit_letters), "anyascii by Hunter WB", listOf("ISC")),
        Part(getString(R.string.credit_speech), "sherpa-onnx by Next-gen Kaldi", listOf("Apache-2.0")),
        Part(getString(R.string.credit_speech_runtime), "ONNX Runtime by Microsoft", listOf("MIT")),
        Part(getString(R.string.credit_speech_model), "Parakeet TDT 0.6B v3 by NVIDIA", listOf("CC-BY-4.0")),
        Part(getString(R.string.credit_autofill), "AndroidX Autofill", listOf("Apache-2.0")),
        Part(
            getString(R.string.credit_rust),
            "jni, jni-sys, cesu8, combine, bytes, memchr, log, thiserror, cfg-if, unicode-normalization, tinyvec",
            listOf("MIT", "Apache-2.0", "Unicode-3.0", "Zlib", "Unlicense"),
        ),
    ) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = ui.column()
        column.addView(ui.card(*parts.map { row(it.name, it.by, it.licenses) }.toTypedArray()))

        // Downloaded dictionaries carry the terms their sources state, as the catalog lists them.
        val enabled = Dictionaries.enabled(this)
        val languages = Dictionaries.catalog(this).filter { it.file in enabled && it.file != Dictionaries.BUILT_IN }
        if (languages.isNotEmpty()) {
            column.addView(ui.heading(getString(R.string.credit_dictionaries_in_use)))
            column.addView(ui.card(*languages.map { language ->
                val terms = language.license.takeIf { it.isNotBlank() && it != "source" } ?: getString(R.string.credit_terms_unstated)
                row(language.title(this), getString(R.string.credit_dictionary_by, terms), emptyList())
            }.toTypedArray()))
        }
        setContentView(ui.page(column))
        title = getString(R.string.credits)
    }

    /** A part's name, its maker and its licenses; tapping it shows the licenses' texts. */
    private fun row(name: String, by: String, licenses: List<String>) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, ui.dp(10), 0, ui.dp(10))
        addView(TextView(context).apply {
            text = name
            textSize = 17f
            setTextColor(ui.text)
        })
        addView(TextView(context).apply {
            text = if (licenses.isEmpty()) by else "$by · ${licenses.joinToString(", ")}"
            textSize = 14f
            setTextColor(ui.secondaryText)
        })
        if (licenses.isNotEmpty()) setOnClickListener { showLicenses(name, licenses) }
    }

    private fun showLicenses(name: String, licenses: List<String>) {
        val text = licenses.joinToString("\n\n\n") { id ->
            assets.open("licenses/$id.txt").bufferedReader().use { it.readText() }
        }
        val body = TextView(this).apply {
            this.text = text
            textSize = 13f
            setTextColor(ui.text)
            setPadding(ui.dp(20), ui.dp(12), ui.dp(20), ui.dp(12))
        }
        AlertDialog.Builder(this)
            .setTitle(name)
            .setView(ScrollView(this).apply { addView(body) })
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
