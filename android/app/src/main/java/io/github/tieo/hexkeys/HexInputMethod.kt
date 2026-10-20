package io.github.tieo.hexkeys

import android.content.Intent
import java.util.concurrent.Executors
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Size
import android.view.ViewGroup
import android.view.inputmethod.InlineSuggestionsRequest
import android.view.inputmethod.InlineSuggestionsResponse
import android.widget.inline.InlinePresentationSpec
import android.annotation.TargetApi
import androidx.autofill.inline.UiVersions
import androidx.autofill.inline.v1.InlineSuggestionUi
import android.content.SharedPreferences
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo

/** The input method: hands the keyboard view the app it types into. */
class HexInputMethod : InputMethodService() {

    private var keyboard: KeyboardView? = null
    private var frame: KeyboardFrame? = null
    /** The lexicon in use; read and replaced on the [Core.words] thread only, once created. */
    @Volatile
    private var lexicon = 0L

    /** What holding a letter offers in the languages loaded, for keyboard views made later. */
    private var accents = ""

    /** Reads dictionaries one set at a time, away from the main thread. */
    private val loader = Executors.newSingleThreadExecutor()
    /** Off in password fields and where the app asks for no suggestions. */
    private var suggesting = false

    /**
     * A correction autocorrect just made: the end of the text as typed and
     * as put in its place, and the word typed when it was one no dictionary
     * has, to learn when it is put back.
     */
    private data class Correction(val typed: String, val inserted: String, val unknown: String? = null)
    private var lastCorrection: Correction? = null

    /**
     * Where in the field a word ends that was put back by hand after a
     * correction, which autocorrect leaves as it is from then on; -1 for none.
     */
    private var keptEnd = -1

    /**
     * What the delete slide under way has deleted, last piece last, to put
     * back when it slides back: [deletedCharacters] characters, then words.
     */
    private val deleted = ArrayDeque<String>()
    private var deletedCharacters = 0

