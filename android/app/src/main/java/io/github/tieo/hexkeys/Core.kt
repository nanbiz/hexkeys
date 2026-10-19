package io.github.tieo.hexkeys

/** The Rust keyboard core. Every function takes the handle from [create]. */
object Core {
    init {
        System.loadLibrary("hexkeys_jni")
    }

    /**
     * The thread every call with a lexicon or [letters] handle runs on, in
     * order: suggestions and swipe decoding took up to a third of a second
     * and more on the phone, which held up the keys when it ran on the
     * main thread.
     */
    val words: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor()

    /** A copy of the keyboard's letter layer for the [words] thread; freed there with [lettersFree]. */
    external fun letters(handle: Long): Long
    external fun lettersFree(letters: Long)

    /** The last swipe's path as x, y pairs and a last 1 when it starts with a capital, else 0; empty without one. */
    external fun swiped(handle: Long): FloatArray

    /** The words a path from [swiped] spells after [before], best first. */
    external fun decodeSwipe(lexicon: Long, letters: Long, path: FloatArray, before: String): Array<String>

    /**
     * A keyboard fitted to [width] pixels; [stretch] makes cells taller than
     * regular hexagons, [numberScale] makes the number row that many times as tall as it
     * is, and a finger held still for [longPressMs] is a long press.
     * [spaceMiddle] puts space and the marks between the letters instead of
     * below them, with the key named [leftKey] left of space; [editKeys] adds shift and backspace keys; [withoutNumbers]
     * leaves out the number row and adds those keys, as in landscape.
     */
    external fun create(width: Float, stretch: Float, numberScale: Float, longPressMs: Long, spaceMiddle: Boolean, leftKey: String, editKeys: Boolean, withoutNumbers: Boolean): Long
    external fun destroy(handle: Long)
    external fun height(handle: Long): Float
    external fun keyCount(handle: Long): Int

    /** Where the number row ends; delete slides start above it. */
    external fun numbersBottom(handle: Long): Float

    /** Width of one cell, which labels are sized from. */
    external fun cellWidth(handle: Long): Float

    /** Outline corners as x0, y0, x1, y1, ... */
    external fun outline(handle: Long, index: Int): FloatArray

    /** Where the label goes, as x, y. */
    external fun labelPosition(handle: Long, index: Int): FloatArray
    external fun label(handle: Long, index: Int): String

    /** The word before the cursor with `key` typed after it as Vietnamese Telex makes it; null for a plain letter. */
    external fun telex(word: String, key: String): String?

    /** The QWERTZ row of the key's letter, 0 to 2, which themes colour it by; -1 for other keys. */
    external fun keyRow(handle: Long, index: Int): Int

    /** Whether the key is one of the number row's. */
    external fun inNumberRow(handle: Long, index: Int): Boolean

    /** Whether space is held and the keyboard moves the cursor. */
    external fun movingCursor(handle: Long): Boolean

    /** Shift: 0 off, 1 for the next letter, 2 locked. */
    external fun shiftState(handle: Long): Int

    /** While a delete slide is under way, the x it started at, the finger's x, and 1 on the far side where whole words go; empty otherwise. */
    external fun deleting(handle: Long): FloatArray

    /** The key the finger went down on, or -1. */
    external fun pressed(handle: Long): Int

    /** Touches with their [android.os.SystemClock.uptimeMillis] time. */
    external fun touchDown(handle: Long, x: Float, y: Float, time: Long)
    external fun touchMove(handle: Long, x: Float, y: Float, time: Long): Array<String>

    /**
     * Delete swipes from the left edge instead of the right, and how far a delete swipe
     * reaches to delete everything, in percent of the width from that edge.
     */
    external fun configure(handle: Long, deleteFromLeft: Boolean, deleteAllPercent: Int)

    /**
     * What lifting means, as kind and text. The kinds are commit, backspace, enter,
     * enter_action, emoji, clipboard, voice, hide, language (text 1 or -1), cursor (text "dx dy"),
     * redraw, nothing, swipe, delete_all, and delete (text "pieces words started", the last two 0 or 1).
     */
    external fun touchUp(handle: Long, x: Float, y: Float, time: Long): Array<String>
    external fun touchCancel(handle: Long)

    /** When [tick] is next due, or -1. */
    external fun deadline(handle: Long): Long

    /** What a hold that lasted long enough does, in the same form as [touchUp]. */
    external fun tick(handle: Long, time: Long): Array<String>

    /** Room above the keys a long-press popup can reach into. */
    external fun headroom(handle: Long): Float

    /** Back to the letters with shift off, for a new field. */
    external fun reset(handle: Long)

    /** How tall the strip between the keys and the headroom is, which popups reach over too. */
    external fun setStrip(handle: Long, height: Float)

    /** Choices in the open popup, 0 when none is open. */
    external fun popupCount(handle: Long): Int
    /** The choice lifting the finger takes, or -1 for none. */
    external fun popupSelected(handle: Long): Int
    external fun popupOutline(handle: Long, index: Int): FloatArray
    external fun popupLabelPosition(handle: Long, index: Int): FloatArray
    external fun popupLabel(handle: Long, index: Int): String

    /** Dictionaries for suggestions, independent of any keyboard handle. */
    external fun lexiconCreate(): Long
    external fun lexiconDestroy(lexicon: Long)

    /** Adds a `.dict` file's bytes; false when they are not a readable dictionary. */
    external fun lexiconAdd(lexicon: Long, bytes: ByteArray): Boolean

    /** Builds the swipe index ahead of the first swipe, which would otherwise wait for it. */
    external fun lexiconPrepare(lexicon: Long)

    /** Adds the context counts (assets/contexts) for the language of the dictionary added `index`th. */
    external fun lexiconContexts(lexicon: Long, index: Int, text: String)

    /**
     * The end of `before` with a word respelled for the words that followed
     * it, as the number of UTF-16 units to replace from the end and the text
     * to put there; empty when nothing changes. `kept` is where a word put
     * back by hand ends, in units from the end, -1 for none.
     */
    external fun amend(lexicon: Long, before: String, kept: Int): Array<String>
    external fun lexiconClear(lexicon: Long)

    /** Counts [word] as kept [times] more times; returns how often it now has been. */
    external fun lexiconLearn(lexicon: Long, word: String, times: Int): Int

    /** Drops every learned word. */
    external fun lexiconForget(lexicon: Long)

    /** Picks the dictionary, by the order they were added, whose language the text is in; -1 guesses again. */
    external fun lexiconPin(lexicon: Long, index: Int)

    /**
     * The suggestion strip for the text before the cursor: left, middle and
     * right word (empty when unused), how many code points a choice replaces,
     * the autocorrection, the typed word when it is not a word yet, and its
     * obvious fix (each empty when none).
     */
    external fun strip(lexicon: Long, letters: Long, before: String): Array<String>

    /** The path being swiped as x0, y0, x1, y1, ..., empty when none is. */
    external fun trail(handle: Long): FloatArray


    /** The accented letters of the lexicon's languages, as "a:ä;o:ö", those of dictionary [first] ahead (-1 for none); slow the first time, call it off the main thread. */
    external fun lexiconAccents(lexicon: Long, first: Int): String

    /** Sets what holding a letter offers, in the form [lexiconAccents] returns. */
    external fun setAccents(handle: Long, accents: String)

    /** What the capital gesture types on a punctuation key, shown small on it; empty for other keys. */
    external fun keyHint(handle: Long, index: Int): String
}
