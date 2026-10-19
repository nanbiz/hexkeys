package io.github.tieo.hexkeys

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** One downloadable dictionary from the catalog in the assets. */
data class Language(val name: String, val locale: String, val file: String, val license: String) {
    /**
     * The language's name in the app's language: the catalog's English one, or, where the app
     * speaks another, the one the system knows it by when it knows it at all.
     */
    fun title(context: Context): String {
        val app = AppLocale.locale(context)
        if (app.language == "en") return name
        when (locale) {
            "hi_zz" -> return AppLocale.text(context, R.string.language_hi_zz)
            "sr_zz" -> return AppLocale.text(context, R.string.language_sr_zz)
            "zgh_zz" -> return AppLocale.text(context, R.string.language_zgh_zz)
        }
        val known = Locale.forLanguageTag(locale.replace('_', '-'))
        val shown = known.getDisplayName(app)
        return if (shown.isBlank() || shown.equals(known.toLanguageTag(), ignoreCase = true) || shown.equals(locale, ignoreCase = true)) name else shown
    }

    /** Whether `query` is in the name, as the catalog has it or as the app shows it. */
    fun matches(context: Context, query: String) =
        name.contains(query, ignoreCase = true) || title(context).contains(query, ignoreCase = true)
}

/**
 * Which dictionaries the keyboard suggests from and where they live: the
 * built-in English one in the assets, the others downloaded into the app's
 * files from the aosp-dictionaries repository, at the commit the catalog
 * was built from (tools/dictionaries.py). Each comes with its language's
 * context counts where there are any (tools/contexts.py): English's in the
 * assets, the others from the hexkeys release [CONTEXTS], checked against
 * the hashes in the assets' contexts.tsv.
 */
object Dictionaries {
    const val BUILT_IN = "main_en_us.dict"
    private const val COMMIT = "ff0cd52be8d9530200fefe8b296c53d4f662091e"
    private const val BASE = "https://codeberg.org/Helium314/aosp-dictionaries/raw/commit/$COMMIT/dictionaries/"

    /** The release holding every language's context counts but English's, gzipped. */
    private const val CONTEXTS = "https://github.com/nanbiz/hexkeys/releases/download/contexts-1/"

    const val ENABLED = "dictionaries"
    /** What autocorrect changes: [AUTOCORRECT_MODES] off, only obvious slips, or every mistake, the default as on other keyboards. */
    const val AUTOCORRECT_MODE = "autocorrect_mode"
    val AUTOCORRECT_MODES = listOf("off", "obvious", "all")

    fun autocorrectMode(context: Context): String {
        val preferences = preferences(context)
        return preferences.getString(AUTOCORRECT_MODE, null)?.takeIf { it in AUTOCORRECT_MODES } ?: "all"
    }
    const val LEARNING = "learning"

    /** Bumped when the learned words are deleted, so the keyboard drops them too. */
    const val LEARNED_RESET = "learned_reset"

    fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun catalog(context: Context): List<Language> =
        context.assets.open("dictionaries.tsv").bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .map { line -> line.split('\t').let { Language(it[0], it[1], it[2], it[3]) } }

    fun enabled(context: Context): Set<String> =
        preferences(context).getStringSet(ENABLED, null) ?: setOf(BUILT_IN)

    /**
     * The languages to start with, as every keyboard does: English, which
     * is built in, and each of the phone's own languages the catalog has,
     * by its country variant where there is one.
     */
    fun deviceLanguages(context: Context): Set<String> {
        val catalog = catalog(context)
        val locales = android.content.res.Resources.getSystem().configuration.locales
        val chosen = mutableSetOf(BUILT_IN)
        for (i in 0 until locales.size()) {
            val locale = locales[i]
            val exact = "${locale.language}_${locale.country}".lowercase()
            val match = catalog.firstOrNull { it.locale.lowercase() == exact }
                ?: catalog.firstOrNull { it.locale.lowercase() == locale.language.lowercase() }
            if (match != null) chosen += match.file
        }
        return chosen
    }

    /** Whether languages have been chosen yet, or the phone's are still to be taken. */
    fun chosen(context: Context) = preferences(context).contains(ENABLED)

    fun setEnabled(context: Context, files: Set<String>) {
        preferences(context).edit().putStringSet(ENABLED, files).apply()
    }

    private fun downloaded(context: Context, file: String) = File(File(context.filesDir, "dictionaries"), file)

    /** The language of a dictionary, as its context counts are named: "de" for de_at. */
    fun languageCode(context: Context, file: String) =
        (catalog(context).firstOrNull { it.file == file }?.locale ?: "en").substringBefore('_').lowercase()

    /** The languages with context counts to download, by the SHA-256 of their file. */
    private fun contextHashes(context: Context): Map<String, String> =
        context.assets.open("contexts.tsv").bufferedReader().readLines()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .associate { line -> line.split('\t').let { it[0] to it[1] } }

