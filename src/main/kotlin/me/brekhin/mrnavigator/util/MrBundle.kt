package me.brekhin.mrnavigator.util

import com.intellij.openapi.application.ApplicationManager
import me.brekhin.mrnavigator.settings.MrReviewSettings
import java.text.MessageFormat
import java.util.Locale
import java.util.ResourceBundle

/**
 * UI strings: messages/MrBundle.properties (English) and MrBundle_ru.properties.
 * The language is the plugin's own setting rather than the IDE's: JetBrains ships no Russian language pack.
 */
object MrBundle {
    private const val NAME = "messages.MrBundle"
    private val RU: Locale = Locale.forLanguageTag("ru")

    /** Chosen once — a new setting applies after an IDE restart. Tests set it directly. */
    @Volatile
    var locale: Locale = detect()

    private fun detect(): Locale {
        val setting = if (ApplicationManager.getApplication() == null) "auto" else MrReviewSettings.getInstance().language
        val language = if (setting == "auto") Locale.getDefault().language else setting
        return if (language == "ru") RU else Locale.ENGLISH
    }

    fun message(key: String, vararg params: Any?): String {
        // Numbers as plain text: MessageFormat would print 12345 as "12,345".
        val args = params.map { if (it is Number) it.toString() else it }.toTypedArray()
        return MessageFormat(bundle().getString(key), locale).format(args)
    }

    /** The word for [n]: `key.one`, `key.few` or `key.many` (English uses only "one" and "many"). */
    fun plural(n: Long, key: String): String = message("$key.${form(n)}")

    private fun form(n: Long): String {
        if (locale.language != "ru") return if (n == 1L) "one" else "many"
        val m10 = n % 10
        val m100 = n % 100
        return when {
            m10 == 1L && m100 != 11L -> "one"
            m10 in 2..4 && m100 !in 12..14 -> "few"
            else -> "many"
        }
    }

    // No fallback to the OS locale: English is the base file itself.
    private fun bundle(): ResourceBundle = ResourceBundle.getBundle(
        NAME, locale, MrBundle::class.java.classLoader,
        ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES),
    )
}

fun msg(key: String, vararg params: Any?): String = MrBundle.message(key, *params)