    /** The field typed in, with the text before the cursor kept here. */
    private val editor = Editor(this)

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        editor.reset(attribute.initialSelStart, attribute.initialSelEnd)
        keptEnd = -1
    }

    /** The word swiped right before the delete swipe under way, which it takes first, whole. */
    private var swipedBeforeDelete: String? = null

    /** The word a swipe just put in, while its alternatives show in the strip. */
    private var swiped: String? = null

    /** Reloads the dictionaries when the languages change in settings. */
    private val settingsChanged = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            Dictionaries.ENABLED -> {
                loadDictionaries()
                emojiSearch = null
            }
            Theme.KEY -> keyboard?.theme = Theme.current(this)
            KeyboardView.LAYOUT, KeyboardView.LEFT_KEY, KeyboardView.DELETE_FROM_LEFT, KeyboardView.DELETE_WORDS, KeyboardView.DELETE_ENDLESS, KeyboardView.EDIT_KEYS,
            KeyboardView.SIZE_ALL, KeyboardView.SIZE_KEYS, KeyboardView.SIZE_NUMBERS, KeyboardView.NUMBER_ROW ->
                keyboard?.requestLayout()
            Dictionaries.LEARNED_RESET -> {
                Core.words.execute { Core.lexiconForget(lexicon) }
                learned.clear()
            }
        }
    }

    /** The learned words as last saved, mirrored in the lexicon. */
    private val learned = mutableMapOf<String, Int>()

    override fun onCreate() {
        super.onCreate()
        lexicon = Core.lexiconCreate()
        learned.putAll(Dictionaries.learned(this))
        loadDictionaries()
        loader.execute { Voice.cleanUp(this) }
        clips.listen()
        Dictionaries.preferences(this).registerOnSharedPreferenceChangeListener(settingsChanged)
        if (BuildConfig.DEBUG) {
            registerReceiver(dictationTest, android.content.IntentFilter(DICTATION_TEST), RECEIVER_EXPORTED)
        }
    }

    /**
     * In debug builds, plays the part of a dictation on an emulator, which
     * has no microphone: `--ez start true` takes the field open now as the
     * one dictated into, `--ei pending N` shows N stretches still being
     * recognised after listening stopped, and `--es text T` lands T as
     * recognised text:
     * `adb shell am broadcast -a io.github.tieo.hexkeys.DICTATION_TEST --es text hello`
     */
    private val dictationTest = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (intent.getBooleanExtra("start", false)) dictationField = fieldOf(currentInputEditorInfo)
            if (intent.hasExtra("pending")) {
                keyboard?.listening = false
                keyboard?.recognising = intent.getIntExtra("pending", 0)
            }
            intent.getStringExtra("text")?.let { dictated(it) }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Dictionaries.preferences(this).unregisterOnSharedPreferenceChangeListener(settingsChanged)
        runCatching { unregisterReceiver(dictationTest) }
        clips.stop()
        loader.shutdown()
        voice.release()
        Core.words.execute {
            Core.lexiconDestroy(lexicon)
            lexicon = 0L
        }
    }

    /**
     * Builds a lexicon from the enabled dictionaries and the learned words
     * off the main thread, works out the accents its languages use, and
     * then puts it in place of the one in use. Large dictionaries take
     * seconds to read through; typing goes on with the old lexicon meanwhile.
     */
    private fun loadDictionaries() {
        val words = learned.toMap()
        loader.execute {
            // On the first start the phone's languages are downloaded and switched on,
            // and a language without its context counts gets them. Offline, what is
            // missing is tried again on the next start.
            var files = Dictionaries.enabled(this)
            if (!Dictionaries.chosen(this)) {
                val languages = Dictionaries.deviceLanguages(this)
                files = languages.filter { file ->
                    Dictionaries.isAvailable(this, file) ||
                        runCatching { Dictionaries.download(this, file) {} }.isSuccess
                }.toSet()
                if (files == languages) Dictionaries.setEnabled(this, files)
            }
            for (file in files) Dictionaries.downloadContexts(this, file)
            val fresh = Core.lexiconCreate()
            val added = files.filter { file -> Dictionaries.read(this, file)?.let { Core.lexiconAdd(fresh, it) } == true }
            for ((word, times) in words) Core.lexiconLearn(fresh, word, times)
            // Spellings told apart by context, for the languages that have counts.
            for ((index, file) in added.withIndex()) {
                Dictionaries.readContexts(this, file)?.let { Core.lexiconContexts(fresh, index, it) }
            }
            val accents = Core.lexiconAccents(fresh, -1)
            Core.lexiconPrepare(fresh)
            Core.words.execute {
                if (lexicon == 0L) {
                    Core.lexiconDestroy(fresh)
                    return@execute
                }
                Core.lexiconDestroy(lexicon)
                lexicon = fresh
                mainExecutor.execute {
                    lexiconFiles = added
                    pinLanguage(null)
                    this.accents = accents
                    keyboard?.accents = accents
                    refreshStrip()
                }
            }
        }
    }

    /**
     * Respells a word before the cursor where the words now after it show
     * another spelling was meant ("its" before "good" is "it's"), and keeps
     * the change for backspace to undo. `typed` is the text before the
     * cursor as the user typed it, before any correction.
     */
    private fun amend(typed: String) {
        if (!suggesting || lexicon == 0L) return
        val before = editor.textBefore()
        val kept = if (keptEnd >= 0) editor.selectionStart - keptEnd else -1
        val result = runCatching {
            Core.words.submit<Array<String>> { Core.amend(lexicon, before, kept) }
                .get(STRIP_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.getOrNull()
        if (result == null || result.size < 2) return
        val replaced = result[0].toInt()
        editor.beginBatch()
        editor.deleteBefore(replaced)
        editor.commit(result[1])
        editor.endBatch()
        keyboard?.note("amended", "${before.takeLast(replaced)} -> ${result[1]}")
        // Backspace puts back everything as typed, from the first change on.
        val after = editor.textBefore()
        val same = typed.commonPrefixWith(after).length
        val unknown = lastCorrection?.unknown
        lastCorrection = Correction(typed.substring(same), after.substring(same), unknown)
    }

    /**
     * One step of a delete slide, on the field or on the emoji search's
     * query alike: `text` is how far the slide has come through its letters
     * zone and its words zone, in thousandths, how many words its endless
     * zone has taken, and whether it just started. The letters zone takes
     * the last few letters one by one, the words zone whole words up to the
     * last several, the endless zone words beyond them, as
     * [KeyboardView.DELETE_LETTERS] and [KeyboardView.DELETE_WORD_WORDS] set. `undo` is a correction made right before, which the first letter
     * takes back, and `justSwiped` a word just swiped, which goes whole as
     * the first one.
     */
    private fun slide(target: TextTarget, text: String, undo: Correction?, justSwiped: String?) {
        val (letters, wordsThrough, endless, started) = text.split(" ").map { it.toInt() }
        var before = target.textBefore()
        if (started == 1) {
            deleted.clear()
            deletedCharacters = 0
            swipedBeforeDelete = justSwiped?.takeIf { before.endsWith(it) }
            slideUndo = undo?.takeIf { before.endsWith(it.inserted) }
            slideUndone = false
            wordPeak = Peak()
            // The zones' spans, from the text as the slide starts.
            val preferences = Dictionaries.preferences(this)
            slideLetters = preferences.getInt(KeyboardView.DELETE_LETTERS, KeyboardView.DELETE_LETTERS_DEFAULT)
            val wordWords = preferences.getInt(KeyboardView.DELETE_WORD_WORDS, KeyboardView.DELETE_WORD_WORDS_DEFAULT)
            slideWords = maxOf(0, wordWords - wholeWords(lastCharacters(before, slideLetters), before))
        }
        val word = swipedBeforeDelete
        target.beginBatch()
        // Right after a correction the first character is the correction itself:
        // what was typed comes back, as backspace brings it back, and sliding
        // back to the start puts the correction in again.
        val undoing = slideUndo
        // Any way into the letters zone takes a letter; its far end takes them all.
        val slid = if (letters == 0) 0 else maxOf(1, Math.round(letters / 1000f * slideLetters))
        val characters = if (undoing == null) slid else slid - 1
        // The text can run out within the words zone; the endless zone's words come on top.
        val askedWords = Math.round(wordsThrough / 1000f * slideWords)
        val words = wordPeak.scaled(askedWords) + endless
        if (undoing != null && characters >= 0 && !slideUndone) {
            undoCorrection(undoing)
            slideUndone = true
            before = target.textBefore()
        }
        // The words lie before the characters: fewer characters, or more, puts all of them back first.
        val wordsKept = if (deletedCharacters == maxOf(characters, 0)) words else 0
        while (deleted.size > deletedCharacters + wordsKept) target.commit(deleted.removeLast())
        while (deletedCharacters > maxOf(characters, 0)) {
            target.commit(deleted.removeLast())
            deletedCharacters -= 1
        }
        before = target.textBefore()
        // A selection goes first, as one piece, as backspace takes it; so
        // does a word just swiped.
        val selected = if (deleted.isEmpty() && characters > 0) target.selectedText() else ""
        if (selected.isNotEmpty()) {
            target.commit("")
            deleted.addLast(selected)
            deletedCharacters = 1
        } else if (deleted.isEmpty() && characters > 0 && word != null && before.endsWith(word)) {
            target.deleteBefore(word.length)
            before = before.dropLast(word.length)
            deleted.addLast(word)
            deletedCharacters = 1
        }
        while (deletedCharacters < characters && before.isNotEmpty()) {
            val piece = lastCharacter(before)
            target.deleteBefore(piece.length)
            before = before.dropLast(piece.length)
            // The copy ran short of the field's text: read on.
            if (before.isEmpty()) before = target.textBefore()
            deleted.addLast(piece)
            deletedCharacters += 1
        }
        while (deleted.size - deletedCharacters < words && before.isNotEmpty()) {
            // A word goes with the spaces after it, as ctrl and backspace take it.
            val piece = WORD_AT_END.find(before)?.value?.takeIf { it.isNotEmpty() } ?: before.takeLast(1)
            target.deleteBefore(piece.length)
            before = before.dropLast(piece.length)
            if (before.isEmpty()) before = target.textBefore()
            deleted.addLast(piece)
        }
        wordPeak.reached(askedWords, minOf(deleted.size - deletedCharacters, askedWords))
        if (undoing != null && characters < 0 && slideUndone) {
            target.deleteBefore(undoing.typed.length)
            target.commit(undoing.inserted)
            keptEnd = -1
            slideUndone = false
        }
        target.endBatch()
        val undone = if (slideUndone) undoing?.inserted?.trimEnd().orEmpty() else ""
        keyboard?.deletedText = undone + deleted.reversed().joinToString("")
    }

    /**
     * The most words a delete slide's words zone has asked for, and how many
     * it then had deleted. The zone counts on past the end of the text; coming
     * back from there puts back what was really deleted in the same proportion.
     */
    private class Peak {
        private var asked = 0
        private var deleted = 0

        fun scaled(now: Int) = if (now >= asked || asked == 0) now else (now.toLong() * deleted / asked).toInt()

        fun reached(now: Int, deletedNow: Int) {
            if (now >= asked) {
                asked = now
                deleted = deletedNow
            }
        }
    }

    private var wordPeak = Peak()

    /** The characters the letters zone of the slide under way spans, and the words its words zone does. */
    private var slideLetters = 0
    private var slideWords = 0

    /** The last `count` characters of `text` as the reader sees them, a whole emoji counting once. */
    private fun lastCharacters(text: String, count: Int): String {
        val breaks = java.text.BreakIterator.getCharacterInstance()
        breaks.setText(text)
        var at = text.length
        repeat(count) { at = breaks.preceding(at).takeIf { it != java.text.BreakIterator.DONE } ?: 0 }
        return text.substring(at)
    }

    /** The words wholly inside `tail`, the end of `text`: a word it starts inside of does not count. */
    private fun wholeWords(tail: String, text: String): Int {
        val cut = text.length > tail.length && !text[text.length - tail.length - 1].isWhitespace() && tail.firstOrNull()?.isWhitespace() == false
        val words = Regex("\\S+").findAll(tail).count()
        return if (cut) words - 1 else words
    }

    /** The correction the delete slide under way started right after, and whether it has undone it. */
    private var slideUndo: Correction? = null
    private var slideUndone = false


    /** Puts back what was typed in place of a correction; the first word put back stays as typed. */
    private fun undoCorrection(undo: Correction) {
        editor.deleteBefore(undo.inserted.length)
        val start = editor.selectionStart
        editor.commit(undo.typed)
        keptEnd = start + (WORD_AT_START.find(undo.typed)?.value?.length ?: 0)
        undo.unknown?.let { learn(it, 1) }
    }

    /** A swipe being decoded; actions arriving meanwhile wait in [waiting]. */
    private var decoding = false
    private val waiting = ArrayDeque<Pair<String, String>>()

    /** Counts the fields closed, so a swipe decoded for one never lands in the next. */
    private var fields = 0

    /** What a key, gesture or hold asks for, in the order they came. */
    private fun act(kind: String, text: String) {
        if (decoding) {
            waiting.addLast(kind to text)
            return
        }
        perform(kind, text)
    }

    /** The emoji search's query, which the keys type while it is open. */
    private val query = StringBuilder()
    private val queryText = QueryText(query)

    /**
     * The emoji names to search, read in the background when the search
     * opens: reading every language's names took long enough to hold up the
     * first letter typed. Read again when the languages change.
     */
    @Volatile
    private var emojiSearch: EmojiSearch? = null
    private var emojiSearchReading = false

    private fun readEmojiSearch() {
        if (emojiSearch != null || emojiSearchReading) return
        emojiSearchReading = true
        loader.execute {
            val search = EmojiSearch(this)
            mainExecutor.execute {
                emojiSearch = search
                emojiSearchReading = false
                if (frame?.searching == true) showEmojiResults()
            }
        }
    }

    /** Enter shows what it does in the field: the field's action, or a new line or enter key where it has none. */
    private fun showEnter() {
        // An action the app names by its own label has no icon of its own here; send stands for it.
        keyboard?.enterIcon = action?.let { ACTION_ICONS[it] ?: R.drawable.icon_send } ?: R.drawable.icon_keyboard_return
    }

    /** Ends the emoji search; enter does the field's own thing again. */
    private fun closeSearch() {
        frame?.showSearch(false)
        showEnter()
    }

    /** Opens the search over the strip, the letters below it typing the query. */
    private fun openEmojiSearch() {
        query.clear()
        readEmojiSearch()
        frame?.showSearch(true, back = {
            closeSearch()
            frame?.showEmoji(true)
        }, backspace = { searchKey("backspace", "") })
        // While searching, enter ends the search.
        keyboard?.enterIcon = R.drawable.icon_check
        showEmojiResults()
    }

    /** Keys while searching emoji go to the query; enter or the emoji found end it. */
    private fun searchKey(kind: String, text: String) {
        when (kind) {
            "commit" -> queryText.commit(text)
            // Deleting in the query works as it does in a field.
            "backspace" -> deleteCharacter(queryText)
            "delete" -> slide(queryText, text, null, null)
            "enter", "enter_action" -> {
                closeSearch()
                return
            }
            else -> return
        }
        showEmojiResults()
    }

    private fun showEmojiResults() {
        // Until something is typed, the recently used are at hand.
        val search = emojiSearch
        val found = if (query.isBlank() || search == null) frame?.emoji?.recent().orEmpty() else search.find(query.toString())
        frame?.showResults(query.toString(), found) { emoji ->
            editor.commit(emoji)
            frame?.emoji?.remember(emoji)
        }
    }

    /** Puts in the word a swipe spelled, then whatever was typed while it was decoded. */
    private fun swiped(words: Array<String>, field: Int) {
        if (field != fields) return
        decoding = false
        val view = keyboard
        if (view != null && words.isNotEmpty() && currentInputConnection != null) {
            // Swiped words are put in with a space between them, as on other keyboards.
            val last = editor.textBefore().lastOrNull()
            val space = last != null && !last.isWhitespace() && last !in OPENING
            swiped = words[0]
            view.note("words", words.joinToString(", "))
            val typed = editor.textBefore() + (if (space) " " else "") + words[0]
            editor.commit((if (space) " " else "") + words[0])
            if (Dictionaries.autocorrectMode(this) != "off") amend(typed)
            lastCorrection = null
            view.showSwipe(words)
        }
        while (!decoding && waiting.isNotEmpty()) {
            val (kind, text) = waiting.removeFirst()
            perform(kind, text)
        }
    }

    private fun perform(kind: String, text: String) {
        if (currentInputConnection == null) return
        if (frame?.searching == true) {
            searchKey(kind, text)
            return
        }
        val connection = editor
        val started = SystemClock.uptimeMillis()
        val undo = lastCorrection
        lastCorrection = null
        // Typing on past a copied text offered in the strip stops offering it.
        if (kind != "redraw" && kind != "nothing" && keyboard?.freshClip?.isNotEmpty() == true) {
            clips.spend()
            keyboard?.freshClip = ""
        }
        val justSwiped = swiped
        swiped = null
        when (kind) {
            "delete" -> slide(connection, text, undo, justSwiped)
            "swipe" -> {
                val view = keyboard ?: return
                if (!suggesting) return
                // Decoded on the words thread; what is typed meanwhile waits its turn.
                val path = view.swiped()
                val letters = view.letters
                val before = connection.textBefore()
                val field = fields
                decoding = true
                Core.words.execute {
                    val words = if (lexicon == 0L || letters == 0L) emptyArray() else Core.decodeSwipe(lexicon, letters, path, before)
                    mainExecutor.execute { swiped(words, field) }
                }
            }
            "commit" -> {
                val mode = Dictionaries.autocorrectMode(this)
                // A space typed before the strip caught up waits for it, briefly.
                if (text in SEPARATORS && mode != "off") currentStrip()
                // A separator typed inside a word splits it as it stands.
                val correction = if (connection.wordAfter().isNotEmpty()) null else when (mode) {
                    "all" -> keyboard?.correction ?: keyboard?.fix
                    "obvious" -> keyboard?.fix
                    else -> null
                }
                val typedBefore = connection.textBefore()
                if (text in SEPARATORS && correction != null) {
                    val (word, replaces) = correction
                    val typed = typedBefore.substring(typedBefore.offsetByCodePoints(typedBefore.length, -replaces))
                    connection.beginBatch()
                    connection.deleteCodePointsBefore(replaces)
                    connection.commit(word + text)
                    connection.endBatch()
                    lastCorrection = Correction(typed + text, word + text, typed)
                    keyboard?.note("corrected", "$typed -> $word")
                    if (mode != "off") amend(typedBefore + text)
                } else if (telexTyped(typedBefore, text)) {
                    // Written in Vietnamese, a letter can mark the word before it instead.
                } else {
                    if (text in SEPARATORS) keyboard?.unknown?.let { learn(it, 1) }
                    // A letter right after a swiped word starts the next word, as on
                    // other keyboards; punctuation stays attached to the word.
                    val before = connection.textBefore()
                    val space = justSwiped != null && before.endsWith(justSwiped) && text.firstOrNull()?.isLetterOrDigit() == true
                    connection.commit(if (space) " $text" else text)
                    if (text in SEPARATORS && mode != "off") amend(typedBefore + text)
                }
            }
            "backspace" -> {
                val before = connection.textBefore()
                when {
                    connection.hasSelection -> connection.commit("")
                    // Right after a swipe, backspace takes the whole word back.
                    justSwiped != null && before.endsWith(justSwiped) ->
                        connection.deleteBefore(justSwiped.length)
                    // Right after a correction, backspace puts back what was typed.
                    undo != null && before.endsWith(undo.inserted) -> {
                        connection.beginBatch()
                        undoCorrection(undo)
                        connection.endBatch()
                    }
                    else -> deleteCharacter()
                }
            }
            // Enter does the field's action wherever the field names one, as the
            // field asks. Without one, a field of several lines gets a new line,
            // written as text so an app that sends on the enter key does not.
            "enter" -> if (action == null && multiLine) connection.commit("\n") else performAction()
            "enter_action" -> performAction()
            "emoji" -> frame?.let {
                it.showEmoji(!it.emojiShown)
                readEmojiSearch()
            }
            "language" -> {
                // Guessing, then each language in turn, then guessing again.
                val count = lexiconFiles.size
                if (count == 0) return
                val order = listOf<Int?>(null) + (0 until count)
                val at = order.indexOf(pinned)
                pinLanguage(order[(at + text.toInt() + order.size) % order.size])
            }
            "cursor" -> {
                val (dx, dy) = text.split(" ").map { it.toInt() }
                connection.moveCursor(dx, dy)
                keyboard?.cursorContext = connection.textBefore().takeLast(CURSOR_CONTEXT) to connection.textAfter(CURSOR_CONTEXT)
            }
        }
        keyboard?.slow(kind, SystemClock.uptimeMillis() - started)
    }

    override fun onCreateInputView(): View = KeyboardView(
        this,
        onAction = { kind, text -> act(kind, text) },
        onPick = { word, replaces ->
            if (currentInputConnection == null) return@KeyboardView
            val connection = editor
            lastCorrection = null
            swiped = null
            // Choosing the word as typed over the suggestions keeps it on purpose.
            if (word == keyboard?.unknown) learn(word, LEARNED)
            // With the cursor inside a word, the suggestion takes the place of the whole word.
            val rest = connection.wordAfter()
            connection.beginBatch()
            connection.deleteAfter(rest.length)
            connection.deleteCodePointsBefore(replaces - rest.codePointCount(0, rest.length))
            val spaced = connection.textAfter(1).firstOrNull()?.isWhitespace() == true
            connection.commit(if (spaced) word else "$word ")
            connection.endBatch()
            // A space already after the word is stepped over rather than doubled.
            if (spaced) connection.moveCursor(1, 0)
            if (Dictionaries.autocorrectMode(this) != "off") amend(connection.textBefore())
        },
        onSettings = {
            startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        },
        onClipboard = { frame?.let { it.showClipboard(!it.clipboardShown) } },
        onPasswords = { passwordManager()?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } },
        onVoice = { toggleVoice() },
        onHeld = { send -> releaseHeld(send) },
        onFreshClip = { clips.fresh()?.let { paste(it.text) } },
    ).let { view ->
        keyboard = view
        view.accents = accents
        // A view made anew, as on rotating, shows the languages before any suggestion does.
        showLanguage(language)
        view.logContext = { if (suggesting && currentInputConnection != null) editor.textBefore() else null }
        val emoji = EmojiPanel(
            this,
            onEmoji = { editor.commit(it) },
            onSpace = { editor.commit(" ") },
            onBackspace = { deleteCharacter() },
            onClose = { frame?.showEmoji(false) },
            onSearch = { openEmojiSearch() },
        )
        val clipboard = ClipboardPanel(
            this, clips,
            onPaste = { text ->
                paste(text)
                frame?.showClipboard(false)
            },
            onSpace = { editor.commit(" ") },
            onBackspace = { deleteCharacter() },
            onClose = { frame?.showClipboard(false) },
        )
        KeyboardFrame(this, view, emoji, clipboard).also { frame = it }
    }

    /**
     * Asks the autofill service for suggestions shaped to fit the strip:
     * as tall as it, from a square up to most of its width.
     */
    @TargetApi(Build.VERSION_CODES.R)
    override fun onCreateInlineSuggestionsRequest(uiExtras: Bundle): InlineSuggestionsRequest? {
        // Asked before the keyboard view exists, so sized from the strip's dp height.
        val height = ((KeyboardView.STRIP_DP - 8) * resources.displayMetrics.density).toInt()
        // A suggestion shows its icon, title and subtitle side by side, the title
        // before the subtitle and taking what it needs. A password manager's logins
        // for one site share the icon and the title, the site, which is on screen
        // anyway, and differ in the subtitle, the account: icon and title are left
        // out, sized to nothing, so the account shows.
        val icon = androidx.autofill.inline.common.ImageViewStyle.Builder()
            .setMaxWidth(0)
            .setMaxHeight(0)
            .setPadding(0, 0, 0, 0)
            .setLayoutMargin(0, 0, 0, 0)
            .build()
        val title = androidx.autofill.inline.common.TextViewStyle.Builder()
            .setTextSize(0f)
            .setPadding(0, 0, 0, 0)
            .setLayoutMargin(0, 0, 0, 0)
            .build()
        val subtitle = androidx.autofill.inline.common.TextViewStyle.Builder().setTextSize(INLINE_SUBTITLE_SP).build()
        val styles = UiVersions.newStylesBuilder()
            .addStyle(InlineSuggestionUi.newStyleBuilder().setStartIconStyle(icon).setTitleStyle(title).setSubtitleStyle(subtitle).build())
            .build()
        // At most as wide as the room left of the right end slot, so the service
        // shortens a long suggestion's text rather than the strip cutting it off.
        val widest = maxOf(height, KeyboardFrame.chipRoom(resources.displayMetrics.widthPixels, resources.displayMetrics.density))
        val spec = InlinePresentationSpec.Builder(Size(height, height), Size(widest, height))
            .setStyle(styles)
            .build()
        return InlineSuggestionsRequest.Builder(listOf(spec))
            .setMaxSuggestionCount(InlineSuggestionsRequest.SUGGESTION_COUNT_UNLIMITED)
            .build()
    }

    /** Counts the autofill service's answers, so only the newest one is shown. */
    private var inlineAnswer = 0

    /**
     * Shows what the autofill service offers; an empty answer clears it. The
     * service may answer again before an answer's chips are drawn, and only
     * the newest answer's chips are shown.
     */
    @TargetApi(Build.VERSION_CODES.R)
    override fun onInlineSuggestionsResponse(response: InlineSuggestionsResponse): Boolean {
        val frame = frame ?: return false
        val suggestions = response.inlineSuggestions
        val answer = ++inlineAnswer
        if (GestureLog.enabled()) {
            GestureLog.write(this, org.json.JSONObject()
                .put("time", System.currentTimeMillis())
                .put("inline", suggestions.size)
                .put("pinned", suggestions.count { it.info.isPinned })
                .put("sources", org.json.JSONArray(suggestions.map { it.info.source })))
        }
        if (suggestions.isEmpty()) {
            frame.show(emptyList())
            return true
        }
        // A suggestion is drawn once, at its final size: as wide as its content,
        // at most the room the request allowed, so a button that is only an icon
        // stays a small square. Each is shown as soon as it is drawn, so one the
        // service never finishes drawing cannot keep the others away.
        val views = arrayOfNulls<View>(suggestions.size)
        // The right end slot holds the service's button: what it pins, or where it
        // pins nothing, its last suggestion while the field is empty. Bitwarden
        // adds its vault button last and pins nothing, and marks its logins as
        // actions too, so the type tells a button from a login nowhere; once
        // something is typed, Android leaves only the suggestions matching it,
        // which drops the button, and every suggestion lines up in the row.
        val empty = editor.textBefore().isEmpty() && editor.textAfter(1).isEmpty()
        val nonePinned = suggestions.none { it.info.isPinned }
        val slotted = suggestions.mapIndexed { index, suggestion ->
            suggestion.info.isPinned || (nonePinned && empty && index == suggestions.lastIndex)
        }
        for ((index, suggestion) in suggestions.withIndex()) {
            val size = Size(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            suggestion.inflate(this, size, mainExecutor) { view ->
                if (answer != inlineAnswer) return@inflate
                views[index] = view
                frame.show(views.withIndex().mapNotNull { (i, it) -> it?.let { v -> v to slotted[i] } })
                if (GestureLog.enabled()) {
                    GestureLog.write(this, org.json.JSONObject()
                        .put("time", System.currentTimeMillis())
                        .put("chip", index)
                        .put("type", suggestion.info.type)
                        .put("width", view?.layoutParams?.width ?: -1))
                }
            }
        }
        return true
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // Hidden, the keyboard stops listening; what was said is still put in.
        voice.stop()
        // A language picked by hand holds for the field it was picked in.
        if (pinned != null) pinLanguage(null)
        // A swipe still being decoded and the keys typed meanwhile belong to the field that closed.
        fields += 1
        decoding = false
        waiting.clear()
        // Chips of an answer still being drawn belong to the field that closed.
        inlineAnswer += 1
        frame?.show(emptyList())
    }

    /** The app stays in view in landscape too, above the keyboard, rather than a full screen editor. */
    override fun onEvaluateFullscreenMode() = false

    override fun onWindowShown() {
        super.onWindowShown()
        keyboard?.logPlacement("shown")
        // Where the window ends up once the system's slide in is over.
        keyboard?.postDelayed({ keyboard?.logPlacement("settled") }, PLACEMENT_SETTLED_MS)
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        keyboard?.logPlacement("configuration")
    }

    /** Closed, the keyboard opens next time on its keys, whichever field it is. */
    override fun onWindowHidden() {
        super.onWindowHidden()
        closePanels()
        shownField = null
    }

    /** Back to the keys: the emoji, their search and the clipboard closed. */
    private fun closePanels() {
        if (frame?.searching == true) closeSearch()
        frame?.showEmoji(false)
        frame?.showClipboard(false)
    }

    override fun onStartInputView(info: EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val password = info.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        ) || info.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val noSuggestions = info.inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
        // Nothing typed into a password or PIN is written to the debug log.
        val pin = info.inputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        keyboard?.secret = password || pin
        suggesting = !password && !noSuggestions
        swiped = null
        // Device colours follow the wallpaper and dark mode, which may have changed.
        // Another field opens on the letters, with the keys rather than a panel. The
        // same field started over keeps everything: Paseo starts its field over when
        // it is emptied, in the middle of a delete slide.
        val field = fieldOf(info)
        if (field != shownField) {
            keyboard?.startInput()
            closePanels()
        }
        shownField = field
        keyboard?.theme = Theme.current(this)
        keyboard?.passwordsAvailable = passwordManager() != null
        keyboard?.passwordIcon = passwordManagerPackage()?.let { runCatching { packageManager.getApplicationIcon(it) }.getOrNull() }
        keyboard?.listening = voice.listening
        keyboard?.recognising = voice.pending
        keyboard?.heldText = held
        keyboard?.freshClip = clips.fresh()?.text.orEmpty()
        // The field's own action for enter, such as send or search, and whether it takes new lines.
        // A field may name an action yet ask that enter not do it (Paseo: done, which
        // closes the keyboard); enter's key event then goes to the app instead.
        val noEnterAction = info.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
        action = when {
            info.actionLabel != null -> info.actionId
            noEnterAction -> null
            else -> (info.imeOptions and EditorInfo.IME_MASK_ACTION).takeIf { it in ACTION_ICONS }
        }
        multiLine = info.inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
        if (GestureLog.enabled()) {
            GestureLog.write(this, org.json.JSONObject()
                .put("time", System.currentTimeMillis())
                .put("field", info.packageName)
                .put("inputType", Integer.toHexString(info.inputType))
                .put("imeOptions", Integer.toHexString(info.imeOptions))
                .put("actionLabel", info.actionLabel?.toString())
                .put("actionId", info.actionId))
        }
        showEnter()
        refreshStrip()
    }

    /** The field's action for enter, if it has one, and whether enter starts a new line in it. */
    private var action: Int? = null
    private var multiLine = false

    /** Performs the field's action, or where it has none sends the enter key, which apps take as their own. */
    private fun performAction() {
        val id = action
        if (id != null) currentInputConnection?.performEditorAction(id) else editor.sendKey(KeyEvent.KEYCODE_ENTER)
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int, newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        val started = SystemClock.uptimeMillis()
        editor.selectionChanged(newSelStart, newSelEnd)
        refreshStrip()
        keyboard?.slow("selection", SystemClock.uptimeMillis() - started)
    }

    /** The last character of `text` as the reader sees it, an emoji or a letter with its marks. */
    private fun lastCharacter(text: String): String {
        val breaks = java.text.BreakIterator.getCharacterInstance()
        breaks.setText(text)
        return text.substring(breaks.preceding(text.length).coerceAtLeast(0))
    }

    /**
     * Deletes the character before the cursor as the reader sees it: a whole
     * emoji, flag or letter with its marks, however many code points it is.
     */
    private fun deleteCharacter(target: TextTarget = editor) {
        if (currentInputConnection == null) return
        if (target.hasSelection) {
            target.commit("")
            return
        }
        val before = target.textBefore()
        if (before.isNotEmpty()) target.deleteBefore(lastCharacter(before).length)
    }

    /**
     * The app of the autofill service the phone uses, if one is set up. A
     * keyboard cannot ask it to fill a field, only the app being typed into
     * can; opening it lets the user look up and copy what the field needs.
     */
    private fun passwordManager(): Intent? =
        passwordManagerPackage()?.let { packageManager.getLaunchIntentForPackage(it) }

    /** The app of the autofill service chosen in the system's settings, which is the password manager. */
    private fun passwordManagerPackage(): String? {
        val service = android.provider.Settings.Secure.getString(contentResolver, "autofill_service") ?: return null
        val component = android.content.ComponentName.unflattenFromString(service) ?: return null
        return component.packageName.takeIf { it != packageName }
    }

    private val voice by lazy { Voice(this) }

    /**
     * Starts listening, or stops and puts in what was said, with a space
     * before it where it follows a word. Without a downloaded model or the
     * microphone allowed, settings open, where both are set up.
     */
    private fun toggleVoice() {
        val view = keyboard ?: return
        if (voice.listening) {
            voice.stop()
            return
        }
        if (!Voice.ready(this)) {
            startActivity(
                Intent(this, SettingsActivity::class.java)
                    .putExtra(SettingsActivity.SECTION, SettingsActivity.VOICE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            )
            return
        }
        dictationField = fieldOf(currentInputEditorInfo)
        val started = voice.start(Dictation(
            level = { view.voiceLevel(it) },
            text = { text -> dictated(text) },
            changed = {
                view.listening = voice.listening
                view.recognising = voice.pending
            },
        ))
        if (started) view.listening = true
    }

    /** The texts copied, kept for pasting; a new one is offered in the strip while fresh. */
    private val clips: Clips by lazy { Clips(this) { showFreshClip() } }

    private fun showFreshClip() {
        keyboard?.freshClip = clips.fresh()?.text.orEmpty()
    }

    /** Puts in a copied text, with a space before it where it follows a word, and stops offering the fresh one. */
    private fun paste(text: String) {
        if (currentInputConnection == null) return
        put(text)
        clips.spend()
        keyboard?.freshClip = ""
    }

    /** The field the keyboard was last shown for, as [fieldOf] names it. */
    private var shownField: String? = null

    /** The field a dictation was started in, as [fieldOf] names it. */
    private var dictationField: String? = null

    /**
     * Dictated text no field took: what landed after the keyboard closed or
     * another field took its place, kept for the user to put in by hand.
     */
    private var held = ""

    /** A field by its app, its id and its kind, to tell whether it is still the same one. */
    private fun fieldOf(info: EditorInfo?) = info?.let { "${it.packageName}/${it.fieldId}/${it.inputType}" }

    /**
     * Puts in text a dictation recognised, while the field it was started
     * in is still open with the keyboard shown; anywhere else it could land
     * in another app, so it is held instead.
     */
    private fun dictated(text: String) {
        val same = isInputViewShown && currentInputConnection != null && fieldOf(currentInputEditorInfo) == dictationField
        if (same && held.isEmpty()) {
            put(text)
        } else {
            held = if (held.isEmpty()) text else "$held $text"
            keyboard?.heldText = held
        }
    }

    /** Puts in dictated or pasted text, with a space before it where it follows a word. */
    private fun put(text: String) {
        val last = editor.textBefore().lastOrNull()
        val space = last != null && !last.isWhitespace() && last !in OPENING
        editor.commit((if (space) " " else "") + text)
    }

    /** The held text goes into the field open now, or away. */
    private fun releaseHeld(send: Boolean) {
        if (send && currentInputConnection != null) put(held)
        held = ""
        keyboard?.heldText = ""
    }

    /** Counts a word no dictionary has as kept, when learning is switched on. */
    private fun learn(word: String, times: Int) {
        if (!Dictionaries.preferences(this).getBoolean(Dictionaries.LEARNING, false)) return
        Core.words.execute {
            val count = Core.lexiconLearn(lexicon, word, times)
            mainExecutor.execute {
                learned[word] = count
                Dictionaries.saveLearned(this, learned)
            }
        }
    }

    /** The dictionaries in the lexicon, in its order. */
    private var lexiconFiles = emptyList<String>()

    /** The dictionaries from the likeliest language of the text to the least, as the last strip had them. */
    private var ranking = emptyList<Int>()

    /** The dictionary the language is taken from, as the last strip had it, and whether the user picked it. */
    private var language: Int? = null
    private var pinned: Int? = null

    /**
     * Picks a language for this field, or goes back to guessing it from the
     * words written with null.
     */
    private fun pinLanguage(index: Int?) {
        pinned = index
        Core.words.execute { if (lexicon != 0L) Core.lexiconPin(lexicon, index ?: -1) }
        showLanguage(index ?: language)
        refreshStrip()
    }

    /** Shows the language on the space bar, dim while it is only a guess. */
    private fun showLanguage(index: Int?) {
        // Holding a letter offers the language in use's accents first.
        if (index != language) {
            Core.words.execute {
                if (lexicon == 0L) return@execute
                val accents = Core.lexiconAccents(lexicon, index ?: -1)
                mainExecutor.execute {
                    this.accents = accents
                    keyboard?.accents = accents
                }
            }
        }
        language = index
        val codes = lexiconFiles.map { languageCode(it).uppercase() }
        val ranked = ranking.mapNotNull { codes.getOrNull(it) }
        val current = index?.let { codes.getOrNull(it) }
        keyboard?.spaceInUse = current != null
        keyboard?.spaceLanguages = (listOfNotNull(current) + ranked + codes).distinct()
        keyboard?.spacePinned = pinned != null
    }

    /** The ISO code of a dictionary's language. */
    private fun languageCode(file: String) = Dictionaries.languageCode(this, file)

    /**
     * Types a letter as Telex while the language in use is Vietnamese: the
     * word before the cursor is replaced by what Telex makes of it with the
     * letter after it ("tieeng" and s is "tiếng"). Returns whether it did.
     */
    private fun telexTyped(before: String, text: String): Boolean {
        val index = pinned ?: language ?: return false
        if (lexiconFiles.getOrNull(index)?.let(::languageCode) != "vi") return false
        if (text.length != 1 || !text[0].isLetter()) return false
        val word = before.takeLastWhile { it.isLetter() }
        if (word.isEmpty()) return false
        val typed = Core.telex(word, text) ?: return false
        val connection = editor
        connection.beginBatch()
        connection.deleteBefore(word.length)
        connection.commit(typed)
        connection.endBatch()
        return true
    }

    /** Counts strips asked for, so only the newest one is shown. */
    @Volatile
    private var stripRequest = 0

    /**
     * Makes sure the strip shown is the one for the text before the cursor
     * now, working it out here when the words thread has not got to it yet,
     * waiting at most [STRIP_WAIT_MS].
     */
    private fun currentStrip() {
        val view = keyboard ?: return
        val before = editor.textBefore()
        val letters = view.letters
        if (!suggesting || letters == 0L || view.stripBefore == before) return
        val whole = before + editor.wordAfter()
        val strip = runCatching {
            Core.words.submit<Array<String>> { Core.strip(lexicon, letters, whole) }
                .get(STRIP_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        }.getOrNull() ?: return
        view.showStrip(before, strip)
    }

    private fun refreshStrip() {
        val view = keyboard ?: return
        val reading = SystemClock.uptimeMillis()
        val before = if (currentInputConnection == null) null else editor.textBefore()
        view.slow("text before", SystemClock.uptimeMillis() - reading)
        // A swipe's alternatives stay until the word is changed or left.
        val word = swiped
        if (word != null && before?.endsWith(word) == true) return
        if (!suggesting || before == null) {
            view.clearSuggestions()
            return
        }
        // Worked out on the words thread; a strip overtaken by newer text is dropped.
        // With the cursor inside a word, the strip is for the whole word.
        val request = ++stripRequest
        val letters = view.letters
        val whole = before + editor.wordAfter()
        Core.words.execute {
            if (request != stripRequest || letters == 0L) return@execute
            val started = SystemClock.uptimeMillis()
            val strip = Core.strip(lexicon, letters, whole)
            val took = SystemClock.uptimeMillis() - started
            mainExecutor.execute {
                view.slow("suggest (words thread)", took)
                if (request == stripRequest) {
                    view.showStrip(before, strip)
                    ranking = strip.getOrNull(8)?.split(',')?.mapNotNull { it.toIntOrNull() }.orEmpty()
                    showLanguage(strip.getOrNull(7)?.toIntOrNull()?.takeIf { it >= 0 })
                }
            }
        }
    }

    /**
     * The band above the keys is for popups only: the app is laid out as if
     * the keyboard started at the strip, and touches on the band reach the app.
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        val view = keyboard ?: return
        if (!view.isShown) return
        val location = IntArray(2)
        view.getLocationInWindow(location)
        val contentTop = location[1] + view.headroom
        outInsets.contentTopInsets = contentTop
        outInsets.visibleTopInsets = contentTop
        outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_REGION
        outInsets.touchableRegion.set(0, contentTop, view.width, location[1] + view.height)
    }

    private companion object {
        /** The longest a space waits for the strip of the word before it, in ms. */
        const val STRIP_WAIT_MS = 300L

        /** Icons for the actions a field can give enter. */
        val ACTION_ICONS = mapOf(
            EditorInfo.IME_ACTION_GO to R.drawable.icon_arrow_forward,
            EditorInfo.IME_ACTION_SEARCH to R.drawable.icon_search,
            EditorInfo.IME_ACTION_SEND to R.drawable.icon_send,
            EditorInfo.IME_ACTION_NEXT to R.drawable.icon_keyboard_tab,
            EditorInfo.IME_ACTION_PREVIOUS to R.drawable.icon_arrow_back,
            EditorInfo.IME_ACTION_DONE to R.drawable.icon_check,
        )

        /** How often a word has to be kept to count as learned, as the core counts. */
        const val LEARNED = 2

        /** What ends a word and so triggers autocorrect. */
        val SEPARATORS = setOf(" ", ".", ",", "!", "?", ";", ":")

        /** The last word before the cursor with the spaces after it. */
        val WORD_AT_END = Regex("\\S*\\s*$")


        /** Text size of an autofill suggestion's subtitle, such as a login's account, in sp. */
        const val INLINE_SUBTITLE_SP = 14f

        /** How long after the window is shown its place is written down again, past the slide in, in ms. */
        const val PLACEMENT_SETTLED_MS = 600L

        const val DICTATION_TEST = "io.github.tieo.hexkeys.DICTATION_TEST"

        /** How much text on each side of the cursor the strip shows while it moves. */
        const val CURSOR_CONTEXT = 40

        /** The word, or the rest of one, a text starts with. */
        val WORD_AT_START = Regex("^[\\p{L}\\p{N}'’-]*")

        /** What a swiped word follows without a space. */
        const val OPENING = "([{\"'¿¡"
    }
}