    private fun contextsFile(context: Context, language: String) = File(File(context.filesDir, "contexts"), "$language.tsv.gz")

    fun isAvailable(context: Context, file: String) = file == BUILT_IN || downloaded(context, file).exists()

    /** Whether the dictionary's language has context counts that are not downloaded yet. */
    fun missingContexts(context: Context, file: String): Boolean {
        val language = languageCode(context, file)
        return language in contextHashes(context) && !contextsFile(context, language).exists()
    }

    /** The context counts of the dictionary's language, or null where there are none. */
    fun readContexts(context: Context, file: String): String? {
        val language = languageCode(context, file)
        if (language == "en") return context.assets.open("contexts/en.tsv").bufferedReader().use { it.readText() }
        val packed = contextsFile(context, language)
        if (!packed.exists()) return null
        return java.util.zip.GZIPInputStream(packed.inputStream()).bufferedReader().use { it.readText() }
    }

    /** The dictionary's bytes, or null when it has not been downloaded. */
    fun read(context: Context, file: String): ByteArray? = when {
        file == BUILT_IN -> context.assets.open(file).use { it.readBytes() }
        downloaded(context, file).exists() -> downloaded(context, file).readBytes()
        else -> null
    }

    /**
     * Downloads [file] and its language's context counts, whichever is
     * missing, reporting progress over both from 0 to 1; blocks, so it runs
     * off the main thread. Each file only appears once complete, and the
     * counts only when their hash matches the catalog's. The counts only
     * refine autocorrect, so the dictionary is kept when they fail, and
     * [downloadContexts] tries them again.
     */
    fun download(context: Context, file: String, progress: (Float) -> Unit) {
        val language = languageCode(context, file)
        val dictionary = if (file != BUILT_IN && !downloaded(context, file).exists()) open(BASE + file) else null
        val counts = if (missingContexts(context, file)) runCatching { open("$CONTEXTS$language.tsv.gz") }.getOrNull() else null
        try {
            val total = listOfNotNull(dictionary, counts).sumOf { it.contentLengthLong.coerceAtLeast(0) }
            var done = 0L
            val read = { count: Int ->
                done += count
                if (total > 0) progress(done.toFloat() / total)
            }
            dictionary?.let { fetch(it, downloaded(context, file), null, read) }
            counts?.let { runCatching { fetch(it, contextsFile(context, language), contextHashes(context)[language], read) } }
        } finally {
            dictionary?.disconnect()
            counts?.disconnect()
        }
    }

    /** Downloads the context counts of the dictionary's language; returns whether they are there now. */
    fun downloadContexts(context: Context, file: String): Boolean {
        if (!missingContexts(context, file)) return true
        val language = languageCode(context, file)
        return runCatching {
            val connection = open("$CONTEXTS$language.tsv.gz")
            try {
                fetch(connection, contextsFile(context, language), contextHashes(context)[language]) {}
            } finally {
                connection.disconnect()
            }
        }.isSuccess
    }

    private fun open(url: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            throw java.io.IOException("HTTP ${connection.responseCode} for $url")
        }
        return connection
    }

    /** Stores what `connection` sends as `target`, if its SHA-256 is `expected` where that is given. */
    private fun fetch(connection: HttpURLConnection, target: File, expected: String?, read: (Int) -> Unit) {
        target.parentFile?.mkdirs()
        val partial = File(target.path + ".part")
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        try {
            connection.inputStream.use { input ->
                partial.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        read(count)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (expected != null && actual != expected) throw java.io.IOException("${target.name} does not match its hash")
            if (!partial.renameTo(target)) throw java.io.IOException("cannot store ${target.name}")
        } finally {
            partial.delete()
        }
    }

    private fun learnedFile(context: Context) = File(context.filesDir, "learned.tsv")

    /** Words learned from typing, with how often each was kept. */
    fun learned(context: Context): Map<String, Int> {
        val file = learnedFile(context)
        if (!file.exists()) return emptyMap()
        return file.readLines().mapNotNull { line ->
            val (word, times) = line.split('\t').takeIf { it.size == 2 } ?: return@mapNotNull null
            times.toIntOrNull()?.let { word to it }
        }.toMap()
    }

    fun saveLearned(context: Context, words: Map<String, Int>) {
        learnedFile(context).writeText(words.entries.joinToString("") { "${it.key}\t${it.value}\n" })
    }

    fun deleteLearned(context: Context) {
        learnedFile(context).delete()
        val preferences = preferences(context)
        preferences.edit().putInt(LEARNED_RESET, preferences.getInt(LEARNED_RESET, 0) + 1).apply()
    }

    /** Deletes a dictionary, and its language's counts unless another enabled dictionary shares them. */
    fun delete(context: Context, file: String) {
        if (file == BUILT_IN) return
        downloaded(context, file).delete()
        val language = languageCode(context, file)
        if (enabled(context).none { it != file && languageCode(context, it) == language }) contextsFile(context, language).delete()
    }
}
