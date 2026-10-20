package io.github.tieo.hexkeys

import android.inputmethodservice.InputMethodService
import android.view.KeyEvent

/** Text that typing and deleting work on: the field typed in, or the emoji search's query. */
interface TextTarget {
    /** The text before the cursor, or before the selection when there is one. */
    fun textBefore(): String
    val hasSelection: Boolean
    fun selectedText(): String
    /** Puts `text` in place of the selection or at the cursor. */
    fun commit(text: String)
    /** Deletes `length` UTF-16 characters before the cursor. */
    fun deleteBefore(length: Int)
    fun beginBatch() {}
    fun endBatch() {}
}

/** The emoji search's query, typed at its end. */
class QueryText(private val query: StringBuilder) : TextTarget {
    override fun textBefore() = query.toString()
    override val hasSelection get() = false
    override fun selectedText() = ""
    override fun commit(text: String) {
        query.append(text)
    }
    override fun deleteBefore(length: Int) {
        query.setLength(maxOf(0, query.length - length))
    }
}

/**
 * The field being typed in, through the service's input connection, with a
 * copy of the text before the cursor kept up to date by every edit made
 * here. Reading text from the app waits for the app's main thread, which
 * took 10 to 70 ms a read in a browser, several times per key; the copy is
 * read again only when the cursor moves for some other reason, such as the
 * user tapping elsewhere in the text or the app changing it.
 */
class Editor(private val service: InputMethodService) : TextTarget {

    private val connection get() = service.currentInputConnection

    /** The text before the selection's start, its last [CONTEXT] characters or more; null when unknown. */
    private var before: String? = null

    /** Whether [before] reaches back to the start of the field. */
    private var complete = false

    /**
     * The rest of the word the cursor is in, right after it; null when unknown.
     * Edits made here at the cursor leave it as it is.
     */
    private var after: String? = null

    /** The selection as the app last reported it, or as the last edit here left it. */
    var selectionStart = -1
        private set
    var selectionEnd = -1
        private set

    override val hasSelection get() = selectionStart != selectionEnd

    /** A new field, or the same one started over. */
    fun reset(start: Int, end: Int) {
        before = null
        after = null
        selectionStart = start
        selectionEnd = end
    }

    /**
     * The app reports where the selection is now. Where an edit here put it,
     * the copy stays; anywhere else it is read again when next needed.
     */
    fun selectionChanged(start: Int, end: Int) {
        if (start != selectionStart || end != selectionEnd) {
            before = null
            after = null
        }
        selectionStart = start
        selectionEnd = end
    }

    /** The text before the cursor, or before the selection when there is one. */
    override fun textBefore(): String {
        before?.let { return it }
        val read = connection?.getTextBeforeCursor(CONTEXT, 0)?.toString().orEmpty()
        before = read
        complete = read.length < CONTEXT
        return read
    }

    /** The rest of the word the cursor is in, after it: empty at a word's end or between words. */
    fun wordAfter(): String {
        after?.let { return it }
        val read = if (hasSelection) "" else WORD_AT_START.find(textAfter(CONTEXT))?.value.orEmpty()
        after = read
        return read
    }

    /** Deletes `length` UTF-16 characters after the cursor. */
    fun deleteAfter(length: Int) {
        if (length <= 0) return
        connection?.deleteSurroundingText(0, length)
        after = null
    }

    /** Up to `length` characters after the cursor, read from the app. */
    fun textAfter(length: Int): String = connection?.getTextAfterCursor(length, 0)?.toString().orEmpty()

    /** The selected text; empty without a selection, which needs no read. */
    override fun selectedText(): String =
        if (hasSelection) connection?.getSelectedText(0)?.toString().orEmpty() else ""

    /** Puts `text` in place of the selection or at the cursor. */
    override fun commit(text: String) {
        val known = before
        connection?.commitText(text, 1)
        val start = minOf(selectionStart, selectionEnd)
        selectionStart = start + text.length
        selectionEnd = selectionStart
        before = known?.let { trimmed(it + text) }
    }

    /** Deletes `length` UTF-16 characters before the cursor. */
    override fun deleteBefore(length: Int) {
        if (length <= 0) return
        connection?.deleteSurroundingText(length, 0)
        selectionStart = maxOf(0, selectionStart - length)
        selectionEnd = maxOf(0, selectionEnd - length)
        val known = before ?: return
        before = known.dropLast(length)
        // A copy cut short at the front could run out before the field does.
        if (!complete && before!!.length < CONTEXT) before = null
    }

