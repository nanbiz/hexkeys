package io.github.tieo.hexkeys

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Every touch on the keyboard written down in debug builds, one JSON object
 * per line in `files/gestures.jsonl`: where the finger went and when, what
 * the core made of it, and what was typed in the end. With it, what was
 * meant can be held against what happened. The file is cut to its newer
 * half once it passes [LIMIT]. Release builds neither write nor contain it:
 * every write hangs on [BuildConfig.DEBUG], whose dead branches the release
 * build's shrinking removes.
 */
object GestureLog {
    /** About ten days of typing, so a log pulled now and then misses nothing. */
    private const val LIMIT = 32L shl 20

    /** Writes happen here, off the main thread, so the log never holds up a touch. */
    private val writer = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun enabled() = BuildConfig.DEBUG

    fun write(context: Context, record: JSONObject) {
        val line = record.toString()
        val file = File(context.filesDir, "gestures.jsonl")
        writer.execute { append(file, line) }
    }

    private fun append(file: File, line: String) {
        if (file.length() > LIMIT) {
            val lines = file.readLines()
            file.writeText(lines.drop(lines.size / 2).joinToString("\n", postfix = "\n"))
        }
        file.appendText(line + "\n")
    }
}
