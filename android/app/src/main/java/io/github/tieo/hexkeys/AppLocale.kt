package io.github.tieo.hexkeys

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * The language of the app's own texts, which is English until settings say
 * German; the keyboard's typing languages are another matter, see
 * [Dictionaries].
 */
object AppLocale {
    const val KEY = "app_language"
    const val DEFAULT = "en"

    /** The languages the app's texts come in, as language tags. */
    val CHOICES = listOf("en", "de")

    fun current(context: Context): String =
        Dictionaries.preferences(context).getString(KEY, null)?.takeIf { it in CHOICES } ?: DEFAULT

    fun locale(context: Context): Locale = Locale.forLanguageTag(current(context))

    /** `base` with its resources in the app's language. */
    fun wrap(base: Context): Context {
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale(base))
        return base.createConfigurationContext(configuration)
    }

    /**
     * A text in the app's language for a context that was made before the language
     * was chosen or changed, such as the input method service that keeps running.
     */
    fun text(context: Context, id: Int, vararg args: Any): String = wrap(context).getString(id, *args)
}

/** An activity whose texts follow [AppLocale]. */
open class LocalizedActivity : Activity() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }
}