    /** Deletes `count` code points before the cursor. */
    fun deleteCodePointsBefore(count: Int) {
        val text = textBefore()
        val start = runCatching { text.offsetByCodePoints(text.length, -count) }.getOrDefault(0)
        deleteBefore(text.length - start)
    }

    override fun beginBatch() {
        connection?.beginBatchEdit()
    }

    override fun endBatch() {
        connection?.endBatchEdit()
    }

    /**
     * Moves the cursor `dx` characters right and `dy` lines down, a character
     * being what the reader sees as one: a whole emoji, flag or letter with
     * its marks, so the cursor never lands inside one, where whatever is
     * typed next would split it. Sideways it is placed directly and stops at
     * the text's ends: an arrow key pressed there moves the focus out of the
     * field instead, which closes the keyboard. Up and down go by arrow keys,
     * only while there is text that way.
     */
    fun moveCursor(dx: Int, dy: Int) {
        val connection = connection ?: return
        if (dx != 0) {
            val breaks = java.text.BreakIterator.getCharacterInstance()
            val room = if (dx > 0) {
                val after = connection.getTextAfterCursor(dx * CHARACTER_LONGEST, 0)?.toString().orEmpty()
                breaks.setText(after)
                var at = 0
                repeat(dx) { at = breaks.following(at).takeIf { it != java.text.BreakIterator.DONE } ?: after.length }
                at
            } else {
                val before = connection.getTextBeforeCursor(-dx * CHARACTER_LONGEST, 0)?.toString().orEmpty()
                breaks.setText(before)
                var at = before.length
                repeat(-dx) { at = breaks.preceding(at).takeIf { it != java.text.BreakIterator.DONE } ?: 0 }
                before.length - at
            }
            // Right goes on from the selection's end and left from its start, as arrow keys do.
            val target = if (dx > 0) maxOf(0, selectionEnd) + room else maxOf(0, selectionStart) - room
            if (room > 0) {
                connection.setSelection(target, target)
                selectionStart = target
                selectionEnd = target
                before = null
                after = null
            }
        }
        if (dy != 0) {
            val textThatWay = if (dy > 0) connection.getTextAfterCursor(1, 0) else connection.getTextBeforeCursor(1, 0)
            if (!textThatWay.isNullOrEmpty()) {
                repeat(kotlin.math.abs(dy)) { sendKey(if (dy > 0) KeyEvent.KEYCODE_DPAD_DOWN else KeyEvent.KEYCODE_DPAD_UP) }
            }
        }
    }

    /** Sends a key to the app, which changes the text in its own way. */
    fun sendKey(code: Int) {
        before = null
        after = null
        service.sendDownUpKeyEvents(code)
    }

    /** Keeps the copy from growing without end, which marks it as no longer reaching the field's start. */
    private fun trimmed(text: String): String {
        if (text.length <= KEEP) return text
        complete = false
        return text.takeLast(KEEP)
    }

    companion object {
        /** Enough text before the cursor for the word being typed and the one before it. */
        const val CONTEXT = 64

        /**
         * The most UTF-16 units one character takes that the cursor steps
         * over whole: a family emoji with skin tones joined by zero width
         * joiners runs to about 30.
         */
        private const val CHARACTER_LONGEST = 32

        /** The most kept in the copy. */
        private const val KEEP = 256

        val ENTER = KeyEvent.KEYCODE_ENTER

        /** The letters, digits and marks inside a word a text starts with. */
        private val WORD_AT_START = Regex("^[\\p{L}\\p{N}'’-]+")
    }
}

/** `text` without its last character as the reader sees it, so an emoji is never cut in half. */
fun withoutLastCharacter(text: String): String {
    val breaks = java.text.BreakIterator.getCharacterInstance()
    breaks.setText(text)
    return text.substring(0, breaks.preceding(text.length).coerceAtLeast(0))
}

/** `text` without its first character as the reader sees it. */
fun withoutFirstCharacter(text: String): String {
    if (text.isEmpty()) return text
    val breaks = java.text.BreakIterator.getCharacterInstance()
    breaks.setText(text)
    return text.substring(breaks.following(0).coerceAtMost(text.length))
}
