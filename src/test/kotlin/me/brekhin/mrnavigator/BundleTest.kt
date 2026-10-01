package me.brekhin.mrnavigator

import me.brekhin.mrnavigator.util.MrBundle
import me.brekhin.mrnavigator.util.TimeAgo
import org.junit.Test
import java.text.MessageFormat
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.Properties
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BundleTest {
    private fun load(name: String): Properties = Properties().apply {
        BundleTest::class.java.classLoader.getResourceAsStream(name)!!.reader(Charsets.UTF_8).use { load(it) }
    }

    private val en = load("messages/MrBundle.properties")
    private val ru = load("messages/MrBundle_ru.properties")

    @Test
    fun sameKeysInBothLanguages() {
        assertEquals(emptySet(), en.stringPropertyNames() - ru.stringPropertyNames(), "missing in MrBundle_ru")
        assertEquals(emptySet(), ru.stringPropertyNames() - en.stringPropertyNames(), "missing in MrBundle")
    }

    @Test
    fun patternsAreValid() {
        // A lone apostrophe makes MessageFormat swallow the rest of the text silently.
        val lone = Regex("(?<!')'(?!')")
        for (p in listOf(en, ru)) for (key in p.stringPropertyNames()) {
            val value = p.getProperty(key)
            assertTrue(lone.find(value) == null, "single apostrophe in $key: $value")
            MessageFormat(value) // throws on a broken {…}
        }
    }

    @Test
    fun plurals() {
        MrBundle.locale = Locale.forLanguageTag("ru")
        assertEquals(listOf("файл", "файла", "файлов", "файл", "файлов", "файла"),
            listOf(1L, 2L, 5L, 21L, 11L, 22L).map { MrBundle.plural(it, "files") })
        MrBundle.locale = Locale.ENGLISH
        assertEquals(listOf("file", "files", "files", "files"), listOf(1L, 2L, 0L, 21L).map { MrBundle.plural(it, "files") })
    }

    @Test
    fun numbersAreNotGrouped() {
        MrBundle.locale = Locale.ENGLISH
        assertEquals("12345 minutes ago", MrBundle.message("time.ago", 12345L, "minutes"))
    }

    @Test
    fun timeAgoInEnglish() {
        MrBundle.locale = Locale.ENGLISH
        val now = Instant.parse("2026-10-01T12:00:00Z")
        val utc = ZoneId.of("UTC")
        assertEquals("just now", TimeAgo.format("2026-10-01T11:59:30Z", now, utc))
        assertEquals("1 minute ago", TimeAgo.format("2026-10-01T11:59:00Z", now, utc))
        assertEquals("22 minutes ago", TimeAgo.format("2026-10-01T11:38:00Z", now, utc))
        assertEquals("3 hours ago", TimeAgo.format("2026-10-01T09:00:00Z", now, utc))
        assertEquals("yesterday", TimeAgo.format("2026-09-30T08:00:00Z", now, utc))
        assertEquals("21 days ago", TimeAgo.format("2026-09-10T08:00:00Z", now, utc))
        assertEquals("1 Jun 2026", TimeAgo.format("2026-06-01T08:00:00Z", now, utc))
    }

    @Test
    fun noRussianLiteralsInCode() {
        val literal = Regex("\"[^\"\\n]*[А-Яа-яЁё][^\"\\n]*\"")
        val offenders = java.io.File("src/main/kotlin").walk().filter { it.extension == "kt" }.flatMap { f ->
            f.readLines().withIndex()
                .filter { (_, line) -> !line.trimStart().startsWith("//") && !line.trimStart().startsWith("*") && literal.containsMatchIn(line) }
                .map { (i, line) -> "${f.name}:${i + 1}: ${line.trim()}" }
        }.toList()
        assertEquals(emptyList(), offenders)
    }

    @Test
    fun everyHostingHasItsStrings() {
        for (lang in listOf("en", "ru")) {
            MrBundle.locale = Locale.forLanguageTag(lang)
            for (t in me.brekhin.mrnavigator.api.HostingType.entries) {
                MrBundle.message("connection.hint.${t.name}")
                t.usernameLabel?.let { MrBundle.message(it) }
            }
        }
    }

    @Test
    fun everyKeyUsedInCodeExists() {
        val code = java.io.File("src/main/kotlin").walk().filter { it.extension == "kt" }.flatMap { it.readLines() }.toList()
        // Every key-shaped literal on a line that calls msg() — so msg(if (…) "a" else "b") counts too.
        val key = Regex(""""([a-z][a-zA-Z0-9]*(?:\.[a-zA-Z0-9]+)+)"""")
        // MrBundle.plural(n, "files") reads files.one / files.few / files.many.
        val plural = Regex("""plural\([^;]*?,\s*"([a-zA-Z0-9.]+)"\)""")
        val pluralBases = code.flatMap { line -> plural.findAll(line).map { it.groupValues[1] } }.toSet()
        val plurals = pluralBases.flatMap { k -> listOf("one", "few", "many").map { "$k.$it" } }
        val used = code.filter { "msg(" in it }.flatMap { line -> key.findAll(line).map { it.groupValues[1] } } - pluralBases
        // Built from a template by the language combo of the settings.
        val languages = listOf("auto", "en", "ru").map { "settings.language.$it" }
        assertTrue(pluralBases.isNotEmpty())
        assertEquals(emptySet(), (used + plurals + languages).toSet() - en.stringPropertyNames())
    }
}
