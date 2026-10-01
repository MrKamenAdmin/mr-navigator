# GitHub, Bitbucket и английский UI — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** плагин ревьюит MR/PR GitLab, GitHub, Bitbucket Cloud и Bitbucket DC с несколькими подключениями одновременно, интерфейс на английском и русском.

**Architecture:** интерфейс `HostingClient` с четырьмя реализациями поверх общего `Http`; общие модели (`MergeRequest`, `FileChange`, `Discussion`, `Position`) и существующий `DiffLineMap`; подключения (тип + URL + username) в настройках, провайдер выбирается по хосту git remote; строки UI — в `MrBundle` (`messages/MrBundle*.properties`), язык — настройка плагина.

**Tech Stack:** Kotlin 2.4, IntelliJ Platform 2026.2 (GoLand), IntelliJ Platform Gradle Plugin 2.19, JUnit 4 + kotlin.test; JSON — собственный `util/Json.kt`; HTTP — `com.intellij.util.io.HttpRequests`.

**Spec:** `docs/superpowers/specs/2026-10-01-github-bitbucket-i18n-design.md`

## Global Constraints

- Сборка и тесты: `./gradlew test buildPlugin` зелёный после каждой задачи.
- Никаких новых зависимостей в `build.gradle.kts`.
- `plugin.xml` id `me.brekhin.mr-navigator`, `@State(name = "GitLabMrReviewSettings", storages = [Storage("gitlab-mr-review.xml")])` и ключ PasswordSafe `generateServiceName("GitLab MR Review", url)` не меняются.
- Код, комментарии в коде, сообщения коммитов — по-английски, в стиле окружающего кода; комментарии только там, где неочевидно.
- Каждый коммит заканчивается строками:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01EnGJB37gkEWNCmbgkHDW2c
  ```
- Все строки UI и ошибок — через `msg(key, ...)`; каждый ключ есть в `MrBundle.properties` (en) и `MrBundle_ru.properties`. Апостроф в значении — только удвоенный (`''`), кавычки в тексте — `“ ”` (en) и `« »` (ru).
- Числа в `msg(...)` передаются как есть: `MrBundle.message` превращает `Number` в строку без группировки разрядов.
- Не переводятся: `MR`, `PR`, `Approve`, `Resolve`, `Draft`, `suggestion`, `Checkout`, названия провайдеров.
- Работа идёт в ветке `multi-provider`.

## Review Focus

1. **Редирект Bitbucket Cloud `/pullrequests/{id}/diff` → `/diff/…`** — `HttpRequests` должен переслать `Authorization` на новый URL, иначе приватный репозиторий отдаст 401. Тест в Task 7: проверить вручную на приватном репо (без живого сервера юнит-тест невозможен) — отмечено в чек-листе ручной проверки Task 9.
2. **Комментарий к строке вне ханка на GitHub/Bitbucket** — «+» не показывается, контекстное действие сообщает «only lines of the diff», а не падает с 422. Тест `DiffLineMap.inHunk` в Task 5.
3. **Пустой или удалённый корневой комментарий Bitbucket Cloud с живыми ответами** — тред остаётся с ответами, без NPE. Тест в Task 7 (`threadsKeepRepliesOfDeletedRoot`).
4. **Многофайловый raw diff с бинарным файлом и переименованием без изменений** — файлы не склеиваются, пути верные. Тест в Task 6.
5. **Пользователь GitLab после обновления** — старый `serverUrl` с токеном превращается в подключение без повторного ввода токена. Логика `migrateLegacy` чистая на `State`; тест в Task 4 на `MrReviewSettings.legacyConnection(state)`.

---

### Task 1: `MrBundle`, множественное число, `TimeAgo`, настройка языка

**Files:**
- Create: `src/main/kotlin/me/brekhin/mrnavigator/util/MrBundle.kt`
- Create: `src/main/resources/messages/MrBundle.properties`
- Create: `src/main/resources/messages/MrBundle_ru.properties`
- Create: `src/test/kotlin/me/brekhin/mrnavigator/BundleTest.kt`
- Modify: `src/main/kotlin/me/brekhin/mrnavigator/util/TimeAgo.kt` (весь файл)
- Modify: `src/main/kotlin/me/brekhin/mrnavigator/settings/MrReviewSettings.kt` (State + свойство `language`)
- Modify: `src/main/kotlin/me/brekhin/mrnavigator/settings/MrReviewConfigurable.kt` (строка выбора языка)
- Modify: `src/main/kotlin/me/brekhin/mrnavigator/ui/MrDetailsPanel.kt` (два вызова `TimeAgo.plural`)
- Modify: `src/test/kotlin/me/brekhin/mrnavigator/LogicTest.kt` (`timeAgoAndStats`)

**Interfaces:**
- Produces: `object MrBundle { var locale: Locale; fun message(key: String, vararg params: Any?): String; fun plural(n: Long, key: String): String }`, top-level `fun msg(key: String, vararg params: Any?): String` в пакете `me.brekhin.mrnavigator.util`; `MrReviewSettings.language: String` (`"auto" | "en" | "ru"`).

- [ ] **Step 1: Write the failing test** — `src/test/kotlin/me/brekhin/mrnavigator/BundleTest.kt`:

```kotlin
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
}
```

В `LogicTest.timeAgoAndStats` первой строкой добавить `me.brekhin.mrnavigator.util.MrBundle.locale = java.util.Locale.forLanguageTag("ru")` (русские ожидания остаются как есть).

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew -q test --tests '*BundleTest*'`
Expected: FAIL — `Unresolved reference 'MrBundle'`.

- [ ] **Step 3: Write minimal implementation**

`src/main/kotlin/me/brekhin/mrnavigator/util/MrBundle.kt`:

```kotlin
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
```

`src/main/resources/messages/MrBundle.properties`:

```properties
time.justNow=just now
time.ago={0} {1} ago
time.yesterday=yesterday
time.minute.one=minute
time.minute.few=minutes
time.minute.many=minutes
time.hour.one=hour
time.hour.few=hours
time.hour.many=hours
time.day.one=day
time.day.few=days
time.day.many=days
files.one=file
files.few=files
files.many=files
replies.one=reply
replies.few=replies
replies.many=replies

settings.language=Language:
settings.language.comment=Takes effect after an IDE restart
settings.language.auto=Automatic (system language)
settings.language.en=English
settings.language.ru=Русский
```

`src/main/resources/messages/MrBundle_ru.properties`:

```properties
time.justNow=только что
time.ago={0} {1} назад
time.yesterday=вчера
time.minute.one=минуту
time.minute.few=минуты
time.minute.many=минут
time.hour.one=час
time.hour.few=часа
time.hour.many=часов
time.day.one=день
time.day.few=дня
time.day.many=дней
files.one=файл
files.few=файла
files.many=файлов
replies.one=ответ
replies.few=ответа
replies.many=ответов

settings.language=Язык:
settings.language.comment=Применится после перезапуска IDE
settings.language.auto=Автоматически (язык системы)
settings.language.en=English
settings.language.ru=Русский
```

`src/main/kotlin/me/brekhin/mrnavigator/util/TimeAgo.kt` — весь файл:

```kotlin
package me.brekhin.mrnavigator.util

import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** "5 minutes ago", "yesterday", "12 Sep 2026" — for ISO timestamps of the APIs. */
object TimeAgo {
    fun format(iso: String?, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): String {
        if (iso.isNullOrBlank()) return ""
        val time = try {
            OffsetDateTime.parse(iso).toInstant()
        } catch (e: Exception) {
            return iso.take(16).replace('T', ' ')
        }
        val d = Duration.between(time, now)
        val minutes = d.toMinutes()
        val hours = d.toHours()
        val days = d.toDays()
        return when {
            d.isNegative || d.seconds < 60 -> msg("time.justNow")
            minutes < 60 -> msg("time.ago", minutes, MrBundle.plural(minutes, "time.minute"))
            hours < 24 -> msg("time.ago", hours, MrBundle.plural(hours, "time.hour"))
            days == 1L -> msg("time.yesterday")
            days < 30 -> msg("time.ago", days, MrBundle.plural(days, "time.day"))
            else -> DateTimeFormatter.ofPattern("d MMM yyyy", MrBundle.locale).format(time.atZone(zone))
        }
    }
}
```

`MrReviewSettings.kt`: в `class State` после `autoStash` добавить
```kotlin
        /** "auto" (the OS language), "en" or "ru". */
        var language: String = "auto"
```
и после свойства `autoStash`:
```kotlin
    var language: String
        get() = state.language
        set(value) { state.language = value }
```

`MrReviewConfigurable.kt`, в конец `panel { … }` после `group("Git") { … }`:
```kotlin
            row(msg("settings.language")) {
                comboBox(listOf("auto", "en", "ru"), SimpleListCellRenderer.create("") { msg("settings.language.$it") })
                    .bindItem(settings::language.toNullableProperty())
                    .comment(msg("settings.language.comment"))
            }
```
импорты: `com.intellij.ui.SimpleListCellRenderer`, `com.intellij.ui.dsl.builder.bindItem`, `com.intellij.ui.dsl.builder.toNullableProperty`, `me.brekhin.mrnavigator.util.msg`.

`MrDetailsPanel.kt`:
- `TimeAgo.plural(n, "файл", "файла", "файлов")` → `MrBundle.plural(n, "files")`
- `TimeAgo.plural(replies.toLong(), "ответ", "ответа", "ответов")` → `MrBundle.plural(replies.toLong(), "replies")`
- импорт `me.brekhin.mrnavigator.util.MrBundle`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew -q test`
Expected: PASS (все тесты, включая русский `timeAgoAndStats`).

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Add MrBundle with English and Russian strings and a language setting"   # + trailer lines
```

---

### Task 2: Слой провайдеров (только GitLab, поведение не меняется)

**Files:**
- Create: `src/main/kotlin/me/brekhin/mrnavigator/api/Hosting.kt`
- Create: `src/main/kotlin/me/brekhin/mrnavigator/api/Http.kt`
- Modify: `src/main/kotlin/me/brekhin/mrnavigator/api/GitLabClient.kt` (весь файл)
- Modify: `src/main/kotlin/me/brekhin/mrnavigator/api/Models.kt` (`MergeRequest`, `Position`, `Discussion`)
- Modify: `src/main/kotlin/me/brekhin/mrnavigator/core/MrReviewService.kt`
- Modify: `src/main/kotlin/me/brekhin/mrnavigator/ui/MrDetailsPanel.kt`, `ui/ThreadPopup.kt`, `ui/MrToolWindow.kt`, `diff/CommentMarkers.kt`, `settings/MrReviewConfigurable.kt`, `ui/SetupPanel.kt`
- Modify: `src/main/resources/messages/MrBundle.properties`, `MrBundle_ru.properties`
- Test: `src/test/kotlin/me/brekhin/mrnavigator/LogicTest.kt`

**Interfaces:**
- Consumes: `msg` (Task 1).
- Produces:
  - `enum class MrFilter(key) { OPENED, REVIEW_REQUESTED, ASSIGNED, MINE, MERGED }` (в `api/Hosting.kt`, `toString()` = `msg(key)`).
  - `data class Connection(val type: HostingType, val url: String, val username: String? = null)`.
  - `enum class HostingType(title: String, prefix: Char, term: String, filters: List<MrFilter>, canSuggest: Boolean, commentsOutsideHunks: Boolean, usernameLabel: String?, fixedUrl: String?)` c `fun client(c: Connection, token: String): HostingClient`, `fun tokenPageUrl(url: String): String`, `companion fun guess(host: String): HostingType`. Пока одна константа `GITLAB`.
  - `interface HostingClient` (сигнатуры ниже).
  - `class ApiException(message: String, val status: Int = 0, cause: Throwable? = null) : IOException`.
  - `class Http(provider: String, auth: (URLConnection) -> Unit) { fun call(method: String, url: String, body: Any? = null, accept: String = "application/json"): Response }`, `class Response(val body: String, headers: Map<String, String>) { fun header(name: String): String?; fun json(): Any? }`, `Http.errorMessage(body: String?): String?` (companion, internal), top-level `fun basicOrBearer(token: String, username: String?): String`.
  - `MergeRequest(iid, title, description, state, draft, author, sourceBranch, targetBranch, webUrl, sha, diffRefs, updatedAt, userNotesCount, hasConflicts, fetchRef: String, fetchUrl: String? = null)` — без `id`, `projectId`, `sourceProjectId`.
  - `Position(..., lineRange: LineRange? = null, outdated: Boolean = false)` + `fun isOutdatedFor(head: String?): Boolean`.
  - `Discussion(id: String, notes: List<Note>, webUrl: String? = null)`.
  - `MrSession(project, connection: Connection, git, remoteName, mr, changes, discussions, approvedBy, viewed)` + `val type: HostingType`, `val ref: String` (`"!12"`), `fun isOutdated(d: Discussion): Boolean`.
  - `MrReviewService.client(): HostingClient`, `currentUser(client: HostingClient): User`.

- [ ] **Step 1: Write the failing test** — в `LogicTest` добавить:

```kotlin
    @Test
    fun apiErrorMessages() {
        assertEquals("401 Unauthorized", Http.errorMessage("""{"message":"401 Unauthorized"}"""))           // GitLab, GitHub
        assertEquals("insufficient_scope", Http.errorMessage("""{"error":"insufficient_scope"}"""))         // GitLab OAuth
        assertEquals("Bad diff", Http.errorMessage("""{"type":"error","error":{"message":"Bad diff"}}""")) // Bitbucket Cloud
        assertEquals("No such PR", Http.errorMessage("""{"errors":[{"context":null,"message":"No such PR"}]}""")) // Bitbucket DC
        assertEquals("<html>oops</html>", Http.errorMessage("<html>oops</html>"))
        assertNull(Http.errorMessage(""))
        assertEquals("Basic dTpw", basicOrBearer("p", "u"))
        assertEquals("Bearer t", basicOrBearer("t", null))
        assertEquals("Bearer t", basicOrBearer("t", " "))
    }

    @Test
    fun outdatedPositions() {
        val p = Position(null, null, "h1", "a", "a", null, 3)
        assertFalse(p.isOutdatedFor("h1"))
        assertTrue(p.isOutdatedFor("h2"))
        assertFalse(p.isOutdatedFor(null))
        assertFalse(p.copy(headSha = null).isOutdatedFor("h2"))
        assertTrue(p.copy(headSha = null, outdated = true).isOutdatedFor("h2"))
    }
```
импорты: `me.brekhin.mrnavigator.api.Http`, `me.brekhin.mrnavigator.api.basicOrBearer`.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew -q test --tests '*LogicTest*'`
Expected: FAIL — `Unresolved reference 'Http'`.

- [ ] **Step 3: Implement**

`src/main/kotlin/me/brekhin/mrnavigator/api/Http.kt`:

```kotlin
package me.brekhin.mrnavigator.api

import com.intellij.util.io.HttpRequests
import me.brekhin.mrnavigator.util.Json
import me.brekhin.mrnavigator.util.a
import me.brekhin.mrnavigator.util.msg
import me.brekhin.mrnavigator.util.obj
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLConnection
import java.util.Base64
import java.util.TreeMap

class ApiException(message: String, val status: Int = 0, cause: Throwable? = null) : IOException(message, cause)

/** Body and headers of a response. */
class Response(val body: String, private val headers: Map<String, String>) {
    fun header(name: String): String? = headers[name]
    fun json(): Any? = if (body.isBlank()) null else Json.parse(body)
}

/** Basic auth when a user name is given (API token + e-mail, DC user + token), Bearer otherwise. */
fun basicOrBearer(token: String, username: String?): String =
    if (username.isNullOrBlank()) "Bearer $token"
    else "Basic " + Base64.getEncoder().encodeToString("$username:$token".toByteArray(Charsets.UTF_8))

/**
 * Blocking HTTP through the IDE's HttpRequests, so the IDE proxy and certificate settings apply.
 * [provider] names the server in error messages; [auth] adds the credentials to every request.
 */
class Http(private val provider: String, private val auth: (URLConnection) -> Unit) {
    fun call(method: String, url: String, body: Any? = null, accept: String = "application/json"): Response {
        val builder = when (method) {
            "GET" -> HttpRequests.request(url)
            "POST" -> HttpRequests.post(url, "application/json")
            "PUT" -> HttpRequests.put(url, "application/json")
            "DELETE" -> HttpRequests.delete(url)
            else -> error("Unsupported method $method")
        }
        return try {
            builder
                .accept(accept)
                .productNameAsUserAgent()
                .connectTimeout(15_000)
                .readTimeout(60_000)
                .tuner { auth(it) }
                .isReadResponseOnError(true)
                .connect { request ->
                    if (body != null) request.write(Json.write(body))
                    val connection = request.connection as HttpURLConnection
                    val code = connection.responseCode
                    if (code >= 400) throw ApiException(describe(code, errorMessage(request.readError())), code)
                    val headers = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
                    connection.headerFields.forEach { (k, v) -> if (k != null && v.isNotEmpty()) headers[k] = v.first() }
                    Response(request.readString(), headers)
                }
        } catch (e: ApiException) {
            throw e
        } catch (e: HttpRequests.HttpStatusException) {
            throw ApiException(describe(e.statusCode, errorMessage(e.message)), e.statusCode, e)
        } catch (e: IOException) {
            throw ApiException(e.message ?: e.javaClass.simpleName, 0, e)
        }
    }

    private fun describe(status: Int, raw: String?): String = when (status) {
        HttpURLConnection.HTTP_UNAUTHORIZED -> msg("api.unauthorized", provider)
        HttpURLConnection.HTTP_FORBIDDEN -> msg("api.forbidden", provider, raw.orEmpty())
        HttpURLConnection.HTTP_NOT_FOUND -> msg("api.notFound", provider, raw.orEmpty())
        HttpURLConnection.HTTP_BAD_REQUEST -> msg("api.badRequest", provider, raw.orEmpty())
        else -> msg("api.other", provider, status, raw.orEmpty())
    }

    companion object {
        /** The server's own explanation: GitLab and GitHub {message}, {error}, Bitbucket Cloud {error:{message}}, DC {errors:[{message}]}. */
        internal fun errorMessage(body: String?): String? {
            if (body.isNullOrBlank()) return null
            return try {
                val m = Json.parse(body).obj()
                val error = m["error"]
                val text = m["message"] ?: (error as? Map<*, *>)?.get("message") ?: error
                    ?: m.a("errors").firstOrNull()?.obj()?.get("message")
                text?.let { if (it is String) it else Json.write(it) } ?: body.take(300)
            } catch (e: Exception) {
                body.take(300)
            }
        }
    }
}
```

`src/main/kotlin/me/brekhin/mrnavigator/api/Hosting.kt`:

```kotlin
package me.brekhin.mrnavigator.api

import me.brekhin.mrnavigator.util.msg

enum class MrFilter(private val key: String) {
    OPENED("filter.opened"),
    REVIEW_REQUESTED("filter.review"),
    ASSIGNED("filter.assigned"),
    MINE("filter.mine"),
    MERGED("filter.merged");

    override fun toString() = msg(key)
}

/** A server the plugin is connected to; [username] — for Bitbucket's Basic auth, null otherwise. */
data class Connection(val type: HostingType, val url: String, val username: String? = null)

enum class HostingType(
    val title: String,
    /** Before the number: "!12" on GitLab, "#12" elsewhere. */
    val prefix: Char,
    /** "MR" or "PR" — not translated. */
    val term: String,
    val filters: List<MrFilter>,
    /** "Suggest a change" button: the server renders ```suggestion blocks. */
    val canSuggest: Boolean,
    /** GitLab accepts comments on any line of the file, the others only on lines of the diff hunks. */
    val commentsOutsideHunks: Boolean,
    /** Bundle key of the user name field of the connection form; null — no such field. */
    val usernameLabel: String?,
    /** The only server of a cloud service; null — the user enters the address. */
    val fixedUrl: String?,
) {
    GITLAB("GitLab", '!', "MR", MrFilter.entries, canSuggest = true, commentsOutsideHunks = true, usernameLabel = null, fixedUrl = null);

    fun client(c: Connection, token: String): HostingClient = when (this) {
        GITLAB -> GitLabClient(c.url, token)
    }

    fun tokenPageUrl(url: String): String = when (this) {
        GITLAB -> "$url/-/user_settings/personal_access_tokens?name=MR+Navigator&scopes=api"
    }

    override fun toString() = title

    companion object {
        /** Type of a server by its host name: the form's first guess for a new connection. */
        fun guess(host: String): HostingType = GITLAB
    }
}

/** One code hosting API. All methods block — call them from a background thread. */
interface HostingClient {
    fun currentUser(): User
    fun mergeRequests(project: ProjectRef, filter: MrFilter, me: User?, search: String?): List<MergeRequest>
    fun mergeRequest(project: ProjectRef, iid: Long): MergeRequest
    fun changes(project: ProjectRef, mr: MergeRequest): List<FileChange>
    fun discussions(project: ProjectRef, mr: MergeRequest): List<Discussion>
    /** [position] == null — a general comment. */
    fun createDiscussion(project: ProjectRef, mr: MergeRequest, body: String, position: Position?)
    fun reply(project: ProjectRef, mr: MergeRequest, d: Discussion, body: String)
    fun resolve(project: ProjectRef, mr: MergeRequest, d: Discussion, resolved: Boolean)
    fun approve(project: ProjectRef, mr: MergeRequest)
    fun unapprove(project: ProjectRef, mr: MergeRequest)
    /** Usernames of those who approved. */
    fun approvedBy(project: ProjectRef, mr: MergeRequest): List<String>
    /** GitLab only — the others have no API for it, and their notes carry no suggestions. */
    fun applySuggestions(ids: List<Long>): Unit = throw UnsupportedOperationException()
}
```

`src/main/kotlin/me/brekhin/mrnavigator/api/GitLabClient.kt` — весь файл:

```kotlin
package me.brekhin.mrnavigator.api

import me.brekhin.mrnavigator.util.arr
import me.brekhin.mrnavigator.util.msg
import me.brekhin.mrnavigator.util.obj
import me.brekhin.mrnavigator.util.str
import java.net.HttpURLConnection
import java.net.URLEncoder

/** GitLab REST API v4. */
class GitLabClient(serverUrl: String, token: String) : HostingClient {
    private val api = serverUrl.trimEnd('/') + "/api/v4"
    private val http = Http("GitLab") { it.setRequestProperty("PRIVATE-TOKEN", token) }

    private fun json(method: String, path: String, body: Any? = null): Any? = http.call(method, api + path, body).json()

    /** Follows X-Next-Page until exhausted (or [limit] items). */
    private fun paged(path: String, limit: Int = 2000): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        val sep = if (path.contains('?')) '&' else '?'
        var page: String? = "1"
        while (page != null && out.size < limit) {
            val r = http.call("GET", "$api$path${sep}per_page=100&page=$page")
            out += r.json().arr().map { it.obj() }
            page = r.header("X-Next-Page")?.takeIf { it.isNotBlank() }
        }
        return out
    }

    private fun proj(project: ProjectRef) = "/projects/${project.encodedPath}"
    private fun mrPath(project: ProjectRef, mr: MergeRequest) = "${proj(project)}/merge_requests/${mr.iid}"
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    override fun currentUser(): User = User.from(json("GET", "/user").obj()) ?: throw ApiException(msg("api.emptyUser", "GitLab"))

    override fun mergeRequests(project: ProjectRef, filter: MrFilter, me: User?, search: String?): List<MergeRequest> {
        val state = if (filter == MrFilter.MERGED) "merged" else "opened"
        val params = StringBuilder("state=$state&order_by=updated_at&sort=desc")
        when (filter) {
            MrFilter.REVIEW_REQUESTED -> me?.let { params.append("&reviewer_username=${enc(it.username)}") }
            MrFilter.MINE -> me?.let { params.append("&author_username=${enc(it.username)}") }
            MrFilter.ASSIGNED -> me?.let { params.append("&assignee_username=${enc(it.username)}") }
            else -> Unit
        }
        if (!search.isNullOrBlank()) params.append("&search=${enc(search.trim())}")
        return paged("${proj(project)}/merge_requests?$params", limit = 200).map { MergeRequest.from(it) }
    }

    override fun mergeRequest(project: ProjectRef, iid: Long): MergeRequest =
        MergeRequest.from(json("GET", "${proj(project)}/merge_requests/$iid").obj())

    /** Uses /diffs (GitLab 15.7+), falls back to the older /changes. */
    override fun changes(project: ProjectRef, mr: MergeRequest): List<FileChange> = try {
        paged("${mrPath(project, mr)}/diffs").map { FileChange.from(it) }
    } catch (e: ApiException) {
        if (e.status != HttpURLConnection.HTTP_NOT_FOUND) throw e
        json("GET", "${mrPath(project, mr)}/changes").obj()["changes"].arr().map { FileChange.from(it.obj()) }
    }

    override fun discussions(project: ProjectRef, mr: MergeRequest): List<Discussion> =
        paged("${mrPath(project, mr)}/discussions").map { m ->
            Discussion.from(m).let { it.copy(webUrl = "${mr.webUrl}#note_${it.first?.id ?: ""}") }
        }

    override fun createDiscussion(project: ProjectRef, mr: MergeRequest, body: String, position: Position?) {
        val payload = linkedMapOf<String, Any?>("body" to body)
        if (position != null) payload["position"] = position.toJson()
        json("POST", "${mrPath(project, mr)}/discussions", payload)
    }

    override fun reply(project: ProjectRef, mr: MergeRequest, d: Discussion, body: String) {
        json("POST", "${mrPath(project, mr)}/discussions/${d.id}/notes", mapOf("body" to body))
    }

    override fun resolve(project: ProjectRef, mr: MergeRequest, d: Discussion, resolved: Boolean) {
        json("PUT", "${mrPath(project, mr)}/discussions/${d.id}?resolved=$resolved", emptyMap<String, Any?>())
    }

    /** GitLab commits the suggestions to the source branch, like "Apply suggestion" on the web. */
    override fun applySuggestions(ids: List<Long>) {
        if (ids.size == 1) json("PUT", "/suggestions/${ids[0]}/apply", emptyMap<String, Any?>())
        else json("PUT", "/suggestions/batch_apply", mapOf("ids" to ids))
    }

    override fun approve(project: ProjectRef, mr: MergeRequest) {
        json("POST", "${mrPath(project, mr)}/approve", mr.sha?.let { mapOf("sha" to it) } ?: emptyMap<String, Any?>())
    }

    override fun unapprove(project: ProjectRef, mr: MergeRequest) {
        json("POST", "${mrPath(project, mr)}/unapprove", emptyMap<String, Any?>())
    }

    /** Empty if approvals are not available on this instance. */
    override fun approvedBy(project: ProjectRef, mr: MergeRequest): List<String> = try {
        json("GET", "${mrPath(project, mr)}/approvals").obj()["approved_by"].arr()
            .mapNotNull { it.obj()["user"].obj().str("username") }
    } catch (e: ApiException) {
        emptyList()
    }
}
```

`Models.kt`:
- `MergeRequest` — удалить поля `id`, `projectId`, `sourceProjectId` и их строки в `from()`; добавить в конец конструктора:
  ```kotlin
      /** Ref on the server with the head commit, fetched into refs/mr-review/<iid>. */
      val fetchRef: String,
      /** Repository to fetch [fetchRef] from when it is not the project's remote (a Bitbucket Cloud fork). */
      val fetchUrl: String? = null,
  ```
  и в `from()`: `fetchRef = "refs/merge-requests/${m.long("iid") ?: 0}/head",`.
- `Position` — после `lineRange` добавить
  ```kotlin
      /** The server says the thread was written for code that has changed since. */
      val outdated: Boolean = false,
  ```
  и метод
  ```kotlin
      /** Written for another version of the MR than [head] — its lines no longer match the diff. */
      fun isOutdatedFor(head: String?): Boolean = outdated || (headSha != null && head != null && headSha != head)
  ```
- `Discussion` — `data class Discussion(val id: String, val notes: List<Note>, val webUrl: String? = null)`.

`MrReviewService.kt`:
- импорт `me.brekhin.mrnavigator.api.*` уже есть.
- `MrSession`: вторым параметром после `project` добавить `val connection: Connection,`; в тело:
  ```kotlin
      val type: HostingType get() = connection.type
      /** "!12" or "#12". */
      val ref: String get() = "${type.prefix}${mr.iid}"

      fun isOutdated(d: Discussion): Boolean = d.position?.isOutdatedFor(mr.diffRefs?.headSha) == true
  ```
  `refs` бросает `ApiException` вместо `GitLabException` (текст пока прежний).
- `client()`: тип `HostingClient`; тело `return GitLabClient(settings.serverUrl, token)` без изменений.
- `currentUser(client: HostingClient)`.
- `loadSession`:
  ```kotlin
          val full = client.mergeRequest(located.project, mr.iid)
          val changes = client.changes(located.project, full)
          val discussions = client.discussions(located.project, full)
          val approved = client.approvedBy(located.project, full)
          val connection = Connection(HostingType.GITLAB, MrReviewSettings.getInstance().serverUrl)
          val s = MrSession(located.project, connection, located.git, located.remoteName, full, changes, discussions, approved)
  ```
- `refreshDiscussions`: `client().discussions(s.project, s.mr)`; `refreshApprovals`: `client().approvedBy(s.project, s.mr)`.
- `ensureCommits`, первый fetch:
  ```kotlin
          s.git.run("fetch", s.mr.fetchUrl ?: remote, "+${s.mr.fetchRef}:refs/mr-review/${s.mr.iid}", timeoutMs = 300_000)
  ```
- `postComment`: `client().createDiscussion(s.project, s.mr, body, position)`; `reply`: `client().reply(s.project, s.mr, d, body)`; `setResolved`: `client().resolve(s.project, s.mr, d, resolved)`.

UI:
- `MrDetailsPanel.kt`: удалить `private fun isOutdated(...)`; оба вызова `isOutdated(s, d)` / `isOutdated(s, value)` → `s.isOutdated(d)` / `s.isOutdated(value)`; в `toggleApprove`: `if (approve) c.approve(s.project, s.mr) else c.unapprove(s.project, s.mr)`.
- `CommentMarkers.refresh()`: строку `if (p.headSha != null && headSha != null && p.headSha != headSha) continue` → `if (s.isOutdated(d)) continue`; удалить ставшую ненужной `val headSha = …`.
- `ThreadPopup.showThread`: `openWeb` —
  ```kotlin
          val openWeb = JButton(AllIcons.General.Web).apply {
              toolTipText = "Открыть в браузере"
              isVisible = discussion.webUrl != null
          }
          ...
          openWeb.addActionListener { discussion.webUrl?.let { BrowserUtil.browse(it) } }
  ```
- `MrToolWindow.kt`, `SetupPanel.kt`, `MrReviewConfigurable.kt`: `GitLabException` → `ApiException` (импорт `me.brekhin.mrnavigator.api.ApiException`).

Ключи — в конец `MrBundle.properties`:
```properties

api.unauthorized={0}: the token was rejected (401). Check the token in the settings.
api.forbidden={0}: not enough permissions (403). {1}
api.notFound={0}: not found (404). {1}
api.badRequest={0} rejected the request (400): {1}
api.other={0}: error {1}. {2}
api.emptyUser={0}: empty response to the current user request

filter.opened=All open
filter.review=Waiting for my review
filter.assigned=Assigned to me
filter.mine=Mine
filter.merged=Merged
```
и в `MrBundle_ru.properties`:
```properties

api.unauthorized={0}: токен не принят (401). Проверьте токен в настройках.
api.forbidden={0}: недостаточно прав (403). {1}
api.notFound={0}: не найдено (404). {1}
api.badRequest={0} отклонил запрос (400): {1}
api.other={0}: ошибка {1}. {2}
api.emptyUser={0}: пустой ответ на запрос текущего пользователя

filter.opened=Все открытые
filter.review=Ждут моего ревью
filter.assigned=Назначены на меня
filter.mine=Мои
filter.merged=Смёрженные
```

- [ ] **Step 4: Run tests and build**

Run: `./gradlew -q test buildPlugin`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Introduce HostingClient and move GitLab onto it"   # + trailer lines
```

---

### Task 3: Перевод всех строк UI на бандл

**Files:**
- Modify: все файлы из инвентаризации ниже; `src/main/resources/META-INF/plugin.xml`; оба `.properties`.
- Test: `BundleTest` (уже проверяет паритет ключей и шаблоны).

**Interfaces:**
- Consumes: `msg`, `MrBundle.plural` (Task 1); `MrSession.type`, `MrSession.ref` (Task 2).
- Produces:
  - `class SetupNeeded(message: String?) : Exception(message)` — `null` значит «ещё ничего не подключено» (форма без текста причины).
  - `MrDetailsPanel.load(mr: MergeRequest, type: HostingType)`.
  - `MrToolWindowPanel.currentType: HostingType` (пока всегда `GITLAB`, Task 4 берёт из репозитория).

- [ ] **Step 1: Write the failing test** — в `BundleTest` добавить проверку, что в коде не осталось кириллицы в строковых литералах:

```kotlin
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew -q test --tests '*BundleTest.noRussianLiteralsInCode'`
Expected: FAIL со списком ~120 строк.

- [ ] **Step 3: Add keys** — дописать в конец `MrBundle.properties`:

```properties

cancelled=Cancelled
error=Error
openInBrowser=Open in browser
comments.one=comment
comments.few=comments
comments.many=comments

list.repo=Repository:
list.repo.tooltip=Repository whose requests are listed
list.search=Search by title
list.refresh=Refresh list
list.settings=Settings
list.loading=Loading…
list.loadingTask=Loading the {0} list
list.empty=No {0}s in {1}
list.openSettings=Open settings
list.unauthorized={0} rejected the token: it may have expired or been revoked. Create a new one.

details.placeholder=Select a merge request or pull request
details.checkout=Checkout & review
details.back=Go back
details.refresh=Refresh the {0}
details.approve=Approve
details.revokeApprove=Revoke approval
details.showHidden=Show hidden ({0})
details.newComment=New comment
details.hideResolved=Hide resolved
details.filesHint=Double click or Enter: open the diff · Space: mark as viewed
details.threadsEmpty=No comments
details.threadsHint=Double click: open the thread; a line comment opens in the diff at its line
details.tab.files=Files ({0})
details.tab.discussion=Discussion ({0})
details.tab.discussionOpen=Discussion ({0}, {1} open)
details.tab.description=Description
details.loading=Loading {0}…
details.loadFailed=Could not load {0}: {1}
details.conflicts=conflicts
details.approvedBy=approved: {0}
details.noDescription=_No description_
details.state.gitError=Could not check git: {0}
details.state.onMr=✓ The working copy is on the {0} code ({1}, {2}): navigation works in the diff
details.state.branchReady=You are on {0}. Branch {1} is already fetched: “Checkout & review” switches to it
details.state.newCommits=You are on {0}. The {1} has new commits: “Checkout & review” updates {2} to {3}
details.state.notOnMr=You are on {0}, not on the {1} code ({2}): navigation in the diff doesn''t work. Click “Checkout & review”
details.summary.files={0} {1}
details.summary.viewed={0} of {1} viewed
details.summary.hidden={0} generated hidden
details.checkoutTask=Checkout {0}
details.checkoutFailed=Checkout failed
details.goBackTask=Returning to the previous branch
details.nothingSelected=Nothing is selected
details.revokeTask=Revoking approval
thread.outdated=outdated
thread.resolved=resolved
thread.open=open

popup.replyPlaceholder=Reply…
popup.reply=Reply
popup.resolve=Resolve
popup.reopen=Reopen
popup.resolve.tooltip=Mark the discussion as resolved
popup.reopen.tooltip=Reopen the discussion
popup.replyTask=Sending the reply
popup.replyFailed=Reply not sent
popup.resolveFailed=Failed
popup.applyTask=Applying the suggestion
popup.applyFailed=Suggestion not applied
popup.applied=Suggestion applied: {0} added a commit to {1}. To get it locally, refresh the {2} and click “Checkout & review”
popup.commentPlaceholder=Comment…
popup.commentPlaceholder.general=Comment on the {0}…
popup.comment=Comment
popup.commentTask=Sending the comment
popup.commentFailed=Comment not sent
popup.newOnLine=New comment · {0}, line {1}
popup.newOnLines=New comment · {0}, lines {1}
popup.newGeneral=Comment on {0}
popup.discussion=Discussion
popup.resolvedBanner=Resolved
popup.suggestionApplied=Suggestion applied
popup.applySuggestion=Apply suggestion
popup.applySuggestion.tooltip={0} commits the change to the {1} branch
popup.applySuggestion.disabled={0} doesn''t allow applying it: the suggestion is outdated, the {1} is closed or the change is already in the code
popup.hint=Markdown · {0}: send
popup.suggest=Suggest a change
popup.suggest.tooltip=Insert a suggestion block with the current lines: the {0} author can apply it
popup.close=Close (Esc)

diff.prepareTask=Preparing the diff of {0}
diff.newFile=(new file)
diff.deleted=(deleted)
diff.local=Local file · {0} · {1}
diff.revision={0} · {1} · {2} (navigation works after a checkout)
diff.tooLarge={0} didn''t return the diff of this file (too large): comment on it in the browser
diff.lines=lines {0}
diff.commentRange=Comment on the selected lines ({0})
diff.commentLine=Comment on the line ({0}). Select several lines to comment on a range
action.addComment=Comment on Line / Selection

tree.viewed=✓ viewed
tree.hidden=hidden
tree.tooLarge=too large

setup.title=Connect to GitLab
setup.intro=The plugin needs the address of your GitLab and a personal access token with the <b>api</b> scope. The token is kept in the IDE password storage.
setup.url=GitLab address:
setup.token=Token:
setup.createToken=Create a token in GitLab →
setup.connect=Connect
setup.allSettings=All plugin settings
setup.detected=Found in git remotes: {0}
setup.pasteToken=Paste the token
setup.checking=Checking…
setup.task=Connecting to {0}
setup.connected={0}: connected as {1} (@{2})

settings.server=Server address:
settings.server.comment=For example https://gitlab.com or https://git.company.com
settings.token=Personal access token:
settings.token.comment=Scope <b>api</b>: GitLab → Preferences → Access Tokens. Kept in the IDE password storage.
settings.check=Test connection
settings.savingToken=Saving the token
settings.fillIn=Fill in the address and the token
settings.checking=Testing the connection…
settings.connected=Connected as {0} (@{1})
settings.hiding=Hidden files
settings.hide=Hide files with these suffixes in the tree and the diff
settings.suffixes=Suffixes (one per line):
settings.suffixes.comment=A file whose path ends with a suffix is hidden. For example .pb.go, .pb.gw.go, _mock.go
settings.git=Git
settings.gitPath=Path to git:
settings.gitPath.comment=Usually just git
settings.autoStash=Stash uncommitted changes on checkout

error.noDiffRefs={0} has no diff refs yet: {1} hasn''t computed the diff, refresh later
error.noProjectDir=The project has no directory
error.noRepos=No git repositories found in the project folder
error.noRemote=No git remote points to {0}{1}. Set the address of your GitLab.
error.noRemote.hosts=\ (remotes: {0})
error.fetchFailed=Could not fetch the {0} commits from remote “{1}”
error.localChanges=There are uncommitted changes. Commit them or turn on auto-stash in the settings.
error.nowhereToGoBack=Nothing to go back to
checkout.alreadyOnMr=Already on the {0} commit
checkout.branch=Branch {0}
checkout.branchStashed=Branch {0}, your changes are stashed
checkout.returned=Returned to {0}
checkout.returnedRestored=Returned to {0}, the stashed changes are restored
checkout.returnedStashMissing=Returned to {0}. Stash “{1}” is not found or conflicts: restore it manually (git stash list)
git.cannotRun=Could not run git: {0}. Set the path to git in the plugin settings.
git.timeout=git {0} didn''t finish in {1} s
git.showTimeout=git show {0} timed out
```

и в конец `MrBundle_ru.properties` (тексты — нынешние русские строки):

```properties

cancelled=Отменено
error=Ошибка
openInBrowser=Открыть в браузере
comments.one=комментарий
comments.few=комментария
comments.many=комментариев

list.repo=Репозиторий:
list.repo.tooltip=Репозиторий, запросы которого показаны в списке
list.search=Поиск по названию
list.refresh=Обновить список
list.settings=Настройки
list.loading=Загрузка…
list.loadingTask=Загрузка списка {0}
list.empty=Нет {0} в {1}
list.openSettings=Открыть настройки
list.unauthorized={0} не принял токен — возможно, он истёк или отозван. Создайте новый.

details.placeholder=Выберите merge request или pull request
details.checkout=Checkout и ревью
details.back=Вернуться
details.refresh=Обновить {0}
details.approve=Approve
details.revokeApprove=Отозвать approve
details.showHidden=Показать скрытые ({0})
details.newComment=Новый комментарий
details.hideResolved=Скрыть решённые
details.filesHint=Двойной клик или Enter — открыть diff · Пробел — отметить просмотренным
details.threadsEmpty=Комментариев нет
details.threadsHint=Двойной клик — открыть тред: комментарий к строке откроется в diff на этой строке
details.tab.files=Файлы ({0})
details.tab.discussion=Обсуждение ({0})
details.tab.discussionOpen=Обсуждение ({0}, открыто {1})
details.tab.description=Описание
details.loading=Загрузка {0}…
details.loadFailed=Не удалось загрузить {0}: {1}
details.conflicts=конфликты
details.approvedBy=approved: {0}
details.noDescription=_Нет описания_
details.state.gitError=Не удалось проверить git: {0}
details.state.onMr=✓ Рабочая копия на коде {0} ({1}, {2}) — в diff работают переходы
details.state.branchReady=Сейчас вы на {0}. Ветка {1} уже выкачана — «Checkout и ревью» переключит на неё
details.state.newCommits=Сейчас вы на {0}. В {1} новые коммиты — «Checkout и ревью» обновит {2} до {3}
details.state.notOnMr=Сейчас вы на {0}, а не на коде {1} ({2}) — переходы в diff не работают. Нажмите «Checkout и ревью»
details.summary.files={0} {1}
details.summary.viewed=просмотрено {0} из {1}
details.summary.hidden=скрыто {0} сгенерированных
details.checkoutTask=Checkout {0}
details.checkoutFailed=Checkout не удался
details.goBackTask=Возврат на прежнюю ветку
details.nothingSelected=Ничего не выбрано
details.revokeTask=Отзыв approve
thread.outdated=устарел
thread.resolved=решён
thread.open=открыт

popup.replyPlaceholder=Ответить…
popup.reply=Ответить
popup.resolve=Resolve
popup.reopen=Переоткрыть
popup.resolve.tooltip=Отметить обсуждение решённым
popup.reopen.tooltip=Снова открыть обсуждение
popup.replyTask=Отправка ответа
popup.replyFailed=Ответ не отправлен
popup.resolveFailed=Не получилось
popup.applyTask=Применение suggestion
popup.applyFailed=Suggestion не применён
popup.applied=Suggestion применён: {0} добавил коммит в {1}. Чтобы получить его локально, обновите {2} и сделайте «Checkout и ревью»
popup.commentPlaceholder=Комментарий…
popup.commentPlaceholder.general=Комментарий к {0}…
popup.comment=Комментировать
popup.commentTask=Отправка комментария
popup.commentFailed=Комментарий не отправлен
popup.newOnLine=Новый комментарий · {0}, строка {1}
popup.newOnLines=Новый комментарий · {0}, строки {1}
popup.newGeneral=Комментарий к {0}
popup.discussion=Обсуждение
popup.resolvedBanner=Решено
popup.suggestionApplied=Suggestion применён
popup.applySuggestion=Применить suggestion
popup.applySuggestion.tooltip={0} закоммитит изменение в ветку {1}
popup.applySuggestion.disabled={0} не даёт применить: suggestion устарел, {1} закрыт или изменение уже в коде
popup.hint=Markdown · {0} — отправить
popup.suggest=Предложить изменение
popup.suggest.tooltip=Вставить блок suggestion с текущими строками — автор {0} сможет его применить
popup.close=Закрыть (Esc)

diff.prepareTask=Подготовка diff {0}
diff.newFile=(новый файл)
diff.deleted=(удалён)
diff.local=Локальный файл · {0} · {1}
diff.revision={0} · {1} · {2} (без checkout переходы не работают)
diff.tooLarge={0} не отдал diff этого файла (слишком большой) — комментируйте его в браузере
diff.lines=строки {0}
diff.commentRange=Комментарий к выделенным строкам ({0})
diff.commentLine=Комментарий к строке ({0}). Выделите несколько строк — комментарий будет на диапазон
action.addComment=Комментарий к строке / выделению

tree.viewed=✓ просмотрен
tree.hidden=скрыт
tree.tooLarge=слишком большой

setup.title=Подключение к GitLab
setup.intro=Плагину нужен адрес вашего GitLab и личный токен доступа со scope <b>api</b>. Токен хранится в хранилище паролей IDE.
setup.url=Адрес GitLab:
setup.token=Токен:
setup.createToken=Создать токен в GitLab →
setup.connect=Подключить
setup.allSettings=Все настройки плагина
setup.detected=Найдено в git remote: {0}
setup.pasteToken=Вставьте токен
setup.checking=Проверяю…
setup.task=Подключение к {0}
setup.connected={0}: подключено как {1} (@{2})

settings.server=Адрес сервера:
settings.server.comment=Например https://gitlab.com или https://git.company.ru
settings.token=Personal access token:
settings.token.comment=Scope <b>api</b>: GitLab → Preferences → Access Tokens. Хранится в хранилище паролей IDE.
settings.check=Проверить подключение
settings.savingToken=Сохранение токена
settings.fillIn=Заполните адрес и токен
settings.checking=Проверка подключения…
settings.connected=Подключено как {0} (@{1})
settings.hiding=Скрытие файлов
settings.hide=Скрывать файлы с этими суффиксами в дереве и diff
settings.suffixes=Суффиксы (по одному в строке):
settings.suffixes.comment=Путь файла оканчивается на суффикс → файл скрыт. Например .pb.go, .pb.gw.go, _mock.go
settings.git=Git
settings.gitPath=Путь к git:
settings.gitPath.comment=Обычно достаточно git
settings.autoStash=При checkout прятать незакоммиченные изменения в stash

error.noDiffRefs=У {0} нет diff_refs — {1} ещё не посчитал diff, обновите позже
error.noProjectDir=У проекта нет каталога
error.noRepos=В папке проекта не найдено ни одного git-репозитория
error.noRemote=Ни один git remote не указывает на {0}{1}. Укажите адрес вашего GitLab.
error.noRemote.hosts=\ (remote: {0})
error.fetchFailed=Не удалось получить коммиты {0} из remote «{1}»
error.localChanges=Есть незакоммиченные изменения. Закоммитьте их или включите авто-stash в настройках.
error.nowhereToGoBack=Некуда возвращаться
checkout.alreadyOnMr=Уже на коммите {0}
checkout.branch=Ветка {0}
checkout.branchStashed=Ветка {0}, ваши изменения спрятаны в stash
checkout.returned=Вернулись на {0}
checkout.returnedRestored=Вернулись на {0}, изменения из stash восстановлены
checkout.returnedStashMissing=Вернулись на {0}. Stash «{1}» не найден или конфликтует — восстановите вручную (git stash list)
git.cannotRun=Не удалось запустить git: {0}. Укажите путь к git в настройках плагина.
git.timeout=git {0} не завершился за {1} с
git.showTimeout=git show {0} завершился по таймауту
```

- [ ] **Step 4: Replace literals** (импорт `me.brekhin.mrnavigator.util.msg` в каждом файле; `t` = `HostingType`, `s` = `MrSession`):

`core/MrReviewService.kt`
- `class SetupNeeded(message: String) ` → `class SetupNeeded(message: String?) : Exception(message)`.
- `refs`: `throw ApiException(msg("error.noDiffRefs", ref, type.title))`.
- `MrException("У проекта нет каталога")` → `MrException(msg("error.noProjectDir"))`; «ни одного git-репозитория» → `msg("error.noRepos")`.
- `throw SetupNeeded("Ни один git remote …")` → `throw SetupNeeded(msg("error.noRemote", server, if (hosts.isNotEmpty()) msg("error.noRemote.hosts", hosts.joinToString()) else ""))`.
- `SetupNeeded("Не задан токен GitLab")` → `SetupNeeded(null)`.
- `GitException("Не удалось получить коммиты MR из remote '$remote'")` → `GitException(msg("error.fetchFailed", s.type.term, remote))`.
- `return "Уже на коммите MR"` → `return msg("checkout.alreadyOnMr", s.type.term)`.
- `MrException("Есть незакоммиченные…")` → `MrException(msg("error.localChanges"))`.
- `return "Ветка $branch" + …` → `return msg(if (stashMarker != null) "checkout.branchStashed" else "checkout.branch", branch)`.
- `MrException("Некуда возвращаться")` → `MrException(msg("error.nowhereToGoBack"))`.
- в `goBack`:
  ```kotlin
          val message = when {
              point.stashMarker == null -> msg("checkout.returned", point.ref)
              popStash(git, point.stashMarker) -> msg("checkout.returnedRestored", point.ref)
              else -> msg("checkout.returnedStashMissing", point.ref, point.stashMarker)
          }
  ```
  (удалить `var message` и `message +=`).

`git/GitCli.kt`
- `"Не удалось запустить git: ${e.message}. …"` → `msg("git.cannotRun", e.message)`.
- `"git ${args.firstOrNull()} не завершился за ${timeoutMs / 1000} с"` → `msg("git.timeout", args.firstOrNull(), timeoutMs / 1000)`.
- `"git show $rev:$path завершился по таймауту"` → `msg("git.showTimeout", "$rev:$path")`.

`ui/Bg.kt`: `CancellationException("Отменено")` → `CancellationException(msg("cancelled"))`.

`ui/ChangesTree.kt`: `"  ✓ просмотрен"` → `"  " + msg("tree.viewed")`; `"  скрыт"` → `"  " + msg("tree.hidden")`; `"  слишком большой"` → `"  " + msg("tree.tooLarge")`.

`ui/MrToolWindow.kt`
- поле `var currentType = HostingType.GITLAB` (private) рядом с `updatingRepos`.
- `toolTipText = "Репозиторий, …"` → `msg("list.repo.tooltip")`; `JBLabel("Репозиторий:")` → `JBLabel(msg("list.repo"))`; `"Поиск по названию"` → `msg("list.search")`; `"Обновить список"` → `msg("list.refresh")`; `"Настройки"` → `msg("list.settings")`; `"Загрузка…"` → `msg("list.loading")`; `Bg.run(project, "Загрузка merge request'ов"` → `Bg.run(project, msg("list.loadingTask", currentType.term)`.
- в `onError` блок `setup.prepare(when { … })` →
  ```kotlin
                  setup.prepare(if (unauthorized) msg("list.unauthorized", currentType.title) else e.message)
  ```
- `e.message ?: "Ошибка"` → `e.message ?: msg("error")`; `"Открыть настройки"` → `msg("list.openSettings")`.
- `"Нет merge request'ов в ${loaded.repo.project.path}"` → `msg("list.empty", currentType.term, loaded.repo.project.path)`.
- `GitLabException` → `ApiException` (если ещё не сделано).
- `list.selectedValue?.let { mr -> details.load(mr) }` → `details.load(mr, currentType)`.
- `Renderer` сделать `inner class`; `"!${value.iid} "` → `"${currentType.prefix}${value.iid} "`; `"  · ${value.userNotesCount} комм."` → `"  · ${value.userNotesCount} ${MrBundle.plural(value.userNotesCount.toLong(), "comments")}"`.

`ui/MrDetailsPanel.kt`
- `JButton("Checkout и ревью", …)` → `msg("details.checkout")`; `"Вернуться"` → `msg("details.back")`; `"Открыть в браузере"` → `msg("openInBrowser")`; `"Обновить MR"` → задаётся в `render()` (см. ниже), в инициализаторе — без tooltip; `JBCheckBox("Показать скрытые")` → `JBCheckBox()`; `"Новый комментарий"` → `msg("details.newComment")`; `"Скрыть решённые"` → `msg("details.hideResolved")`; `"Выберите merge request"` → `msg("details.placeholder")`; два `hint(…)` → `hint(msg("details.filesHint"))`, `hint(msg("details.threadsHint"))`; `"Комментариев нет"` → `msg("details.threadsEmpty")`; `tabs.addTab("Файлы", …)` → `msg("details.tab.files", 0)`; `"Обсуждение"` → `msg("details.tab.discussion", 0)`; `"Описание"` → `msg("details.tab.description")`.
- `fun load(mr: MergeRequest)` → `fun load(mr: MergeRequest, type: HostingType)`; внутри `val ref = "${type.prefix}${mr.iid}"`; `"Загрузка !${mr.iid}…"` → `msg("details.loading", ref)`; `Bg.run(project, "Загрузка !${mr.iid}"` → `Bg.run(project, msg("details.loading", ref)`; `"Не удалось загрузить !${mr.iid}: ${it.message}"` → `msg("details.loadFailed", ref, it.message)`.
- `refreshButton.addActionListener { session?.let { load(it.mr) } }` → `session?.let { load(it.mr, it.type) }`.
- в `render()`:
  ```kotlin
          title.text = "<html>${if (mr.draft) "<span style='color:gray'>Draft:</span> " else ""}${s.ref} ${Markdown.escape(mr.title)}</html>"
          meta.text = "${mr.author?.name ?: "?"} · ${mr.sourceBranch} → ${mr.targetBranch} · ${mr.state}" +
              (if (mr.hasConflicts) " · " + msg("details.conflicts") else "") +
              (if (s.approvedBy.isNotEmpty()) " · " + msg("details.approvedBy", s.approvedBy.joinToString()) else "")
          refreshButton.toolTipText = msg("details.refresh", s.type.term)
          approveButton.text = msg(if (isApprovedByMe(s)) "details.revokeApprove" else "details.approve")
          description.text = "<html>${Markdown.gfmToHtml(mr.description.ifBlank { msg("details.noDescription") }, mr.webUrl.substringBefore("/-/"))}</html>"
  ```
- `stateText`:
  ```kotlin
          return when {
              st == null -> ""
              st.error != null -> msg("details.state.gitError", st.error)
              st.onMr -> msg("details.state.onMr", s.type.term, st.current, head)
              st.mrBranchSha != null && st.mrBranchSha == st.mrHeadSha -> msg("details.state.branchReady", st.current, branch)
              st.mrBranchSha != null -> msg("details.state.newCommits", st.current, s.type.term, branch, head)
              else -> msg("details.state.notOnMr", st.current, s.type.term, head)
          }
  ```
- `renderFiles`: `showHidden.text = msg("details.showHidden", hiddenCount)`; `tabs.setTitleAt(0, msg("details.tab.files", tree.shownFiles.size))`.
- `updateFilesSummary`:
  ```kotlin
          filesSummary.text = "<html>" + msg("details.summary.files", n, MrBundle.plural(n, "files")) + " · " +
              "<font color='${ColorUtil.toHtmlColor(PLUS)}'>+$added</font> <font color='${ColorUtil.toHtmlColor(MINUS)}'>−$removed</font>" +
              " · " + msg("details.summary.viewed", viewed, n) +
              (if (hiddenCount > 0) " · " + msg("details.summary.hidden", hiddenCount) else "") + "</html>"
  ```
- `renderThreads`: `tabs.setTitleAt(1, if (open > 0) msg("details.tab.discussionOpen", all.size, open) else msg("details.tab.discussion", all.size))`.
- `checkout()`: `Bg.run(project, msg("details.checkoutTask", s.ref)`; `Notify.error(project, msg("details.checkoutFailed"), it)`; `Notify.info(project, "${s.ref}: $message", msg("details.back") to { goBack() })`.
- `goBack()`: `Bg.run(project, msg("details.goBackTask")`; `IllegalStateException("MR не выбран")` → `IllegalStateException(msg("details.nothingSelected"))`. **Внимание:** внутри лямбды локальная переменная `msg` затеняет функцию — переименовать её в `text`: `val text = service.goBack(…); text to service.checkoutState(s)`.
- `toggleApprove()`: `Bg.run(project, if (approve) msg("details.approve") else msg("details.revokeTask")`.
- `ThreadCellRenderer`: `"  устарел"` → `"  " + msg("thread.outdated")`; `"  решён"` → `"  " + msg("thread.resolved")`; `"  открыт"` → `"  " + msg("thread.open")`.

`ui/ThreadPopup.kt` (`t = session.type`)
- `inputArea("Ответить…")` → `inputArea(msg("popup.replyPlaceholder"))`; `primary("Ответить")` → `primary(msg("popup.reply"))`.
- resolve: `JButton(msg(if (discussion.resolved) "popup.reopen" else "popup.resolve"))`, `toolTipText = msg(if (discussion.resolved) "popup.reopen.tooltip" else "popup.resolve.tooltip")`.
- `"Открыть в браузере"` → `msg("openInBrowser")`.
- `"Отправка ответа"` → `msg("popup.replyTask")`; `"Ответ не отправлен"` → `msg("popup.replyFailed")`; `Bg.run(project, "Resolve"` → `Bg.run(project, msg("popup.resolve")`; `"Не получилось"` → `msg("popup.resolveFailed")`; `"Применение suggestion"` → `msg("popup.applyTask")`; `"Suggestion не применён"` → `msg("popup.applyFailed")`.
- `Notify.info(project, "Suggestion применён: …")` → `Notify.info(project, msg("popup.applied", session.type.title, session.mr.sourceBranch, session.type.term))`.
- `showNew`: `inputArea(if (position == null) msg("popup.commentPlaceholder.general", session.type.term) else msg("popup.commentPlaceholder"))`; `primary(msg("popup.comment"))`; `msg("popup.commentTask")`; `msg("popup.commentFailed")`; заголовок:
  ```kotlin
          val where = position?.let { p ->
              val file = (p.newPath ?: p.oldPath)?.substringAfterLast('/')
              msg(if (p.isMultiLine) "popup.newOnLines" else "popup.newOnLine", file, p.lineLabel())
          } ?: msg("popup.newGeneral", session.ref)
  ```
- `title(d)`: `return msg("popup.discussion")` для треда без позиции.
- `resolvedBanner`: `JBLabel(msg("popup.resolvedBanner") + who, …)`.
- `suggestionState(n, type, onApply)` — добавить параметр `type: HostingType` (передать `session.type` из `noteView` → добавить параметр и туда, из `notesView(d, session.type, applySuggestions)`):
  `JBLabel(msg("popup.suggestionApplied"), …)`, `JButton(msg("popup.applySuggestion"), …)`, `toolTipText = if (isEnabled) msg("popup.applySuggestion.tooltip", type.title, type.term) else msg("popup.applySuggestion.disabled", type.title, type.term)`.
- `editor()`: `JBLabel(msg("popup.hint", send))`.
- `suggestionButton`: `JButton(msg("popup.suggest"), …)`, `toolTipText = msg("popup.suggest.tooltip", "MR")` → в Task 4 станет `session.type.term`; сейчас добавить параметр `term: String` в `suggestionButton(input, lines, term)` и передавать `session.type.term`.
- `IconButton("Закрыть (Esc)", …)` → `IconButton(msg("popup.close"), …)`.

`diff/MrDiffOpener.kt`
- `Bg.run(project, msg("diff.prepareTask", session.ref)`.
- заголовки:
  ```kotlin
                  val leftTitle = if (change.newFile) msg("diff.newFile") else "${mr.targetBranch} · ${refs.baseSha.take(8)} · ${change.oldPath}"
                  val rightTitle = when {
                      change.deletedFile -> msg("diff.deleted")
                      rightIsLocal -> msg("diff.local", mr.sourceBranch, change.newPath)
                      else -> msg("diff.revision", mr.sourceBranch, refs.headSha.take(8), change.newPath)
                  }
                  val request = SimpleDiffRequest("${session.ref}: ${change.displayPath}", left, right, leftTitle, rightTitle)
  ```

`diff/CommentMarkers.kt`
- `Notify.info(project, "GitLab не отдал diff …")` → `Notify.info(project, msg("diff.tooLarge", session.type.title))`.
- тултип треда: `" <i>(строки ${it.lineLabel()})</i>"` → `" <i>(${msg("diff.lines", it.lineLabel())})</i>"`.
- `AddIcon.getTooltipText`: `msg("diff.commentRange", session.type.title)` / `msg("diff.commentLine", session.type.title)`.

`diff/AddCommentAction.kt`, в `update()` после `isEnabledAndVisible`: `e.presentation.text = msg("action.addComment")`.
`META-INF/plugin.xml`: `text="Комментарий к строке / выделению (GitLab MR)"` → `text="Comment on Line / Selection"`.

`api/Hosting.kt`: ничего (фильтры уже через бандл).

`ui/SetupPanel.kt`: `"Подключение к GitLab"` → `msg("setup.title")`; текст-интро → `msg("setup.intro")`; `"Адрес GitLab:"` → `msg("setup.url")`; `"Токен:"` → `msg("setup.token")`; `"Создать токен в GitLab →"` → `msg("setup.createToken")`; `"Подключить"` → `msg("setup.connect")`; `"Все настройки плагина"` → `msg("setup.allSettings")`; `"Найдено в git remote: " + servers.joinToString()` → `msg("setup.detected", servers.joinToString())`; `"Вставьте токен"` → `msg("setup.pasteToken")`; `"Проверяю…"` → `msg("setup.checking")`; `Bg.run(project, msg("setup.task", "GitLab")`; `Notify.info(project, msg("setup.connected", "GitLab", user.name, user.username))`.

`settings/MrReviewConfigurable.kt`: `group("GitLab")` остаётся; `"Адрес сервера:"` → `msg("settings.server")`, коммент → `msg("settings.server.comment")`; `"Personal access token:"` → `msg("settings.token")`, коммент → `msg("settings.token.comment")`; `"Проверить подключение"` → `msg("settings.check")`; `group("Скрытие файлов")` → `group(msg("settings.hiding"))`; чекбокс → `msg("settings.hide")`; `row("Суффиксы (по одному в строке):")` → `row(msg("settings.suffixes"))`; коммент → `msg("settings.suffixes.comment")`; `group("Git")` → `group(msg("settings.git"))`; `"Путь к git:"` → `msg("settings.gitPath")`; `"Обычно достаточно git"` → `msg("settings.gitPath.comment")`; чекбокс stash → `msg("settings.autoStash")`; `"Сохранение токена"` → `msg("settings.savingToken")`; `"Заполните адрес и токен"` → `msg("settings.fillIn")`; `"Проверка подключения…"` → `msg("settings.checking")`; `"Подключено как …"` → `msg("settings.connected", user.name, user.username)`.

- [ ] **Step 5: Run tests and build**

Run: `./gradlew -q test buildPlugin`
Expected: PASS, включая `noRussianLiteralsInCode`.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Translate the UI: English and Russian"   # + trailer lines
```

---
### Task 4: Несколько подключений

**Files:**
- Create: `src/main/kotlin/me/brekhin/mrnavigator/ui/ConnectionForm.kt`
- Modify: `src/main/kotlin/me/brekhin/mrnavigator/settings/MrReviewSettings.kt`
- Modify: `src/main/kotlin/me/brekhin/mrnavigator/settings/MrReviewConfigurable.kt` (весь файл)
- Modify: `src/main/kotlin/me/brekhin/mrnavigator/ui/SetupPanel.kt` (весь файл)
- Modify: `src/main/kotlin/me/brekhin/mrnavigator/core/MrReviewService.kt`
- Modify: `src/main/kotlin/me/brekhin/mrnavigator/git/RemoteUrl.kt` (`projectPath`)
- Modify: `src/main/kotlin/me/brekhin/mrnavigator/ui/MrToolWindow.kt`, `ui/MrDetailsPanel.kt`
- Modify: оба `.properties`
- Test: `LogicTest.kt`, `BundleTest.kt`

**Interfaces:**
- Consumes: `Connection`, `HostingType`, `HostingClient`, `ApiException` (Task 2); `msg` (Task 1); `SetupNeeded(message: String?)` (Task 3).
- Produces:
  - `MrReviewSettings.connections: List<Connection>` (get/set), `saveConnection(c: Connection, token: String)`, `removeConnection(c: Connection)`, `migrateLegacy()`, `getToken(url: String): String?`, `setToken(token: String?, url: String)`; `companion internal fun legacyConnection(state: State): Connection?`. Свойство `serverUrl` удаляется (поле `State.serverUrl` остаётся для миграции).
  - `class SetupNeeded(message: String?, val connection: Connection? = null)`.
  - `class Repo(root, name, remoteName, project, val connection: Connection)`.
  - `MrReviewService.client(c: Connection): HostingClient`, `currentUser(c: Connection, client: HostingClient): User`, `currentUserCached(c: Connection): User?`, `detectedHosts(): List<String>`; `locate()`/`Located`/`detectedServers()` удаляются.
  - `RemoteUrl.projectPath(remote: RemoteUrl, c: Connection): String?`.
  - `class ConnectionForm { fun addTo(p: Panel, onSubmit: () -> Unit = {}); fun fill(c: Connection?, host: String? = null); fun connection(): Connection; fun token(): String; fun validate(): String?; fun clearToken(); companion fun verifyAndSave(c: Connection, token: String): User }`.
  - `SetupPanel.prepare(why: String?, connection: Connection?)`.

- [ ] **Step 1: Write the failing tests**

`LogicTest.remoteUrls` — заменить три вызова `RemoteUrl.projectPath(…, "<url>")` на `RemoteUrl.projectPath(…, gitlab("<url>"))` и добавить в класс:
```kotlin
    private fun gitlab(url: String) = Connection(HostingType.GITLAB, url)
```
Добавить тесты:
```kotlin
    @Test
    fun connectionsSettings() {
        val s = MrReviewSettings()
        val list = listOf(Connection(HostingType.GITLAB, "https://gitlab.com"), Connection(HostingType.GITLAB, "https://git.corp", "me"))
        s.connections = list
        assertEquals(list, s.connections)
        // A type this version doesn't know (settings of a newer plugin) is skipped, not fatal.
        s.loadState(MrReviewSettings.State().apply {
            connections.add(MrReviewSettings.ConnectionState().apply { type = "FUTURE"; url = "https://x" })
        })
        assertTrue(s.connections.isEmpty())
    }

    @Test
    fun legacySettings() {
        // Pre-0.3 settings: one GitLab server, gitlab.com by default.
        assertEquals(Connection(HostingType.GITLAB, "https://gitlab.com"), MrReviewSettings.legacyConnection(MrReviewSettings.State()))
        val custom = MrReviewSettings.State().apply { serverUrl = "https://git.corp/ " }
        assertEquals(Connection(HostingType.GITLAB, "https://git.corp"), MrReviewSettings.legacyConnection(custom))
        val migrated = MrReviewSettings.State().apply { connections.add(MrReviewSettings.ConnectionState()) }
        assertNull(MrReviewSettings.legacyConnection(migrated))
    }
```
импорты: `me.brekhin.mrnavigator.api.Connection`, `me.brekhin.mrnavigator.api.HostingType`, `me.brekhin.mrnavigator.settings.MrReviewSettings`.

`BundleTest` — добавить:
```kotlin
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
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew -q test`
Expected: FAIL — compile errors (`projectPath(…, Connection)`, `connections`, `legacyConnection`).

- [ ] **Step 3: Settings** — `MrReviewSettings.kt`:

В `class State` заменить `var serverUrl …` на:
```kotlin
        /** The only server before 0.3; read once by [migrateLegacy]. */
        var serverUrl: String = "https://gitlab.com"
        var connections: MutableList<ConnectionState> = ArrayList()
        var migrated: Boolean = false
```
После `class State` добавить:
```kotlin
    class ConnectionState {
        var type: String = HostingType.GITLAB.name
        var url: String = ""
        var username: String = ""
    }
```
Поле `private var state` и `getState()`/`loadState()` не трогать (публичный `state` конфликтовал бы с `getState()`).

Удалить свойство `serverUrl`. Добавить:
```kotlin
    var connections: List<Connection>
        get() = state.connections.mapNotNull { c ->
            val type = HostingType.entries.firstOrNull { it.name == c.type } ?: return@mapNotNull null
            Connection(type, c.url, c.username.ifBlank { null })
        }
        set(value) {
            state.connections = value.mapTo(ArrayList()) { c ->
                ConnectionState().apply { type = c.type.name; url = c.url; username = c.username.orEmpty() }
            }
        }

    /** Adds [c], or replaces the connection with the same address, and stores its token. Blocking (password storage). */
    fun saveConnection(c: Connection, token: String) {
        connections = connections.filter { it.url != c.url } + c
        setToken(token, c.url)
    }

    fun removeConnection(c: Connection) {
        connections = connections.filter { it.url != c.url }
        setToken(null, c.url)
    }

    /** Before 0.3 the plugin knew one GitLab server; it becomes a connection, once. Blocking (password storage). */
    fun migrateLegacy() {
        if (state.migrated) return
        state.migrated = true
        legacyConnection(state)?.takeIf { getToken(it.url) != null }?.let { connections = listOf(it) }
    }
```
Токен:
```kotlin
    fun getToken(url: String): String? =
        PasswordSafe.instance.getPassword(credentials(url))?.takeIf { it.isNotBlank() }

    fun setToken(token: String?, url: String) {
        PasswordSafe.instance.setPassword(credentials(url), token?.trim()?.takeIf { it.isNotEmpty() })
    }
```
В `companion object` добавить:
```kotlin
        /** The connection the pre-0.3 single-server settings describe, if there are no connections yet. */
        internal fun legacyConnection(state: State): Connection? {
            val url = state.serverUrl.trim().trimEnd('/')
            return if (state.connections.isEmpty() && url.isNotEmpty()) Connection(HostingType.GITLAB, url) else null
        }
```
Импорты: `me.brekhin.mrnavigator.api.Connection`, `me.brekhin.mrnavigator.api.HostingType`.

- [ ] **Step 4: Remote matching** — `RemoteUrl.kt`, заменить `projectPath`:
```kotlin
        /** Project path for [remote] on the server of [c], or null if the remote is on another host. */
        fun projectPath(remote: RemoteUrl, c: Connection): String? {
            if (remote.host != serverHost(c.url)) return null
            val prefix = serverPathPrefix(c.url)
            val path = if (prefix.isNotEmpty() && remote.path.startsWith("$prefix/")) remote.path.removePrefix("$prefix/") else remote.path
            return path.takeIf { it.contains('/') }
        }
```
импорт `me.brekhin.mrnavigator.api.Connection`.

- [ ] **Step 5: Service** — `MrReviewService.kt`:
- `class Repo(val root: File, val name: String, val remoteName: String, val project: ProjectRef, val connection: Connection)` (KDoc: «A git repository of the IDE project whose remote points at one of the connected servers.»).
- `class SetupNeeded(message: String?, val connection: Connection? = null) : Exception(message)` (KDoc: «Nothing is connected yet (null message), no token, the token is rejected, or no remote matches a connection; [connection] — the one to fix.»).
- удалить `class Located`, `fun locate()`, `@Volatile private var cachedUser`, поле `reposCache` заменить на `@Volatile private var reposCache: Pair<List<Connection>, List<Repo>>? = null`; добавить `private val users = java.util.concurrent.ConcurrentHashMap<String, User>()` (KDoc: «Current user per connection URL.»).
- `repositories`:
  ```kotlin
      fun repositories(refresh: Boolean = false): List<Repo> {
          val settings = MrReviewSettings.getInstance()
          settings.migrateLegacy()
          val connections = settings.connections
          if (refresh) users.clear()
          if (!refresh) reposCache?.let { (c, list) -> if (c == connections) return list }
          if (connections.isEmpty()) throw SetupNeeded(null)

          val base = File(ideProject.basePath ?: throw MrException(msg("error.noProjectDir")))
          val roots = RepoScanner.find(base)
          if (roots.isEmpty()) throw MrException(msg("error.noRepos"))

          val hosts = LinkedHashSet<String>()
          val repos = roots.mapNotNull { root ->
              val remotes = try {
                  GitCli(root).remotes()
              } catch (e: Exception) {
                  emptyMap()
              }
              remotes.values.mapNotNullTo(hosts) { RemoteUrl.parse(it)?.host }
              remotes.entries.sortedBy { if (it.key == "origin") 0 else 1 }.firstNotNullOfOrNull { (name, url) ->
                  val remote = RemoteUrl.parse(url) ?: return@firstNotNullOfOrNull null
                  connections.firstNotNullOfOrNull { c ->
                      RemoteUrl.projectPath(remote, c)?.let { Repo(root, RepoScanner.displayName(base, root), name, ProjectRef(c.url, it), c) }
                  }
              }
          }
          if (repos.isEmpty()) throw SetupNeeded(msg("error.noRemote", connections.joinToString { it.url }, hosts.joinToString()))
          reposCache = connections to repos
          return repos
      }
  ```
- `detectedServers()` → 
  ```kotlin
      /** Hosts of the project's git remotes, origin first — suggestions for the setup form. Blocking. */
      fun detectedHosts(): List<String> = try {
          val base = ideProject.basePath?.let { File(it) }
          val roots = base?.let { RepoScanner.find(it) }.orEmpty()
          roots.flatMap { root ->
              GitCli(root).remotes().entries.sortedBy { if (it.key == "origin") 0 else 1 }.mapNotNull { RemoteUrl.parse(it.value)?.host }
          }.distinct()
      } catch (e: Exception) {
          emptyList()
      }
  ```
- клиент и пользователь:
  ```kotlin
      fun client(c: Connection): HostingClient {
          val token = MrReviewSettings.getInstance().getToken(c.url) ?: throw SetupNeeded(msg("error.noToken", c.url), c)
          return c.type.client(c, token)
      }

      fun currentUser(c: Connection, client: HostingClient): User = users.getOrPut(c.url) { client.currentUser() }

      /** Current user if already known (no network). */
      fun currentUserCached(c: Connection): User? = users[c.url]
  ```
- `loadSession`:
  ```kotlin
      fun loadSession(mr: MergeRequest): MrSession {
          val repo = selectedRepo(repositories())
          val client = client(repo.connection)
          runCatching { currentUser(repo.connection, client) }
          val full = client.mergeRequest(repo.project, mr.iid)
          val changes = client.changes(repo.project, full)
          val discussions = client.discussions(repo.project, full)
          val approved = client.approvedBy(repo.project, full)
          val s = MrSession(repo.project, repo.connection, GitCli(repo.root), repo.remoteName, full, changes, discussions, approved)
          loadViewed(s)
          session = s
          fireChanged()
          return s
      }
  ```
- во всех остальных методах `client()` → `client(s.connection)` (`refreshDiscussions`, `refreshApprovals`, `postComment`, `reply`, `setResolved`, `applySuggestions`).

- [ ] **Step 6: ConnectionForm** — `src/main/kotlin/me/brekhin/mrnavigator/ui/ConnectionForm.kt`:

```kotlin
package me.brekhin.mrnavigator.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.columns
import com.intellij.util.ui.UIUtil
import me.brekhin.mrnavigator.api.Connection
import me.brekhin.mrnavigator.api.HostingType
import me.brekhin.mrnavigator.api.User
import me.brekhin.mrnavigator.settings.MrReviewSettings
import me.brekhin.mrnavigator.util.msg

/**
 * Fields of one connection — hosting, address, user name (Bitbucket) and token — with a link to create
 * the token. Used by the tool window's setup form and by "Add connection" in the settings.
 */
class ConnectionForm {
    private val typeCombo = ComboBox(HostingType.entries.toTypedArray())
    private val urlField = JBTextField()
    private val usernameField = JBTextField()
    private val tokenField = JBPasswordField()
    private val usernameLabel = JBLabel()
    private val hint = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private var urlRow: Row? = null
    private var usernameRow: Row? = null

    private val type: HostingType get() = typeCombo.selectedItem as HostingType

    /** Adds the rows to [p]; [onSubmit] runs on Enter in the token field. */
    fun addTo(p: Panel, onSubmit: () -> Unit = {}) {
        with(p) {
            row(msg("connection.type")) { cell(typeCombo) }
            urlRow = row(msg("connection.url")) { cell(urlField).columns(COLUMNS_LARGE) }
            usernameRow = row(usernameLabel) { cell(usernameField).columns(COLUMNS_LARGE).comment(msg("connection.username.comment")) }
            row(msg("connection.token")) { cell(tokenField).columns(COLUMNS_LARGE) }
            row("") { cell(hint) }
            row("") { link(msg("connection.createToken")) { BrowserUtil.browse(type.tokenPageUrl(url())) } }
        }
        typeCombo.addActionListener { updateType() }
        tokenField.addActionListener { onSubmit() }
        updateType()
    }

    /** Pre-fills the form with an existing connection, or guesses it from a git remote [host]. */
    fun fill(c: Connection?, host: String? = null) {
        typeCombo.selectedItem = c?.type ?: host?.let { HostingType.guess(it) } ?: HostingType.GITLAB
        urlField.text = c?.url ?: host?.let { "https://$it" }.orEmpty()
        usernameField.text = c?.username.orEmpty()
        tokenField.text = ""
        updateType()
    }

    private fun url(): String =
        type.fixedUrl ?: urlField.text.trim().trimEnd('/').let { if (it.isEmpty() || "://" in it) it else "https://$it" }

    fun connection() = Connection(type, url(), usernameField.text.trim().takeIf { it.isNotEmpty() && type.usernameLabel != null })

    fun token(): String = String(tokenField.password).trim()

    fun clearToken() {
        tokenField.text = ""
    }

    /** What is missing, or null when the form can be submitted. */
    fun validate(): String? = if (url().isEmpty() || token().isEmpty()) msg("connection.fillIn") else null

    private fun updateType() {
        val t = type
        urlRow?.visible(t.fixedUrl == null)
        usernameRow?.visible(t.usernameLabel != null)
        t.usernameLabel?.let { usernameLabel.text = msg(it) }
        hint.text = "<html>${msg("connection.hint.${t.name}")}</html>"
    }

    companion object {
        /** Checks the connection and saves it with the token. Blocking: network and password storage. */
        fun verifyAndSave(c: Connection, token: String): User {
            val user = c.type.client(c, token).currentUser()
            MrReviewSettings.getInstance().saveConnection(c, token)
            return user
        }
    }
}
```

- [ ] **Step 7: SetupPanel** — весь файл `ui/SetupPanel.kt`:

```kotlin
package me.brekhin.mrnavigator.ui

import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import me.brekhin.mrnavigator.api.Connection
import me.brekhin.mrnavigator.core.MrReviewService
import me.brekhin.mrnavigator.settings.MrReviewConfigurable
import me.brekhin.mrnavigator.util.Markdown
import me.brekhin.mrnavigator.util.msg
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.Scrollable

/**
 * Shown instead of the list until the project's repository has a connection: hosting and address
 * (guessed from the git remotes), a token with a link to create one, "Connect".
 */
class SetupPanel(private val project: Project, private val onConnected: () -> Unit) : JPanel(BorderLayout()), Scrollable {
    private val form = ConnectionForm()
    private lateinit var connectButton: JButton
    // HTML labels wrap to the width they get (the panel follows the tool window width, see Scrollable below).
    private val status = JBLabel()
    private val reason = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val detected = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }

    init {
        val content = panel {
            row { label(msg("setup.title")).applyToComponent { font = JBFont.h3().asBold() } }
            row { text(msg("setup.intro")) }
            row { cell(reason).align(AlignX.FILL) }
            row { cell(detected) }
            form.addTo(this) { connect() }
            row {
                connectButton = button(msg("setup.connect")) { connect() }
                    .applyToComponent { putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true) }
                    .component
            }
            row { cell(status).align(AlignX.FILL) }
            row {
                link(msg("setup.allSettings")) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, MrReviewConfigurable::class.java)
                    onConnected()
                }
            }
        }
        content.border = JBUI.Borders.empty(16)
        add(content, BorderLayout.NORTH)
    }

    /** [why] explains what is missing; [connection] — the one to fix, otherwise the form is guessed from git remotes. */
    fun prepare(why: String?, connection: Connection?) {
        reason.text = why?.let { "<html>${Markdown.escape(it)}</html>" }.orEmpty()
        reason.isVisible = !why.isNullOrBlank()
        status.text = ""
        detected.text = ""
        form.fill(connection)
        if (connection != null) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val hosts = MrReviewService.getInstance(project).detectedHosts()
            ApplicationManager.getApplication().invokeLater({
                if (hosts.isEmpty()) return@invokeLater
                detected.text = msg("setup.detected", hosts.joinToString())
                form.fill(null, hosts.first())
            }, { project.isDisposed })
        }
    }

    private fun connect() {
        form.validate()?.let { showError(it); return }
        val c = form.connection()
        val token = form.token()
        connectButton.isEnabled = false
        status.foreground = UIUtil.getContextHelpForeground()
        status.text = msg("setup.checking")
        Bg.run(project, msg("setup.task", c.type.title), work = { ConnectionForm.verifyAndSave(c, token) }, onError = {
            connectButton.isEnabled = true
            showError(it.message ?: it.toString())
        }) { user ->
            connectButton.isEnabled = true
            form.clearToken()
            Notify.info(project, msg("setup.connected", c.type.title, user.name, user.username))
            onConnected()
        }
    }

    private fun showError(text: String) {
        status.foreground = UIUtil.getErrorForeground()
        status.text = "<html>${Markdown.escape(text)}</html>"
    }

    // Track the viewport width so that long messages wrap instead of scrolling sideways.
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = visibleRect.height
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false
}
```

- [ ] **Step 8: Settings page** — весь файл `settings/MrReviewConfigurable.kt`:

```kotlin
package me.brekhin.mrnavigator.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.ui.CollectionListModel
import com.intellij.ui.SimpleListCellRenderer
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.ui.dsl.builder.toNullableProperty
import me.brekhin.mrnavigator.api.ApiException
import me.brekhin.mrnavigator.api.Connection
import me.brekhin.mrnavigator.ui.ConnectionForm
import me.brekhin.mrnavigator.util.msg
import javax.swing.JComponent

/** Settings → Tools → MR Navigator */
class MrReviewConfigurable : BoundConfigurable("MR Navigator") {
    private val settings = MrReviewSettings.getInstance()

    private var suffixesText: String = ""
    // Connections are saved right away, with their tokens — not on Apply.
    private val connections = CollectionListModel<Connection>()
    private val list = JBList(connections).apply {
        emptyText.text = msg("settings.noConnections")
        cellRenderer = SimpleListCellRenderer.create("") { c -> listOfNotNull(c.type.title, c.url, c.username).joinToString(" · ") }
    }

    override fun createPanel(): DialogPanel {
        suffixesText = settings.hiddenSuffixes.joinToString("\n")
        connections.replaceAll(settings.connections)
        // Migrating pre-0.3 settings reads the password storage (OS keychain) — off the EDT.
        ApplicationManager.getApplication().executeOnPooledThread {
            settings.migrateLegacy()
            ApplicationManager.getApplication().invokeLater({ connections.replaceAll(settings.connections) }, ModalityState.any())
        }

        return panel {
            group(msg("settings.connections")) {
                row {
                    cell(
                        ToolbarDecorator.createDecorator(list)
                            .setAddAction { addConnection() }
                            .setRemoveAction { removeConnection() }
                            .disableUpDownActions()
                            .createPanel(),
                    ).align(AlignX.FILL)
                }
                row { button(msg("settings.check")) { checkConnection() } }
            }
            group(msg("settings.hiding")) {
                row {
                    checkBox(msg("settings.hide")).bindSelected(settings::hideEnabled)
                }
                row(msg("settings.suffixes")) {}
                row {
                    textArea().bindText(::suffixesText).rows(4).align(AlignX.FILL)
                        .applyToComponent { emptyText.text = ".pb.go" }
                        .comment(msg("settings.suffixes.comment"))
                }
            }
            group(msg("settings.git")) {
                row(msg("settings.gitPath")) {
                    textField().bindText(settings::gitExecutable).columns(COLUMNS_LARGE).comment(msg("settings.gitPath.comment"))
                }
                row {
                    checkBox(msg("settings.autoStash")).bindSelected(settings::autoStash)
                }
            }
            row(msg("settings.language")) {
                comboBox(listOf("auto", "en", "ru"), SimpleListCellRenderer.create("") { msg("settings.language.$it") })
                    .bindItem(settings::language.toNullableProperty())
                    .comment(msg("settings.language.comment"))
            }
        }
    }

    override fun apply() {
        super.apply()
        settings.hiddenSuffixes = suffixesText.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    }

    private fun addConnection() {
        val form = ConnectionForm()
        val dialog = object : DialogWrapper(list, true) {
            init {
                title = msg("settings.addConnection")
                init()
            }

            override fun createCenterPanel(): JComponent = panel { form.addTo(this) }.also { form.fill(null) }

            override fun doOKAction() {
                form.validate()?.let { setErrorText(it); return }
                val c = form.connection()
                val token = form.token()
                try {
                    ProgressManager.getInstance().runProcessWithProgressSynchronously(
                        ThrowableComputable { ConnectionForm.verifyAndSave(c, token) }, msg("connection.checking"), true, null,
                    )
                    super.doOKAction()
                } catch (e: Exception) {
                    setErrorText(e.message ?: e.toString())
                }
            }
        }
        if (dialog.showAndGet()) connections.replaceAll(settings.connections)
    }

    private fun removeConnection() {
        val c = list.selectedValue ?: return
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            Runnable { settings.removeConnection(c) }, msg("settings.removing"), false, null,
        )
        connections.replaceAll(settings.connections)
    }

    private fun checkConnection() {
        val c = list.selectedValue ?: return Messages.showInfoMessage(list, msg("settings.selectConnection"), "MR Navigator")
        try {
            val user = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable {
                    val token = settings.getToken(c.url) ?: throw ApiException(msg("error.noToken", c.url))
                    c.type.client(c, token).currentUser()
                },
                msg("connection.checking"), true, null,
            )
            Messages.showInfoMessage(list, msg("settings.connected", user.name, user.username), c.type.title)
        } catch (e: Exception) {
            Messages.showErrorDialog(list, e.message ?: e.toString(), c.type.title)
        }
    }
}
```

- [ ] **Step 9: Tool window and details**

`ui/MrToolWindow.kt`:
- переименовать `updatingRepos` → `updating`; `filter.addActionListener { if (!updating) reload() }`.
- `private class Loaded(val repos: List<Repo>, val repo: Repo, val filter: MrFilter, val mrs: List<MergeRequest>)`.
- `reload`:
  ```kotlin
      private fun reload(refreshRepos: Boolean = false) {
          val f = filter.selectedItem as? MrFilter ?: MrFilter.OPENED
          val query = search.text
          list.emptyText.text = msg("list.loading")
          model.clear()
          Bg.run(project, msg("list.loadingTask", currentType.term), work = {
              val repos = service.repositories(refreshRepos)
              val repo = service.selectedRepo(repos)
              val c = repo.connection
              val effective = f.takeIf { it in c.type.filters } ?: MrFilter.OPENED
              val client = service.client(c)
              try {
                  val me = if (effective == MrFilter.OPENED || effective == MrFilter.MERGED) runCatching { service.currentUser(c, client) }.getOrNull()
                  else service.currentUser(c, client)
                  Loaded(repos, repo, effective, client.mergeRequests(repo.project, effective, me, query))
              } catch (e: ApiException) {
                  throw if (e.status == 401) SetupNeeded(msg("list.unauthorized", c.type.title), c) else e
              }
          }, onError = { e ->
              if (e is SetupNeeded) {
                  setup.prepare(e.message, e.connection)
                  showCard(CARD_SETUP)
                  return@run
              }
              showCard(CARD_MAIN)
              list.emptyText.clear()
              list.emptyText.appendLine(e.message ?: msg("error"))
              list.emptyText.appendLine(msg("list.openSettings"), SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                  ShowSettingsUtil.getInstance().showSettingsDialog(project, MrReviewConfigurable::class.java)
                  reload(refreshRepos = true)
              }
          }) { loaded ->
              currentType = loaded.repo.connection.type
              showCard(CARD_MAIN)
              showRepos(loaded.repos, loaded.repo)
              showFilters(loaded.repo.connection.type.filters, loaded.filter)
              list.emptyText.text = msg("list.empty", currentType.term, loaded.repo.project.path)
              loaded.mrs.forEach { model.addElement(it) }
              // Keep the opened MR selected — but only if it is from this repository.
              val current = service.session?.takeIf { it.project == loaded.repo.project }?.mr?.iid
              val index = loaded.mrs.indexOfFirst { it.iid == current }
              if (index >= 0) list.selectedIndex = index else if (loaded.mrs.isEmpty()) details.clear()
          }
      }

      /** Bitbucket has no assignees: the filters follow the hosting of the selected repository. */
      private fun showFilters(filters: List<MrFilter>, selected: MrFilter) {
          updating = true
          try {
              filter.model = DefaultComboBoxModel(filters.toTypedArray())
              filter.selectedItem = selected
          } finally {
              updating = false
          }
      }
  ```
- `showRepos` использует `updating` вместо `updatingRepos`.
- `RepoRenderer`: `if (value.name != value.project.path) append(…)` оставить; добавить в конец `append("   ${value.connection.type.title}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)`.
- импорты: `me.brekhin.mrnavigator.api.ApiException`, `me.brekhin.mrnavigator.api.MrFilter`, `me.brekhin.mrnavigator.core.SetupNeeded`.

`ui/MrDetailsPanel.kt`:
- `isApprovedByMe`: `val me = service.currentUserCached(s.connection) ?: return false`.
- `toggleApprove`: `val c = service.client(s.connection)`.

- [ ] **Step 10: Keys** — в `MrBundle.properties` **удалить** ключи `setup.url`, `setup.token`, `setup.createToken`, `setup.pasteToken`, `settings.server`, `settings.server.comment`, `settings.token`, `settings.token.comment`, `settings.savingToken`, `settings.fillIn`, `settings.checking`, `error.noRemote.hosts`; **заменить** значения `setup.title`, `setup.intro`, `error.noRemote`; **добавить** остальное:

```properties
setup.title=Connect a code hosting
setup.intro=Choose where the repository is hosted and paste a personal access token. The token is kept in the IDE password storage.
error.noRemote=No git remote of the project points to a connected server ({0}). Remotes: {1}. Add a connection for your server.
error.noToken=No token is stored for {0}

connection.type=Hosting:
connection.url=Address:
connection.email=E-mail:
connection.username=Username:
connection.username.comment=Leave empty for an access token of a repository, project or workspace
connection.token=Token:
connection.createToken=Create a token →
connection.fillIn=Fill in the address and the token
connection.checking=Checking the connection…
connection.hint.GITLAB=Personal access token with the <b>api</b> scope

settings.connections=Connections
settings.noConnections=No connections: add one with +
settings.addConnection=Add Connection
settings.removing=Removing the connection
settings.selectConnection=Select a connection
```
То же в `MrBundle_ru.properties`:
```properties
setup.title=Подключение к хостингу кода
setup.intro=Выберите, где хранится репозиторий, и вставьте личный токен доступа. Токен хранится в хранилище паролей IDE.
error.noRemote=Ни один git remote проекта не указывает на подключённые серверы ({0}). Remote: {1}. Добавьте подключение к вашему серверу.
error.noToken=Для {0} не сохранён токен

connection.type=Хостинг:
connection.url=Адрес:
connection.email=E-mail:
connection.username=Имя пользователя:
connection.username.comment=Оставьте пустым для access token репозитория, проекта или workspace
connection.token=Токен:
connection.createToken=Создать токен →
connection.fillIn=Заполните адрес и токен
connection.checking=Проверка подключения…
connection.hint.GITLAB=Personal access token со scope <b>api</b>

settings.connections=Подключения
settings.noConnections=Подключений нет — добавьте кнопкой +
settings.addConnection=Новое подключение
settings.removing=Удаление подключения
settings.selectConnection=Выберите подключение
```

- [ ] **Step 11: Run tests and build**

Run: `./gradlew -q test buildPlugin`
Expected: PASS.

- [ ] **Step 12: Commit**

```bash
git add -A
git commit -m "Support several connections; pick one by the git remote host"   # + trailer lines
```

---
### Task 5: GitHub

**Files:**
- Create: `src/main/kotlin/me/brekhin/mrnavigator/api/GitHubClient.kt`
- Create: `src/test/kotlin/me/brekhin/mrnavigator/GitHubTest.kt`
- Modify: `api/Hosting.kt` (константа `GITHUB`, `client`, `tokenPageUrl`, `guess`)
- Modify: `core/DiffLineMap.kt` (`inHunk`), `diff/CommentMarkers.kt` (комментарии только в ханках), `util/Suggestion.kt` (формат без `-N+0`), `ui/ThreadPopup.kt` (кнопка suggestion по `canSuggest`)
- Modify: оба `.properties`; `LogicTest.suggestions`

**Interfaces:**
- Consumes: `HostingClient`, `Http`, `ApiException`, `MergeRequest(…, fetchRef)`, `Position(…, outdated)`, `Discussion(…, webUrl)` (Task 2); `msg` (Task 1).
- Produces: `HostingType.GITHUB`; `GitHubClient(serverUrl: String, token: String)` с companion `internal` функциями `apiUrl(serverUrl): String`, `nextLink(link: String?): String?`, `parsePull(m, mergeBase: String?): MergeRequest`, `parseFile(m): FileChange`, `parseThread(t, headSha: String?): Discussion`, `parseIssueComment(m): Discussion`, `commentPayload(body, p: Position, headSha: String?): Map<String, Any?>`, `approvers(reviews): List<String>`; `DiffLineMap.inHunk(line: Int, onNewSide: Boolean): Boolean`; `Suggestion.block(lines: List<String>, gitlab: Boolean = true)`.

- [ ] **Step 1: Write the failing tests** — `src/test/kotlin/me/brekhin/mrnavigator/GitHubTest.kt`:

```kotlin
package me.brekhin.mrnavigator

import me.brekhin.mrnavigator.api.DiffRefs
import me.brekhin.mrnavigator.api.GitHubClient
import me.brekhin.mrnavigator.api.HostingType
import me.brekhin.mrnavigator.api.LinePoint
import me.brekhin.mrnavigator.core.DiffLineMap
import me.brekhin.mrnavigator.util.Json
import me.brekhin.mrnavigator.util.obj
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubTest {
    private fun obj(s: String) = Json.parse(s).obj()
    private val map = DiffLineMap("@@ -2,3 +2,4 @@\n two\n-three\n+THREE\n+three-and-half\n four")

    @Test
    fun pull() {
        val m = obj("""{"number":42,"title":"Fix","body":"Desc","state":"open","draft":true,
            "user":{"login":"alice","id":1},"head":{"ref":"feature","sha":"h1"},"base":{"ref":"main","sha":"b1"},
            "html_url":"https://github.com/o/r/pull/42","updated_at":"2026-09-30T10:00:00Z","comments":2,"review_comments":3,
            "mergeable":false,"merged_at":null}""")
        val mr = GitHubClient.parsePull(m, "mb")
        assertEquals(42L, mr.iid)
        assertEquals("feature", mr.sourceBranch); assertEquals("main", mr.targetBranch)
        assertEquals(DiffRefs("mb", "b1", "h1"), mr.diffRefs); assertEquals("h1", mr.sha)
        assertEquals("refs/pull/42/head", mr.fetchRef); assertNull(mr.fetchUrl)
        assertEquals(5, mr.userNotesCount)
        assertTrue(mr.draft); assertTrue(mr.hasConflicts); assertEquals("open", mr.state)
        assertEquals("alice", mr.author?.username)
        assertNull(GitHubClient.parsePull(m, null).diffRefs)
        assertEquals("merged", GitHubClient.parsePull(obj("""{"number":1,"state":"closed","merged_at":"2026-09-30T10:00:00Z"}"""), null).state)
    }

    @Test
    fun files() {
        val renamed = GitHubClient.parseFile(obj("""{"filename":"new.go","previous_filename":"old.go","status":"renamed","changes":2,"patch":"@@ -1 +1 @@\n-a\n+b"}"""))
        assertEquals("old.go", renamed.oldPath); assertEquals("new.go", renamed.newPath); assertTrue(renamed.renamedFile)
        assertEquals(1 to 1, renamed.stats)
        val added = GitHubClient.parseFile(obj("""{"filename":"a.go","status":"added","changes":1,"patch":"@@ -0,0 +1 @@\n+x"}"""))
        assertTrue(added.newFile); assertEquals("a.go", added.oldPath)
        assertTrue(GitHubClient.parseFile(obj("""{"filename":"gone.go","status":"removed","changes":1,"patch":"@@ -1 +0,0 @@\n-x"}""")).deletedFile)
        assertTrue(GitHubClient.parseFile(obj("""{"filename":"big.json","status":"modified","changes":90000}""")).tooLarge)
        assertFalse(GitHubClient.parseFile(obj("""{"filename":"logo.png","status":"modified","changes":0}""")).tooLarge)
    }

    @Test
    fun threads() {
        val t = obj("""{"id":"PRRT_1","isResolved":true,"isOutdated":false,"path":"a.go","line":12,"startLine":10,
            "diffSide":"RIGHT","startDiffSide":"LEFT","resolvedBy":{"login":"bob"},
            "comments":{"nodes":[
              {"databaseId":7,"body":"hi","createdAt":"2026-09-30T10:00:00Z","url":"https://github.com/o/r/pull/1#discussion_r7","author":{"login":"alice","name":"Alice"}},
              {"databaseId":8,"body":"ok","createdAt":"2026-09-30T11:00:00Z","url":"u8","author":{"login":"bob"}}]}}""")
        val d = GitHubClient.parseThread(t, "h1")
        assertEquals("PRRT_1", d.id); assertEquals(2, d.notes.size)
        assertEquals("https://github.com/o/r/pull/1#discussion_r7", d.webUrl)
        assertTrue(d.resolved); assertEquals("bob", d.resolvedBy?.username); assertEquals("Alice", d.first?.author?.name)
        val p = d.position!!
        assertEquals(12, p.newLine); assertNull(p.oldLine); assertEquals("a.go", p.newPath)
        assertEquals(LinePoint("", "old", 10, null), p.lineRange!!.start)
        assertTrue(p.isMultiLine); assertFalse(p.isOutdatedFor("h1"))

        val old = GitHubClient.parseThread(obj("""{"id":"x","isOutdated":true,"path":"a.go","line":null,"originalLine":5,
            "diffSide":"LEFT","comments":{"nodes":[]}}"""), "h1")
        assertEquals(5, old.position!!.oldLine); assertTrue(old.position!!.isOutdatedFor("h1"))
    }

    @Test
    fun issueComment() {
        val d = GitHubClient.parseIssueComment(obj("""{"id":5,"body":"lgtm","user":{"login":"a"},"created_at":"2026-09-30T10:00:00Z","html_url":"w"}"""))
        assertNull(d.position); assertFalse(d.resolvable); assertEquals("w", d.webUrl); assertEquals(5L, d.first?.id)
    }

    @Test
    fun commentPayloads() {
        val single = map.position("b", "s", "h", "f.go", "f.go", 3, onNewSide = true)
        assertEquals(mapOf("body" to "x", "commit_id" to "h", "path" to "f.go", "line" to 3, "side" to "RIGHT"),
            GitHubClient.commentPayload("x", single, "h"))
        assertEquals("LEFT", GitHubClient.commentPayload("x", map.position("b", "s", "h", "f.go", "f.go", 3, onNewSide = false), "h")["side"])
        val range = map.position("b", "s", "h", "f.go", "f.go", end = DiffLineMap.Line(4, true), start = DiffLineMap.Line(3, false))
        val r = GitHubClient.commentPayload("x", range, "h")
        assertEquals(4, r["line"]); assertEquals("RIGHT", r["side"]); assertEquals(3, r["start_line"]); assertEquals("LEFT", r["start_side"])
    }

    @Test
    fun approvalsAndPaging() {
        val reviews = listOf(
            """{"user":{"login":"a"},"state":"APPROVED"}""", """{"user":{"login":"b"},"state":"APPROVED"}""",
            """{"user":{"login":"b"},"state":"COMMENTED"}""", """{"user":{"login":"a"},"state":"CHANGES_REQUESTED"}""",
        ).map { obj(it) }
        assertEquals(listOf("b"), GitHubClient.approvers(reviews))
        assertEquals("https://api.github.com/x?page=2",
            GitHubClient.nextLink("""<https://api.github.com/x?page=2>; rel="next", <https://api.github.com/x?page=5>; rel="last""""))
        assertNull(GitHubClient.nextLink("""<https://api.github.com/x?page=1>; rel="prev""""))
        assertNull(GitHubClient.nextLink(null))
    }

    @Test
    fun hostingAndHunks() {
        assertEquals(HostingType.GITHUB, HostingType.guess("github.com"))
        assertEquals(HostingType.GITHUB, HostingType.guess("github.corp.com"))
        assertEquals(HostingType.GITLAB, HostingType.guess("git.corp.com"))
        assertEquals("https://api.github.com", GitHubClient.apiUrl("https://github.com"))
        assertEquals("https://ghe.corp/api/v3", GitHubClient.apiUrl("https://ghe.corp/"))
        // Hunk covers new lines 2..5 and old lines 2..4.
        assertTrue(map.inHunk(2, onNewSide = true)); assertTrue(map.inHunk(5, onNewSide = true))
        assertFalse(map.inHunk(1, onNewSide = true)); assertFalse(map.inHunk(6, onNewSide = true))
        assertTrue(map.inHunk(4, onNewSide = false)); assertFalse(map.inHunk(5, onNewSide = false))
    }
}
```

`LogicTest.suggestions` — добавить в конец:
```kotlin
        // GitHub: the range comes from the comment itself.
        assertEquals("```suggestion\na\nb\n```", Suggestion.block(listOf("a", "b"), gitlab = false).text)
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew -q test --tests '*GitHubTest*'`
Expected: FAIL — `Unresolved reference 'GitHubClient'`.

- [ ] **Step 3: Implement**

`src/main/kotlin/me/brekhin/mrnavigator/api/GitHubClient.kt`:

```kotlin
package me.brekhin.mrnavigator.api

import me.brekhin.mrnavigator.util.a
import me.brekhin.mrnavigator.util.arr
import me.brekhin.mrnavigator.util.bool
import me.brekhin.mrnavigator.util.int
import me.brekhin.mrnavigator.util.long
import me.brekhin.mrnavigator.util.msg
import me.brekhin.mrnavigator.util.o
import me.brekhin.mrnavigator.util.obj
import me.brekhin.mrnavigator.util.str

/** GitHub REST v3 plus GraphQL for review threads (REST can't resolve them); github.com or Enterprise Server. */
class GitHubClient(serverUrl: String, token: String) : HostingClient {
    private val api = apiUrl(serverUrl)
    private val graphqlUrl = if (api == GITHUB_API) "$GITHUB_API/graphql" else serverUrl.trimEnd('/') + "/api/graphql"
    private val http = Http("GitHub") { it.setRequestProperty("Authorization", "Bearer $token") }

    private fun get(path: String): Any? = http.call("GET", api + path).json()
    private fun send(method: String, path: String, body: Any): Any? = http.call(method, api + path, body).json()

    /** Follows `Link: <…>; rel="next"` until exhausted or [limit] items. */
    private fun paged(path: String, limit: Int = 3000): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        var url: String? = api + path + (if ('?' in path) '&' else '?') + "per_page=100"
        while (url != null && out.size < limit) {
            val r = http.call("GET", url)
            out += r.json().arr().map { it.obj() }
            url = nextLink(r.header("Link"))
        }
        return out
    }

    private fun graphql(query: String, variables: Map<String, Any?>): Map<String, Any?> {
        val r = http.call("POST", graphqlUrl, mapOf("query" to query, "variables" to variables)).json().obj()
        r.a("errors").firstOrNull()?.obj()?.str("message")?.let { throw ApiException("GitHub: $it") }
        return r.o("data") ?: emptyMap()
    }

    private fun repo(p: ProjectRef) = "/repos/${p.path}"
    private fun pull(p: ProjectRef, mr: MergeRequest) = "${repo(p)}/pulls/${mr.iid}"

    override fun currentUser(): User = user(get("/user").obj()) ?: throw ApiException(msg("api.emptyUser", "GitHub"))

    /** The pulls API filters only by state, so the other filters and the search apply to its result. */
    override fun mergeRequests(project: ProjectRef, filter: MrFilter, me: User?, search: String?): List<MergeRequest> {
        val state = if (filter == MrFilter.MERGED) "closed" else "open"
        val login = me?.username
        return paged("${repo(project)}/pulls?state=$state&sort=updated&direction=desc", limit = 200)
            .filter { m ->
                when (filter) {
                    MrFilter.OPENED -> true
                    MrFilter.REVIEW_REQUESTED -> m.a("requested_reviewers").any { it.obj().str("login") == login }
                    MrFilter.ASSIGNED -> m.a("assignees").any { it.obj().str("login") == login }
                    MrFilter.MINE -> m.o("user")?.str("login") == login
                    MrFilter.MERGED -> m.str("merged_at") != null
                }
            }
            .filter { search.isNullOrBlank() || it.str("title").orEmpty().contains(search.trim(), ignoreCase = true) }
            .map { parsePull(it, null) }
    }

    override fun mergeRequest(project: ProjectRef, iid: Long): MergeRequest {
        val m = get("${repo(project)}/pulls/$iid").obj()
        val base = m.o("base")?.str("sha")
        val head = m.o("head")?.str("sha")
        // The PR diff is against the merge base, not the target branch head.
        val mergeBase = if (base != null && head != null) {
            get("${repo(project)}/compare/$base...$head?per_page=1").obj().o("merge_base_commit")?.str("sha")
        } else null
        return parsePull(m, mergeBase)
    }

    override fun changes(project: ProjectRef, mr: MergeRequest): List<FileChange> =
        paged("${pull(project, mr)}/files").map { parseFile(it) }

    override fun discussions(project: ProjectRef, mr: MergeRequest): List<Discussion> {
        val owner = project.path.substringBefore('/')
        val name = project.path.substringAfter('/')
        val threads = ArrayList<Discussion>()
        var cursor: String? = null
        do {
            val page = graphql(THREADS, mapOf("owner" to owner, "name" to name, "number" to mr.iid, "cursor" to cursor))
                .o("repository")?.o("pullRequest")?.o("reviewThreads") ?: break
            page.a("nodes").mapTo(threads) { parseThread(it.obj(), mr.diffRefs?.headSha) }
            cursor = page.o("pageInfo")?.takeIf { it.bool("hasNextPage") }?.str("endCursor")
        } while (cursor != null)
        return paged("${repo(project)}/issues/${mr.iid}/comments").map { parseIssueComment(it) } + threads
    }

    override fun createDiscussion(project: ProjectRef, mr: MergeRequest, body: String, position: Position?) {
        if (position == null) send("POST", "${repo(project)}/issues/${mr.iid}/comments", mapOf("body" to body))
        else send("POST", "${pull(project, mr)}/comments", commentPayload(body, position, mr.diffRefs?.headSha ?: mr.sha))
    }

    /** General comments have no threads on GitHub: a reply to one is a new comment. */
    override fun reply(project: ProjectRef, mr: MergeRequest, d: Discussion, body: String) {
        val first = d.first ?: return
        if (d.id.startsWith(ISSUE)) send("POST", "${repo(project)}/issues/${mr.iid}/comments", mapOf("body" to body))
        else send("POST", "${pull(project, mr)}/comments/${first.id}/replies", mapOf("body" to body))
    }

    override fun resolve(project: ProjectRef, mr: MergeRequest, d: Discussion, resolved: Boolean) {
        val mutation = if (resolved) "resolveReviewThread" else "unresolveReviewThread"
        graphql("mutation(\$id: ID!) { $mutation(input: {threadId: \$id}) { thread { id } } }", mapOf("id" to d.id))
    }

    override fun approve(project: ProjectRef, mr: MergeRequest) {
        send("POST", "${pull(project, mr)}/reviews", mapOf("event" to "APPROVE", "commit_id" to mr.sha))
    }

    /** A reviewer can't withdraw an approval on GitHub, only dismiss it — that needs write access to the repository. */
    override fun unapprove(project: ProjectRef, mr: MergeRequest) {
        val me = currentUser().username
        val review = paged("${pull(project, mr)}/reviews")
            .lastOrNull { it.o("user")?.str("login") == me && it.str("state") == "APPROVED" } ?: return
        send("PUT", "${pull(project, mr)}/reviews/${review.long("id")}/dismissals",
            mapOf("message" to "Approval withdrawn", "event" to "DISMISS"))
    }

    override fun approvedBy(project: ProjectRef, mr: MergeRequest): List<String> = approvers(paged("${pull(project, mr)}/reviews"))

    companion object {
        private const val GITHUB_API = "https://api.github.com"
        /** Prefix of the ids of general (issue) comments — they are not review threads. */
        private const val ISSUE = "issue:"

        private val THREADS = """
            query(${'$'}owner: String!, ${'$'}name: String!, ${'$'}number: Int!, ${'$'}cursor: String) {
              repository(owner: ${'$'}owner, name: ${'$'}name) {
                pullRequest(number: ${'$'}number) {
                  reviewThreads(first: 100, after: ${'$'}cursor) {
                    pageInfo { hasNextPage endCursor }
                    nodes {
                      id isResolved isOutdated path line startLine originalLine originalStartLine diffSide startDiffSide
                      resolvedBy { login }
                      comments(first: 100) { nodes { databaseId body createdAt url author { login ... on User { name } } } }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        internal fun apiUrl(serverUrl: String): String =
            serverUrl.trimEnd('/').let { if (it.substringAfter("://") == "github.com") GITHUB_API else "$it/api/v3" }

        internal fun nextLink(link: String?): String? =
            link?.split(',')?.firstOrNull { it.contains("rel=\"next\"") }?.substringAfter('<')?.substringBefore('>')

        internal fun user(m: Map<String, Any?>?): User? = m?.let {
            val login = it.str("login") ?: ""
            User(it.long("id") ?: 0, login, it.str("name")?.takeIf { n -> n.isNotBlank() } ?: login)
        }

        internal fun parsePull(m: Map<String, Any?>, mergeBase: String?): MergeRequest {
            val base = m.o("base")
            val head = m.o("head")
            val baseSha = base?.str("sha")
            val headSha = head?.str("sha")
            val number = m.long("number") ?: 0
            return MergeRequest(
                iid = number,
                title = m.str("title") ?: "",
                description = m.str("body") ?: "",
                state = if (m.str("merged_at") != null) "merged" else m.str("state") ?: "",
                draft = m.bool("draft"),
                author = user(m.o("user")),
                sourceBranch = head?.str("ref") ?: "",
                targetBranch = base?.str("ref") ?: "",
                webUrl = m.str("html_url") ?: "",
                sha = headSha,
                diffRefs = if (mergeBase != null && baseSha != null && headSha != null) DiffRefs(mergeBase, baseSha, headSha) else null,
                updatedAt = m.str("updated_at"),
                userNotesCount = (m.int("comments") ?: 0) + (m.int("review_comments") ?: 0),
                hasConflicts = m["mergeable"] == false,
                fetchRef = "refs/pull/$number/head",
            )
        }

        /** [FileChange.diff] is GitHub's `patch` — hunks only, like GitLab's; none for binary and huge files. */
        internal fun parseFile(m: Map<String, Any?>): FileChange {
            val path = m.str("filename") ?: ""
            val status = m.str("status")
            val patch = m.str("patch")
            return FileChange(
                oldPath = m.str("previous_filename") ?: path,
                newPath = path,
                newFile = status == "added",
                deletedFile = status == "removed",
                renamedFile = status == "renamed",
                diff = patch ?: "",
                tooLarge = patch == null && (m.int("changes") ?: 0) > 0,
            )
        }

        /** A review thread; an outdated one keeps its original lines and is drawn only on the Discussion tab. */
        internal fun parseThread(t: Map<String, Any?>, headSha: String?): Discussion {
            val outdated = t.bool("isOutdated")
            val line = (if (outdated) null else t.int("line")) ?: t.int("originalLine")
            val start = (if (outdated) null else t.int("startLine")) ?: t.int("originalStartLine")
            val right = t.str("diffSide") != "LEFT"
            val startRight = (t.str("startDiffSide") ?: t.str("diffSide")) != "LEFT"
            fun point(l: Int, onRight: Boolean) = LinePoint("", if (onRight) "new" else "old", if (onRight) null else l, if (onRight) l else null)
            val path = t.str("path")
            val position = Position(
                null, null, headSha, path, path,
                oldLine = if (right) null else line,
                newLine = if (right) line else null,
                lineRange = if (start != null && line != null) LineRange(point(start, startRight), point(line, right)) else null,
                outdated = outdated,
            )
            val resolved = t.bool("isResolved")
            val resolvedBy = user(t.o("resolvedBy"))
            val comments = t.o("comments")?.a("nodes").orEmpty().map { it.obj() }
            val notes = comments.map { c ->
                Note(
                    id = c.long("databaseId") ?: 0, body = c.str("body") ?: "", author = user(c.o("author")),
                    createdAt = c.str("createdAt"), system = false, resolvable = true, resolved = resolved,
                    position = position, resolvedBy = resolvedBy,
                )
            }
            return Discussion(t.str("id") ?: "", notes, comments.firstOrNull()?.str("url"))
        }

        internal fun parseIssueComment(m: Map<String, Any?>): Discussion {
            val note = Note(
                id = m.long("id") ?: 0, body = m.str("body") ?: "", author = user(m.o("user")),
                createdAt = m.str("created_at"), system = false, resolvable = false, resolved = false, position = null,
            )
            return Discussion(ISSUE + note.id, listOf(note), m.str("html_url"))
        }

        /** Body of a line comment: RIGHT side when the point has a line of the new version, LEFT otherwise. */
        internal fun commentPayload(body: String, p: Position, headSha: String?): Map<String, Any?> {
            val payload = linkedMapOf<String, Any?>(
                "body" to body,
                "commit_id" to headSha,
                "path" to (p.newPath ?: p.oldPath),
                "line" to (p.newLine ?: p.oldLine),
                "side" to if (p.newLine != null) "RIGHT" else "LEFT",
            )
            p.lineRange?.takeIf { p.isMultiLine }?.start?.let { s ->
                payload["start_line"] = s.newLine ?: s.oldLine
                payload["start_side"] = if (s.newLine != null) "RIGHT" else "LEFT"
            }
            return payload
        }

        /** Users whose latest decisive review is an approval (comment-only reviews don't change the state). */
        internal fun approvers(reviews: List<Map<String, Any?>>): List<String> {
            val last = LinkedHashMap<String, String>()
            for (r in reviews) {
                val login = r.o("user")?.str("login") ?: continue
                val state = r.str("state") ?: continue
                if (state != "COMMENTED" && state != "PENDING") last[login] = state
            }
            return last.filterValues { it == "APPROVED" }.keys.toList()
        }
    }
}
```

`api/Hosting.kt`:
- после `GITLAB(…)` (заменив `;` на `,`):
  ```kotlin
      GITHUB("GitHub", '#', "PR", MrFilter.entries, canSuggest = true, commentsOutsideHunks = false, usernameLabel = null, fixedUrl = null);
  ```
- `client`: `GITHUB -> GitHubClient(c.url, token)`.
- `tokenPageUrl`: `GITHUB -> "$url/settings/tokens/new?description=MR%20Navigator&scopes=repo"`.
- `guess`:
  ```kotlin
          fun guess(host: String): HostingType = when {
              "github" in host -> GITHUB
              else -> GITLAB
          }
  ```

`core/DiffLineMap.kt` — после `isRemoved`:
```kotlin
    /** Whether [line] lies in a hunk — GitHub and Bitbucket accept comments only on such lines. */
    fun inHunk(line: Int, onNewSide: Boolean): Boolean = hunks.any { h ->
        if (onNewSide) line >= h.newStart && line < h.newStart + h.newCount
        else line >= h.oldStart && line < h.oldStart + h.oldCount
    }
```

`diff/CommentMarkers.kt`:
- в `hover()` после `val (side, fileLine) = mapping.fromEditor(line) ?: return`:
  ```kotlin
          if (!session.type.commentsOutsideHunks && !session.lineMap(ctx.change).inHunk(fileLine + 1, side == Side.RIGHT)) return
  ```
- в `newComment()` сразу после `val c = ctx.change`:
  ```kotlin
          val map = s.lineMap(c)
          if (!s.type.commentsOutsideHunks && listOfNotNull(start, endSide to endLine).any { (side, line) -> !map.inHunk(line + 1, side == Side.RIGHT) }) {
              Notify.info(project, msg("diff.outsideHunk", s.type.title))
              return
          }
  ```
  и ниже `s.lineMap(c).position(` → `map.position(`.

`util/Suggestion.kt`:
```kotlin
    /** The block pre-filled with the current [lines]: GitLab needs the range in the header, GitHub takes it from the comment. */
    fun block(lines: List<String>, gitlab: Boolean = true): Block {
        val content = lines.joinToString("\n")
        val fence = fence(content)
        val head = if (gitlab) "${fence}suggestion:-${maxOf(lines.size - 1, 0)}+0\n" else "${fence}suggestion\n"
        val text = head + content + "\n" + fence
        return Block(text, head.length, head.length + content.length)
    }
```
и KDoc объекта: дописать «GitHub: the same block without `:-N+0`.».

`ui/ThreadPopup.kt` — `suggestionButton(input, lines, term)` → `suggestionButton(input, lines, type: HostingType)`:
```kotlin
    private fun suggestionButton(input: JBTextArea, lines: List<String>?, type: HostingType): JButton =
        JButton(msg("popup.suggest"), AllIcons.Actions.IntentionBulb).apply {
            isVisible = type.canSuggest && !lines.isNullOrEmpty()
            toolTipText = msg("popup.suggest.tooltip", type.term)
            addActionListener {
                val block = Suggestion.block(lines ?: return@addActionListener, gitlab = type == HostingType.GITLAB)
                ...остальное без изменений...
```
оба вызова → `suggestionButton(input, suggestionLines, session.type)`.

Ключи (`MrBundle.properties` / `_ru`):
```properties
connection.hint.GITHUB=Classic token with the <b>repo</b> scope
diff.outsideHunk={0} accepts comments only on lines of the diff
```
```properties
connection.hint.GITHUB=Classic-токен со scope <b>repo</b>
diff.outsideHunk={0} принимает комментарии только к строкам diff
```

- [ ] **Step 4: Run tests and build**

Run: `./gradlew -q test buildPlugin`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Add GitHub pull requests"   # + trailer lines
```

---

### Task 6: Разбор raw diff

**Files:**
- Create: `src/main/kotlin/me/brekhin/mrnavigator/core/UnifiedDiff.kt`
- Create: `src/test/kotlin/me/brekhin/mrnavigator/UnifiedDiffTest.kt`

**Interfaces:**
- Consumes: `FileChange` (без изменений).
- Produces: `object UnifiedDiff { fun split(text: String): List<FileChange> }`.

- [ ] **Step 1: Write the failing test**

```kotlin
package me.brekhin.mrnavigator

import me.brekhin.mrnavigator.core.UnifiedDiff
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnifiedDiffTest {
    @Test
    fun splitsGitDiff() {
        val diff = """
            diff --git a/src/a.go b/src/a.go
            index 1111111..2222222 100644
            --- a/src/a.go
            +++ b/src/a.go
            @@ -1,2 +1,2 @@
             x
            -y
            +z
            diff --git a/new.go b/new.go
            new file mode 100644
            --- /dev/null
            +++ b/new.go
            @@ -0,0 +1 @@
            +n
            diff --git a/gone.go b/gone.go
            deleted file mode 100644
            --- a/gone.go
            +++ /dev/null
            @@ -1 +0,0 @@
            -g
            diff --git a/old name.go b/new name.go
            similarity index 100%
            rename from old name.go
            rename to new name.go
            diff --git a/logo.png b/logo.png
            Binary files a/logo.png and b/logo.png differ
        """.trimIndent() + "\n"
        val files = UnifiedDiff.split(diff)
        assertEquals(listOf("src/a.go", "new.go", "gone.go", "new name.go", "logo.png"), files.map { it.displayPath })

        val a = files[0]
        assertEquals("@@ -1,2 +1,2 @@\n x\n-y\n+z", a.diff)
        assertEquals(1 to 1, a.stats)
        assertTrue(files[1].newFile); assertEquals("new.go", files[1].oldPath)
        assertTrue(files[2].deletedFile); assertEquals("gone.go", files[2].newPath)
        val renamed = files[3]
        assertTrue(renamed.renamedFile); assertEquals("old name.go", renamed.oldPath); assertEquals("", renamed.diff)
        assertFalse(files[4].newFile); assertEquals("logo.png", files[4].oldPath); assertEquals("", files[4].diff)
    }

    @Test
    fun bitbucketServerPrefixes() {
        val files = UnifiedDiff.split("diff --git src://a.go dst://a.go\n--- src://a.go\n+++ dst://a.go\n@@ -1 +1 @@\n-a\n+b\n")
        assertEquals(1, files.size)
        assertEquals("a.go", files[0].oldPath); assertEquals("a.go", files[0].newPath)
        assertEquals("@@ -1 +1 @@\n-a\n+b", files[0].diff)
    }

    @Test
    fun emptyDiff() {
        assertEquals(emptyList(), UnifiedDiff.split(""))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew -q test --tests '*UnifiedDiffTest*'`
Expected: FAIL — `Unresolved reference 'UnifiedDiff'`.

- [ ] **Step 3: Implement** — `src/main/kotlin/me/brekhin/mrnavigator/core/UnifiedDiff.kt`:

```kotlin
package me.brekhin.mrnavigator.core

import me.brekhin.mrnavigator.api.FileChange

/**
 * Splits a raw multi-file `git diff` (Bitbucket's pull request diff) into files with hunks only —
 * the shape GitLab's /diffs and GitHub's /files give, which [DiffLineMap] reads.
 */
object UnifiedDiff {
    private const val DEV_NULL = "/dev/null"
    // git's a/ b/, and src:// dst:// of Bitbucket Data Center.
    private val PREFIXES = listOf("a/", "b/", "src://", "dst://")

    fun split(text: String): List<FileChange> {
        val files = ArrayList<MutableList<String>>()
        for (line in text.replace("\r\n", "\n").split('\n')) {
            if (line.startsWith("diff --git ")) files += mutableListOf(line) else files.lastOrNull()?.add(line)
        }
        return files.map { file(it) }
    }

    private fun file(lines: List<String>): FileChange {
        val firstHunk = lines.indexOfFirst { it.startsWith("@@") }.let { if (it < 0) lines.size else it }
        val header = lines.subList(0, firstHunk)
        fun value(prefix: String) = header.firstOrNull { it.startsWith(prefix) }?.removePrefix(prefix)
        val minus = value("--- ")?.let(::path)
        val plus = value("+++ ")?.let(::path)
        val (gitOld, gitNew) = gitPaths(header.first().removePrefix("diff --git "))
        val newFile = value("new file mode") != null || minus == DEV_NULL
        val deleted = value("deleted file mode") != null || plus == DEV_NULL
        val renameFrom = value("rename from ")?.let(::unquote)
        val oldPath = renameFrom ?: minus?.takeIf { it != DEV_NULL } ?: gitOld
        val newPath = value("rename to ")?.let(::unquote) ?: plus?.takeIf { it != DEV_NULL } ?: gitNew
        return FileChange(
            // Like GitLab: a new file has old_path == new_path, a deleted one new_path == old_path.
            oldPath = if (newFile) newPath else oldPath,
            newPath = if (deleted) oldPath else newPath,
            newFile = newFile,
            deletedFile = deleted,
            renamedFile = renameFrom != null,
            diff = lines.subList(firstHunk, lines.size).joinToString("\n").trimEnd('\n'),
            tooLarge = false,
        )
    }

    /** "a/x.go" → "x.go", without the quotes git puts around unusual names and the tab with a timestamp. */
    private fun path(raw: String): String {
        val p = unquote(raw.substringBefore('\t'))
        return PREFIXES.firstOrNull { p.startsWith(it) }?.let { p.removePrefix(it) } ?: p
    }

    private fun unquote(s: String) = s.trim().removeSurrounding("\"")

    /** Paths from "a/x b/y" — for files without ---/+++ lines (binary, mode or rename only). */
    private fun gitPaths(rest: String): Pair<String, String> {
        val at = listOf(" b/", " \"b/", " dst://", " \"dst://").map { rest.indexOf(it) }.filter { it > 0 }.minOrNull()
            ?: return path(rest) to path(rest)
        return path(rest.substring(0, at)) to path(rest.substring(at + 1))
    }
}
```

- [ ] **Step 4: Run tests**

Run: `./gradlew -q test --tests '*UnifiedDiffTest*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Split raw multi-file diffs into per-file hunks"   # + trailer lines
```

---

### Task 7: Bitbucket Cloud

**Files:**
- Create: `src/main/kotlin/me/brekhin/mrnavigator/api/BitbucketCloudClient.kt`
- Create: `src/test/kotlin/me/brekhin/mrnavigator/BitbucketTest.kt`
- Modify: `api/Hosting.kt`, оба `.properties`

**Interfaces:**
- Consumes: `UnifiedDiff.split` (Task 6), `basicOrBearer`, `Http` (Task 2).
- Produces: `HostingType.BITBUCKET_CLOUD`; `BitbucketCloudClient(token: String, username: String?)` с companion `internal` `user(m)`, `parsePull(m, refs: DiffRefs?, repoPath: String?)`, `threads(comments)`, `position(inline)`, `commentPayload(body, p: Position?)`, `quote(s)`.

- [ ] **Step 1: Write the failing test** — `src/test/kotlin/me/brekhin/mrnavigator/BitbucketTest.kt`:

```kotlin
package me.brekhin.mrnavigator

import me.brekhin.mrnavigator.api.BitbucketCloudClient
import me.brekhin.mrnavigator.api.DiffRefs
import me.brekhin.mrnavigator.api.HostingType
import me.brekhin.mrnavigator.api.LinePoint
import me.brekhin.mrnavigator.core.DiffLineMap
import me.brekhin.mrnavigator.util.Json
import me.brekhin.mrnavigator.util.obj
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BitbucketTest {
    private fun obj(s: String) = Json.parse(s).obj()
    private val map = DiffLineMap("@@ -2,3 +2,4 @@\n two\n-three\n+THREE\n+three-and-half\n four")

    @Test
    fun cloudPull() {
        val m = obj("""{"id":7,"title":"T","summary":{"raw":"Body"},"state":"OPEN","draft":false,
            "author":{"display_name":"Alice","nickname":"alice","account_id":"557058:1"},
            "source":{"branch":{"name":"feat"},"commit":{"hash":"abc123def456"},"repository":{"full_name":"fork/repo"}},
            "destination":{"branch":{"name":"main"},"commit":{"hash":"0123456789ab"},"repository":{"full_name":"team/repo"}},
            "links":{"html":{"href":"https://bitbucket.org/team/repo/pull-requests/7"}},
            "updated_on":"2026-09-30T10:00:00.123456+00:00","comment_count":4}""")
        val mr = BitbucketCloudClient.parsePull(m, DiffRefs("b", "s", "h"), "team/repo")
        assertEquals(7L, mr.iid); assertEquals("Body", mr.description); assertEquals("open", mr.state)
        assertEquals("feat", mr.sourceBranch); assertEquals("main", mr.targetBranch); assertEquals(4, mr.userNotesCount)
        assertEquals("refs/heads/feat", mr.fetchRef); assertEquals("https://bitbucket.org/fork/repo.git", mr.fetchUrl)
        assertEquals("alice", mr.author?.username); assertEquals("Alice", mr.author?.name); assertEquals("h", mr.sha)
        assertEquals("https://bitbucket.org/team/repo/pull-requests/7", mr.webUrl)
        assertNull(BitbucketCloudClient.parsePull(m, null, "FORK/REPO").fetchUrl)
    }

    @Test
    fun cloudThreads() {
        val comments = listOf(
            """{"id":1,"content":{"raw":"root"},"user":{"nickname":"a","display_name":"A"},"created_on":"2026-09-30T10:00:00Z",
               "inline":{"path":"x.go","to":12,"from":null,"start_to":10},
               "links":{"html":{"href":"https://bitbucket.org/t/r/pull-requests/7/_/diff#comment-1"}},
               "resolution":{"type":"comment_resolution","user":{"nickname":"b","display_name":"B"}}}""",
            """{"id":2,"content":{"raw":"reply"},"user":{"nickname":"b"},"created_on":"2026-09-30T11:00:00Z","parent":{"id":1}}""",
            """{"id":3,"content":{"raw":"reply to reply"},"user":{"nickname":"a"},"created_on":"2026-09-30T12:00:00Z","parent":{"id":2}}""",
            """{"id":4,"content":{"raw":"general"},"user":{"nickname":"c"},"created_on":"2026-09-30T09:00:00Z"}""",
        ).map { obj(it) }
        val threads = BitbucketCloudClient.threads(comments)
        assertEquals(setOf("1", "4"), threads.map { it.id }.toSet())
        val t = threads.first { it.id == "1" }
        assertEquals(listOf("root", "reply", "reply to reply"), t.notes.map { it.body })
        assertTrue(t.resolved); assertEquals("B", t.resolvedBy?.name)
        assertEquals("https://bitbucket.org/t/r/pull-requests/7/_/diff#comment-1", t.webUrl)
        assertEquals(12, t.position!!.newLine)
        assertEquals(LinePoint("", "new", null, 10), t.position!!.lineRange!!.start)
        assertNull(threads.first { it.id == "4" }.position)
    }

    @Test
    fun threadsKeepRepliesOfDeletedRoot() {
        val comments = listOf(
            """{"id":1,"deleted":true,"content":{"raw":""},"inline":{"path":"x.go","from":3}}""",
            """{"id":2,"content":{"raw":"still here"},"parent":{"id":1},"created_on":"2026-09-30T11:00:00Z"}""",
        ).map { obj(it) }
        val t = BitbucketCloudClient.threads(comments).single()
        assertEquals(listOf("still here"), t.notes.map { it.body })
        assertEquals(3, t.position!!.oldLine)
    }

    @Test
    fun cloudPayloads() {
        val range = map.position("b", "s", "h", "f.go", "f.go", end = DiffLineMap.Line(4, true), start = DiffLineMap.Line(3, false))
        assertEquals(mapOf("content" to mapOf("raw" to "x"), "inline" to mapOf("path" to "f.go", "to" to 4, "start_from" to 3)),
            BitbucketCloudClient.commentPayload("x", range))
        assertEquals(mapOf("content" to mapOf("raw" to "x")), BitbucketCloudClient.commentPayload("x", null))
        val removed = map.position("b", "s", "h", "f.go", "f.go", 3, onNewSide = false)
        assertEquals(mapOf("path" to "f.go", "from" to 3), BitbucketCloudClient.commentPayload("x", removed)["inline"])
    }

    @Test
    fun cloudQueryAndHosting() {
        assertEquals("\"a \\\"b\\\" \\\\c\"", BitbucketCloudClient.quote("a \"b\" \\c"))
        assertEquals(HostingType.BITBUCKET_CLOUD, HostingType.guess("bitbucket.org"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew -q test --tests '*BitbucketTest*'`
Expected: FAIL — `Unresolved reference 'BitbucketCloudClient'`.

- [ ] **Step 3: Implement** — `src/main/kotlin/me/brekhin/mrnavigator/api/BitbucketCloudClient.kt`:

```kotlin
package me.brekhin.mrnavigator.api

import me.brekhin.mrnavigator.core.UnifiedDiff
import me.brekhin.mrnavigator.util.a
import me.brekhin.mrnavigator.util.bool
import me.brekhin.mrnavigator.util.int
import me.brekhin.mrnavigator.util.long
import me.brekhin.mrnavigator.util.msg
import me.brekhin.mrnavigator.util.o
import me.brekhin.mrnavigator.util.obj
import me.brekhin.mrnavigator.util.str
import java.net.URLEncoder

/**
 * Bitbucket Cloud REST 2.0. With [username] (the Atlassian account e-mail) the token is an API token
 * sent with Basic auth; without it — a repository, project or workspace access token sent as Bearer.
 */
class BitbucketCloudClient(token: String, username: String?) : HostingClient {
    private val api = "https://api.bitbucket.org/2.0"
    private val http = Http("Bitbucket") { it.setRequestProperty("Authorization", basicOrBearer(token, username)) }

    private fun get(url: String): Map<String, Any?> = http.call("GET", if (url.startsWith("http")) url else api + url).json().obj()
    private fun send(method: String, path: String, body: Any?): Any? = http.call(method, api + path, body).json()

    /** Follows the `next` links until exhausted or [limit] items. */
    private fun paged(path: String, limit: Int = 2000): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        var url: String? = api + path
        while (url != null && out.size < limit) {
            val page = get(url)
            out += page.a("values").map { it.obj() }
            url = page.str("next")
        }
        return out
    }

    private fun repo(path: String) = "/repositories/$path"
    private fun pr(p: ProjectRef, mr: MergeRequest) = "${repo(p.path)}/pullrequests/${mr.iid}"

    override fun currentUser(): User = user(get("/user")) ?: throw ApiException(msg("api.emptyUser", "Bitbucket"))

    override fun mergeRequests(project: ProjectRef, filter: MrFilter, me: User?, search: String?): List<MergeRequest> {
        val q = listOfNotNull(
            when (filter) {
                MrFilter.REVIEW_REQUESTED -> me?.let { "reviewers.nickname=${quote(it.username)}" }
                MrFilter.MINE -> me?.let { "author.nickname=${quote(it.username)}" }
                else -> null
            },
            search?.trim()?.takeIf { it.isNotEmpty() }?.let { "title ~ ${quote(it)}" },
        ).joinToString(" AND ")
        val state = if (filter == MrFilter.MERGED) "MERGED" else "OPEN"
        val params = "state=$state&sort=-updated_on&pagelen=50" + (if (q.isNotEmpty()) "&q=" + URLEncoder.encode(q, Charsets.UTF_8) else "")
        return paged("${repo(project.path)}/pullrequests?$params", limit = 200).map { parsePull(it, null, project.path) }
    }

    override fun mergeRequest(project: ProjectRef, iid: Long): MergeRequest {
        val m = get("${repo(project.path)}/pullrequests/$iid")
        val sourceRepo = m.o("source")?.o("repository")?.str("full_name") ?: project.path
        val src = m.o("source")?.o("commit")?.str("hash")
        val dst = m.o("destination")?.o("commit")?.str("hash")
        // The pull request carries 12-character hashes; git needs full ones.
        val refs = if (src != null && dst != null) {
            val head = get("${repo(sourceRepo)}/commit/$src").str("hash")
            val start = get("${repo(project.path)}/commit/$dst").str("hash")
            val base = get("${repo(project.path)}/merge-base/$src..$dst").str("hash")
            if (head != null && start != null && base != null) DiffRefs(base, start, head) else null
        } else null
        return parsePull(m, refs, project.path)
    }

    /** Redirects to /diff/…, a raw git diff against the merge base. */
    override fun changes(project: ProjectRef, mr: MergeRequest): List<FileChange> =
        UnifiedDiff.split(http.call("GET", api + pr(project, mr) + "/diff", accept = "text/plain").body)

    override fun discussions(project: ProjectRef, mr: MergeRequest): List<Discussion> =
        threads(paged("${pr(project, mr)}/comments?pagelen=100"))

    override fun createDiscussion(project: ProjectRef, mr: MergeRequest, body: String, position: Position?) {
        send("POST", "${pr(project, mr)}/comments", commentPayload(body, position))
    }

    override fun reply(project: ProjectRef, mr: MergeRequest, d: Discussion, body: String) {
        send("POST", "${pr(project, mr)}/comments", mapOf("content" to mapOf("raw" to body), "parent" to mapOf("id" to d.id.toLong())))
    }

    override fun resolve(project: ProjectRef, mr: MergeRequest, d: Discussion, resolved: Boolean) {
        val path = "${pr(project, mr)}/comments/${d.id}/resolve"
        if (resolved) send("POST", path, emptyMap<String, Any?>()) else send("DELETE", path, null)
    }

    override fun approve(project: ProjectRef, mr: MergeRequest) {
        send("POST", "${pr(project, mr)}/approve", emptyMap<String, Any?>())
    }

    override fun unapprove(project: ProjectRef, mr: MergeRequest) {
        send("DELETE", "${pr(project, mr)}/approve", null)
    }

    override fun approvedBy(project: ProjectRef, mr: MergeRequest): List<String> =
        get(pr(project, mr)).a("participants").map { it.obj() }.filter { it.bool("approved") }.mapNotNull { it.o("user")?.str("nickname") }

    companion object {
        internal fun user(m: Map<String, Any?>?): User? = m?.let {
            val nickname = it.str("nickname") ?: it.str("account_id") ?: ""
            User(0, nickname, it.str("display_name") ?: nickname)
        }

        /** [repoPath] — the repository the PR is in; a source in another one (a fork) is fetched by URL. */
        internal fun parsePull(m: Map<String, Any?>, refs: DiffRefs?, repoPath: String?): MergeRequest {
            val source = m.o("source")
            val sourceRepo = source?.o("repository")?.str("full_name")
            val branch = source?.o("branch")?.str("name") ?: ""
            return MergeRequest(
                iid = m.long("id") ?: 0,
                title = m.str("title") ?: "",
                description = m.o("summary")?.str("raw") ?: m.str("description") ?: "",
                state = m.str("state")?.lowercase() ?: "",
                draft = m.bool("draft"),
                author = user(m.o("author")),
                sourceBranch = branch,
                targetBranch = m.o("destination")?.o("branch")?.str("name") ?: "",
                webUrl = m.o("links")?.o("html")?.str("href") ?: "",
                sha = refs?.headSha,
                diffRefs = refs,
                updatedAt = m.str("updated_on"),
                userNotesCount = m.int("comment_count") ?: 0,
                hasConflicts = false,
                fetchRef = "refs/heads/$branch",
                fetchUrl = sourceRepo?.takeIf { repoPath != null && !it.equals(repoPath, ignoreCase = true) }?.let { "https://bitbucket.org/$it.git" },
            )
        }

        /** Comments come flat; replies point at their parent. A deleted root keeps its live replies together. */
        internal fun threads(comments: List<Map<String, Any?>>): List<Discussion> {
            val byId = comments.associateBy { it.long("id") }
            fun root(c: Map<String, Any?>): Map<String, Any?> {
                var cur = c
                while (true) cur = cur.o("parent")?.long("id")?.let { byId[it] } ?: return cur
            }
            return comments.filterNot { it.bool("deleted") }.groupBy { root(it) }.map { (r, notes) ->
                val position = position(r.o("inline"))
                val resolution = r.o("resolution")
                Discussion(
                    id = r.long("id").toString(),
                    notes = notes.sortedBy { it.str("created_on").orEmpty() }.map { c ->
                        Note(
                            id = c.long("id") ?: 0, body = c.o("content")?.str("raw") ?: "", author = user(c.o("user")),
                            createdAt = c.str("created_on"), system = false, resolvable = true, resolved = resolution != null,
                            position = position, resolvedBy = user(resolution?.o("user")),
                        )
                    },
                    webUrl = r.o("links")?.o("html")?.str("href"),
                )
            }
        }

        /** `to` — a line of the new version, `from` — of the old one; `start_*` — the first line of a range. */
        internal fun position(inline: Map<String, Any?>?): Position? {
            inline ?: return null
            val path = inline.str("path") ?: return null
            val to = inline.int("to")
            val from = inline.int("from")
            val startTo = inline.int("start_to")
            val startFrom = inline.int("start_from")
            fun point(new: Int?, old: Int?) = LinePoint("", if (new != null) "new" else "old", if (new != null) null else old, new)
            val range = if (startTo != null || startFrom != null) LineRange(point(startTo, startFrom), point(to, from)) else null
            return Position(
                null, null, null, path, path,
                oldLine = if (to == null) from else null, newLine = to,
                lineRange = range, outdated = inline.bool("outdated"),
            )
        }

        internal fun commentPayload(body: String, p: Position?): Map<String, Any?> {
            val payload = linkedMapOf<String, Any?>("content" to mapOf("raw" to body))
            if (p != null) {
                val inline = linkedMapOf<String, Any?>("path" to (p.newPath ?: p.oldPath))
                if (p.newLine != null) inline["to"] = p.newLine else inline["from"] = p.oldLine
                p.lineRange?.takeIf { p.isMultiLine }?.start?.let { s ->
                    if (s.newLine != null) inline["start_to"] = s.newLine else inline["start_from"] = s.oldLine
                }
                payload["inline"] = inline
            }
            return payload
        }

        /** A string literal of Bitbucket's query language. */
        internal fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
```

`api/Hosting.kt`:
- константа (после `GITHUB`, с заменой `;` на `,`):
  ```kotlin
      BITBUCKET_CLOUD("Bitbucket Cloud", '#', "PR", MrFilter.entries - MrFilter.ASSIGNED, canSuggest = false, commentsOutsideHunks = false,
          usernameLabel = "connection.email", fixedUrl = "https://bitbucket.org");
  ```
- `client`: `BITBUCKET_CLOUD -> BitbucketCloudClient(token, c.username)`.
- `tokenPageUrl`: `BITBUCKET_CLOUD -> "https://id.atlassian.com/manage-profile/security/api-tokens"`.
- `guess`: первой веткой `host == "bitbucket.org" -> BITBUCKET_CLOUD`.

Ключи:
```properties
connection.hint.BITBUCKET_CLOUD=API token (Bitbucket) with the scopes read:user, read:repository, read:pullrequest and write:pullrequest, plus your Atlassian e-mail above. Or a repository access token without the e-mail.
```
```properties
connection.hint.BITBUCKET_CLOUD=API token (Bitbucket) со scope read:user, read:repository, read:pullrequest и write:pullrequest и e-mail вашего аккаунта Atlassian выше. Или access token репозитория без e-mail.
```

- [ ] **Step 4: Run tests and build**

Run: `./gradlew -q test buildPlugin`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Add Bitbucket Cloud pull requests"   # + trailer lines
```

---

### Task 8: Bitbucket Data Center

**Files:**
- Create: `src/main/kotlin/me/brekhin/mrnavigator/api/BitbucketServerClient.kt`
- Modify: `api/Hosting.kt`, `git/RemoteUrl.kt` (`scm/`), оба `.properties`
- Test: `BitbucketTest.kt`

**Interfaces:**
- Consumes: `UnifiedDiff.split` (Task 6), `basicOrBearer`, `Http` (Task 2), `RemoteUrl.projectPath(remote, c)` (Task 4).
- Produces: `HostingType.BITBUCKET_SERVER`; `BitbucketServerClient(serverUrl: String, token: String, username: String?)` с companion `internal` `user(m)`, `iso(ms: Long?)`, `parsePull(m, mergeBase: String?)`, `threads(activities)`, `position(anchor)`, `commentPayload(body, p: Position?, refs: DiffRefs?)`.

- [ ] **Step 1: Write the failing tests** — в `BitbucketTest` добавить:

```kotlin
    @Test
    fun serverPull() {
        val m = obj("""{"id":12,"title":"T","description":"D","state":"OPEN","draft":false,
            "author":{"user":{"name":"alice","slug":"alice","displayName":"Alice"}},
            "fromRef":{"displayId":"feat","latestCommit":"h"},"toRef":{"displayId":"main","latestCommit":"s"},
            "links":{"self":[{"href":"https://bb.corp/projects/P/repos/r/pull-requests/12/overview"}]},
            "updatedDate":1790000000000,"properties":{"commentCount":3}}""")
        val mr = BitbucketServerClient.parsePull(m, "b")
        assertEquals(DiffRefs("b", "s", "h"), mr.diffRefs); assertEquals("h", mr.sha)
        assertEquals("refs/pull-requests/12/from", mr.fetchRef)
        assertEquals(java.time.Instant.ofEpochMilli(1790000000000).toString(), mr.updatedAt)
        assertEquals(3, mr.userNotesCount); assertEquals("open", mr.state); assertEquals("alice", mr.author?.username)
        assertEquals("https://bb.corp/projects/P/repos/r/pull-requests/12/overview", mr.webUrl)
        assertNull(BitbucketServerClient.parsePull(m, null).diffRefs)
    }

    @Test
    fun serverThreads() {
        val activities = listOf(
            """{"action":"COMMENTED","commentAction":"ADDED","comment":{"id":5,"text":"root","author":{"slug":"a","displayName":"A"},
                "createdDate":1790000000000,"threadResolved":true,
                "comments":[{"id":6,"text":"reply","author":{"slug":"b"},"createdDate":1790000001000,
                             "comments":[{"id":7,"text":"nested","createdDate":1790000002000}]}]},
                "commentAnchor":{"path":"x.go","srcPath":"x.go","line":12,"lineType":"ADDED","fileType":"TO",
                                 "multilineMarker":{"startLine":10,"startLineType":"CONTEXT"}}}""",
            """{"action":"COMMENTED","commentAction":"REPLIED","comment":{"id":6,"text":"reply"}}""",
            """{"action":"COMMENTED","commentAction":"ADDED","comment":{"id":8,"text":"gone"}}""",
            """{"action":"COMMENTED","commentAction":"DELETED","comment":{"id":8}}""",
            """{"action":"COMMENTED","commentAction":"ADDED","comment":{"id":9,"text":"general","createdDate":1790000000000}}""",
            """{"action":"APPROVED"}""",
            """{"action":"COMMENTED","commentAction":"ADDED","comment":{"id":10,"text":"old"},
                "commentAnchor":{"path":"y.go","line":3,"lineType":"REMOVED","fileType":"FROM","orphaned":true}}""",
        ).map { obj(it) }
        val threads = BitbucketServerClient.threads(activities)
        assertEquals(setOf("5", "9", "10"), threads.map { it.id }.toSet())
        val t = threads.first { it.id == "5" }
        assertEquals(listOf("root", "reply", "nested"), t.notes.map { it.body }); assertTrue(t.resolved)
        assertEquals(12, t.position!!.newLine)
        assertEquals(LinePoint("", "new", null, 10), t.position!!.lineRange!!.start)
        assertNull(threads.first { it.id == "9" }.position)
        val old = threads.first { it.id == "10" }.position!!
        assertEquals(3, old.oldLine); assertTrue(old.isOutdatedFor("anything"))
    }

    @Test
    fun serverPayloads() {
        val refs = DiffRefs("b", "s", "h")
        fun anchor(p: me.brekhin.mrnavigator.api.Position) = BitbucketServerClient.commentPayload("x", p, refs)["anchor"] as Map<*, *>
        val ctx = anchor(map.position("b", "s", "h", "f.go", "f.go", 2, onNewSide = true))
        assertEquals(2, ctx["line"]); assertEquals("CONTEXT", ctx["lineType"]); assertEquals("TO", ctx["fileType"])
        assertEquals("b", ctx["fromHash"]); assertEquals("h", ctx["toHash"]); assertEquals("EFFECTIVE", ctx["diffType"])
        val removed = anchor(map.position("b", "s", "h", "f.go", "f.go", 3, onNewSide = false))
        assertEquals("REMOVED", removed["lineType"]); assertEquals("FROM", removed["fileType"]); assertEquals(3, removed["line"])
        val range = anchor(map.position("b", "s", "h", "f.go", "f.go", end = DiffLineMap.Line(4, true), start = DiffLineMap.Line(3, false)))
        assertEquals("ADDED", range["lineType"]); assertEquals(4, range["line"])
        assertEquals(mapOf("startLine" to 3, "startLineType" to "REMOVED"), range["multilineMarker"])
        assertEquals(mapOf("text" to "x"), BitbucketServerClient.commentPayload("x", null, refs))
    }

    @Test
    fun serverRemotes() {
        val dc = Connection(HostingType.BITBUCKET_SERVER, "https://bb.corp")
        assertEquals("PROJ/repo", RemoteUrl.projectPath(RemoteUrl.parse("ssh://git@bb.corp:7999/PROJ/repo.git")!!, dc))
        assertEquals("proj/repo", RemoteUrl.projectPath(RemoteUrl.parse("https://bb.corp/scm/proj/repo.git")!!, dc))
        assertEquals("~alice/repo", RemoteUrl.projectPath(RemoteUrl.parse("https://bb.corp/scm/~alice/repo.git")!!, dc))
        assertEquals("proj/repo", RemoteUrl.projectPath(RemoteUrl.parse("https://bb.corp/bitbucket/scm/proj/repo.git")!!,
            dc.copy(url = "https://bb.corp/bitbucket")))
        assertEquals(HostingType.BITBUCKET_SERVER, HostingType.guess("bitbucket.corp.com"))
        assertEquals(HostingType.BITBUCKET_CLOUD, HostingType.guess("bitbucket.org"))
    }
```
импорты: `me.brekhin.mrnavigator.api.BitbucketServerClient`, `me.brekhin.mrnavigator.api.Connection`, `me.brekhin.mrnavigator.git.RemoteUrl`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew -q test --tests '*BitbucketTest*'`
Expected: FAIL — `Unresolved reference 'BitbucketServerClient'`.

- [ ] **Step 3: Implement** — `src/main/kotlin/me/brekhin/mrnavigator/api/BitbucketServerClient.kt`:

```kotlin
package me.brekhin.mrnavigator.api

import me.brekhin.mrnavigator.core.UnifiedDiff
import me.brekhin.mrnavigator.util.a
import me.brekhin.mrnavigator.util.bool
import me.brekhin.mrnavigator.util.int
import me.brekhin.mrnavigator.util.long
import me.brekhin.mrnavigator.util.msg
import me.brekhin.mrnavigator.util.o
import me.brekhin.mrnavigator.util.obj
import me.brekhin.mrnavigator.util.str
import java.net.URLEncoder
import java.time.Instant

/** Bitbucket Data Center (Server) REST 1.0. With [username] the HTTP access token goes with Basic auth, otherwise as Bearer. */
class BitbucketServerClient(serverUrl: String, token: String, username: String?) : HostingClient {
    private val api = serverUrl.trimEnd('/') + "/rest/api/latest"
    private val http = Http("Bitbucket") { it.setRequestProperty("Authorization", basicOrBearer(token, username)) }

    private fun get(path: String): Map<String, Any?> = http.call("GET", api + path).json().obj()
    private fun send(method: String, path: String, body: Any): Any? = http.call(method, api + path, body).json()

    /** Follows isLastPage / nextPageStart until exhausted or [limit] items. */
    private fun paged(path: String, limit: Int = 2000): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        val sep = if ('?' in path) '&' else '?'
        var start: Long? = 0
        while (start != null && out.size < limit) {
            val page = get("$path${sep}limit=100&start=$start")
            out += page.a("values").map { it.obj() }
            start = if (page.bool("isLastPage")) null else page.long("nextPageStart")
        }
        return out
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
    /** "PROJ/repo" (or "~user/repo" for a personal repository) → /projects/PROJ/repos/repo */
    private fun repo(p: ProjectRef) = "/projects/${enc(p.path.substringBefore('/'))}/repos/${enc(p.path.substringAfter('/'))}"
    private fun pr(p: ProjectRef, mr: MergeRequest) = "${repo(p)}/pull-requests/${mr.iid}"

    /** There is no "current user" endpoint: any response names the user in X-AUSERNAME, the rest comes from /users. */
    override fun currentUser(): User {
        val name = http.call("GET", "$api/application-properties").header("X-AUSERNAME")
            ?: throw ApiException(msg("api.emptyUser", "Bitbucket"))
        val found = get("/users?filter=${enc(name)}").a("values").map { it.obj() }.firstOrNull { it.str("name") == name }
        return user(found) ?: User(0, name, name)
    }

    override fun mergeRequests(project: ProjectRef, filter: MrFilter, me: User?, search: String?): List<MergeRequest> {
        val params = StringBuilder("state=${if (filter == MrFilter.MERGED) "MERGED" else "OPEN"}&order=NEWEST")
        when (filter) {
            MrFilter.REVIEW_REQUESTED -> me?.let { params.append("&role.1=REVIEWER&username.1=${enc(it.username)}") }
            MrFilter.MINE -> me?.let { params.append("&role.1=AUTHOR&username.1=${enc(it.username)}") }
            else -> Unit
        }
        if (!search.isNullOrBlank()) params.append("&filterText=${enc(search.trim())}")
        return paged("${repo(project)}/pull-requests?$params", limit = 200).map { parsePull(it, null) }
    }

    override fun mergeRequest(project: ProjectRef, iid: Long): MergeRequest {
        val m = get("${repo(project)}/pull-requests/$iid")
        // 204 without a body when the branches have no common ancestor.
        val base = get("${repo(project)}/pull-requests/$iid/merge-base").str("id")
        return parsePull(m, base)
    }

    /** Raw git diff against the merge base. */
    override fun changes(project: ProjectRef, mr: MergeRequest): List<FileChange> =
        UnifiedDiff.split(http.call("GET", api + pr(project, mr) + ".diff", accept = "text/plain").body)

    override fun discussions(project: ProjectRef, mr: MergeRequest): List<Discussion> =
        threads(paged("${pr(project, mr)}/activities")).map { it.copy(webUrl = "${mr.webUrl}?commentId=${it.id}") }

    override fun createDiscussion(project: ProjectRef, mr: MergeRequest, body: String, position: Position?) {
        send("POST", "${pr(project, mr)}/comments", commentPayload(body, position, mr.diffRefs))
    }

    override fun reply(project: ProjectRef, mr: MergeRequest, d: Discussion, body: String) {
        send("POST", "${pr(project, mr)}/comments", mapOf("text" to body, "parent" to mapOf("id" to d.id.toLong())))
    }

    /** Best effort: the REST reference doesn't describe thread resolution; Bitbucket 8+ takes threadResolved on update. */
    override fun resolve(project: ProjectRef, mr: MergeRequest, d: Discussion, resolved: Boolean) {
        val path = "${pr(project, mr)}/comments/${d.id}"
        send("PUT", path, mapOf("version" to (get(path).int("version") ?: 0), "threadResolved" to resolved))
    }

    override fun approve(project: ProjectRef, mr: MergeRequest) = setStatus(project, mr, "APPROVED")

    override fun unapprove(project: ProjectRef, mr: MergeRequest) = setStatus(project, mr, "UNAPPROVED")

    private fun setStatus(project: ProjectRef, mr: MergeRequest, status: String) {
        send("PUT", "${pr(project, mr)}/participants/${enc(currentUser().username)}", mapOf("status" to status))
    }

    override fun approvedBy(project: ProjectRef, mr: MergeRequest): List<String> =
        get(pr(project, mr)).a("reviewers").map { it.obj() }.filter { it.bool("approved") }.mapNotNull { it.o("user")?.str("slug") }

    companion object {
        internal fun user(m: Map<String, Any?>?): User? = m?.let {
            val slug = it.str("slug") ?: it.str("name") ?: ""
            User(it.long("id") ?: 0, slug, it.str("displayName") ?: slug)
        }

        /** Bitbucket DC gives epoch milliseconds; the UI expects ISO timestamps. */
        internal fun iso(ms: Long?): String? = ms?.let { Instant.ofEpochMilli(it).toString() }

        internal fun parsePull(m: Map<String, Any?>, mergeBase: String?): MergeRequest {
            val from = m.o("fromRef")
            val to = m.o("toRef")
            val head = from?.str("latestCommit")
            val start = to?.str("latestCommit")
            val id = m.long("id") ?: 0
            return MergeRequest(
                iid = id,
                title = m.str("title") ?: "",
                description = m.str("description") ?: "",
                state = m.str("state")?.lowercase() ?: "",
                draft = m.bool("draft"),
                author = user(m.o("author")?.o("user")),
                sourceBranch = from?.str("displayId") ?: "",
                targetBranch = to?.str("displayId") ?: "",
                webUrl = m.o("links")?.a("self")?.firstOrNull()?.obj()?.str("href") ?: "",
                sha = head,
                diffRefs = if (mergeBase != null && start != null && head != null) DiffRefs(mergeBase, start, head) else null,
                updatedAt = iso(m.long("updatedDate")),
                userNotesCount = m.o("properties")?.int("commentCount") ?: 0,
                hasConflicts = false,
                fetchRef = "refs/pull-requests/$id/from",
            )
        }

        /** Threads from the activity feed: each added root comment carries its replies, nested. */
        internal fun threads(activities: List<Map<String, Any?>>): List<Discussion> {
            val deleted = activities.filter { it.str("commentAction") == "DELETED" }.mapNotNull { it.o("comment")?.long("id") }.toSet()
            return activities
                .filter { it.str("action") == "COMMENTED" && it.str("commentAction") == "ADDED" }
                .mapNotNull { a ->
                    val c = a.o("comment") ?: return@mapNotNull null
                    val id = c.long("id") ?: return@mapNotNull null
                    if (id in deleted) return@mapNotNull null
                    val position = position(a.o("commentAnchor") ?: c.o("anchor"))
                    val resolved = c.bool("threadResolved") || c.str("state") == "RESOLVED"
                    val resolver = user(c.o("threadResolver") ?: c.o("resolver"))
                    val notes = ArrayList<Note>()
                    fun add(n: Map<String, Any?>) {
                        notes += Note(
                            id = n.long("id") ?: 0, body = n.str("text") ?: "", author = user(n.o("author")),
                            createdAt = iso(n.long("createdDate")), system = false, resolvable = true, resolved = resolved,
                            position = position, resolvedBy = resolver,
                        )
                        n.a("comments").forEach { add(it.obj()) }
                    }
                    add(c)
                    Discussion(id.toString(), notes.sortedBy { it.createdAt.orEmpty() })
                }
        }

        /** fileType FROM — a line of the old version, TO — of the new one; an orphaned anchor lost its code. */
        internal fun position(anchor: Map<String, Any?>?): Position? {
            anchor ?: return null
            val path = anchor.str("path") ?: return null
            val line = anchor.int("line")
            val old = anchor.str("fileType") == "FROM"
            val marker = anchor.o("multilineMarker")
            val start = marker?.int("startLine")
            val startOld = marker?.str("startLineType") == "REMOVED"
            fun point(l: Int, onOld: Boolean) = LinePoint("", if (onOld) "old" else "new", if (onOld) l else null, if (onOld) null else l)
            return Position(
                null, null, null, anchor.str("srcPath") ?: path, path,
                oldLine = if (old) line else null, newLine = if (old) null else line,
                lineRange = if (start != null && line != null) LineRange(point(start, startOld), point(line, old)) else null,
                outdated = anchor.bool("orphaned"),
            )
        }

        internal fun commentPayload(body: String, p: Position?, refs: DiffRefs?): Map<String, Any?> {
            val payload = linkedMapOf<String, Any?>("text" to body)
            p ?: return payload
            val path = p.newPath ?: p.oldPath
            val (line, lineType, fileType) = when {
                p.newLine == null -> Triple(p.oldLine, "REMOVED", "FROM")
                p.oldLine == null -> Triple(p.newLine, "ADDED", "TO")
                else -> Triple(p.newLine, "CONTEXT", "TO")
            }
            val anchor = linkedMapOf<String, Any?>(
                "diffType" to "EFFECTIVE", "path" to path, "srcPath" to (p.oldPath ?: path),
                "line" to line, "lineType" to lineType, "fileType" to fileType,
                "fromHash" to refs?.baseSha, "toHash" to refs?.headSha,
            )
            p.lineRange?.takeIf { p.isMultiLine }?.start?.let { s ->
                anchor["multilineMarker"] = mapOf(
                    "startLine" to (s.newLine ?: s.oldLine),
                    "startLineType" to when {
                        s.newLine == null -> "REMOVED"
                        s.type == "new" -> "ADDED"
                        else -> "CONTEXT"
                    },
                )
            }
            payload["anchor"] = anchor
            return payload
        }
    }
}
```

`api/Hosting.kt`:
- константа (после `BITBUCKET_CLOUD`, с заменой `;` на `,`):
  ```kotlin
      BITBUCKET_SERVER("Bitbucket Data Center", '#', "PR", MrFilter.entries - MrFilter.ASSIGNED, canSuggest = false, commentsOutsideHunks = false,
          usernameLabel = "connection.username", fixedUrl = null);
  ```
- `client`: `BITBUCKET_SERVER -> BitbucketServerClient(c.url, token, c.username)`.
- `tokenPageUrl`: `BITBUCKET_SERVER -> "$url/account"`.
- `guess` (итог):
  ```kotlin
          fun guess(host: String): HostingType = when {
              host == "bitbucket.org" -> BITBUCKET_CLOUD
              "github" in host -> GITHUB
              "bitbucket" in host -> BITBUCKET_SERVER
              else -> GITLAB
          }
  ```

`git/RemoteUrl.kt`, в `projectPath` перед `return`:
```kotlin
            // Bitbucket DC clones over https from /scm/<project>/<repo>.
            val project = if (c.type == HostingType.BITBUCKET_SERVER) path.removePrefix("scm/") else path
            return project.takeIf { it.contains('/') }
```
(и удалить прежний `return path.takeIf …`); импорт `me.brekhin.mrnavigator.api.HostingType`.

Ключи:
```properties
connection.hint.BITBUCKET_SERVER=HTTP access token with the <b>Repository write</b> permission (Manage account → HTTP access tokens)
```
```properties
connection.hint.BITBUCKET_SERVER=HTTP access token с правом <b>Repository write</b> (Manage account → HTTP access tokens)
```

- [ ] **Step 4: Run tests and build**

Run: `./gradlew -q test buildPlugin`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "Add Bitbucket Data Center pull requests"   # + trailer lines
```

---

### Task 9: Документация, имя плагина, версия 0.3.0

**Files:**
- Modify: `README.md`, `README.ru.md`, `CHANGELOG.md`, `src/main/resources/META-INF/plugin.xml`, `build.gradle.kts`

**Interfaces:** —

- [ ] **Step 1: Version and descriptor**

`build.gradle.kts`: `version = "0.3.0"`.

`plugin.xml`: `<name>MR Navigator</name>`; `<description>` целиком:
```xml
    <description><![CDATA[
        <p>Review merge and pull requests of GitLab, GitHub and Bitbucket right in the IDE, with working code navigation and without generated noise.</p>
        <ul>
            <li>Request list of the project with filters (all open, waiting for my review, assigned to me, mine, merged); repository switcher for folders with several repositories.</li>
            <li>One-click checkout into a local <code>mr/&lt;number&gt;</code> branch; uncommitted changes are stashed and restored by "Go back".</li>
            <li>Diff whose right side is the real project file — Ctrl+Click, Find Usages and the rest of the navigation work.</li>
            <li>Hides generated files by suffixes you set (e.g. <code>.pb.go</code>) and folders that contain only such files.</li>
            <li>Comments: line and multi-line threads, replies, resolve, suggestions, general comments; approve.</li>
            <li>Files viewed marks and +/− counts; discussion list that opens the diff at the commented line.</li>
        </ul>
        <p>Works with gitlab.com and self-hosted GitLab, github.com and GitHub Enterprise Server, Bitbucket Cloud and Bitbucket Data Center,
        several of them at once. English and Russian interface.</p>
    ]]></description>
```

- [ ] **Step 2: CHANGELOG** — в начало после `# Changelog`:
```markdown
## 0.3.0

- GitHub (github.com and Enterprise Server), Bitbucket Cloud and Bitbucket Data Center pull requests, alongside GitLab.
- Several connections at once: the hosting is picked by the host of the repository's git remote. Settings and the token of an existing GitLab connection carry over.
- English interface; the language is chosen in the settings (automatic by default — Russian on a Russian system).
- Merge request descriptions are rendered as GitHub-flavoured Markdown: headings, tables, task lists, quotes.
- The plugin is renamed to MR Navigator.
```

- [ ] **Step 3: README.md** (English) — изменить:
  - заголовок `# MR Navigator`;
  - первый абзац: «An IDE plugin for reviewing GitLab merge requests and GitHub and Bitbucket pull requests inside the IDE — with working code navigation in the diff and without generated noise like `*.pb.go`.»;
  - второй абзац: «The built-in integrations show the diff from revisions…» (вместо «built-in GitLab integration»);
  - удалить цитату «The user interface is currently in Russian…»;
  - Features: в «One-click checkout» — «a local `mr/<number>` branch»; добавить пункты:
    - «**GitLab, GitHub and Bitbucket**: gitlab.com and self-hosted GitLab (REST v4), github.com and GitHub Enterprise Server, Bitbucket Cloud and Bitbucket Data Center. Several connections at once — each repository uses the one whose server its git remote points to.»
    - «**English and Russian** interface.»
    - последний пункт «Self-hosted GitLab and gitlab.com…» заменить на «The IDE's proxy and certificate settings apply.»
  - UI-подписи в скобках с переводом убрать: писать английские подписи (**Go back**, **Show hidden (N)**, **Comment on Line / Selection**, **Suggest a change**, **Apply suggestion**, **Discussion**, **Checkout & review**, **Connect**, **Create a token →**).
  - Requirements — вместо одного пункта про GitLab-токен:
    ```markdown
    - A token for each hosting:
      - GitLab: personal access token with the `api` scope;
      - GitHub: classic personal access token with the `repo` scope;
      - Bitbucket Cloud: API token with the `read:user`, `read:repository`, `read:pullrequest`, `write:pullrequest` scopes and your Atlassian e-mail, or a repository/project/workspace access token;
      - Bitbucket Data Center: HTTP access token with the Repository write permission.
    ```
  - Getting started, п. 2: «The first time, a connection form appears: the hosting and its address are guessed from the project's git remote — correct them if needed (e.g. GitLab under a sub-path); **Create a token →** opens the token page of that hosting; for Bitbucket Cloud also enter your Atlassian e-mail; paste the token and click **Connect**.» Пункт 3: «Pick a request and click **Checkout & review**.»
  - Settings: «**Connections**: add (+), remove (−) and test connections; the token of each is kept in the IDE password storage.» и «**Language**: automatic (the system language), English or Russian; applies after an IDE restart.»
  - How it works — строку `api/GitLabClient.kt, api/Models.kt` заменить на:
    ```markdown
    | `api/Hosting.kt`, `api/Http.kt`, `api/Models.kt` | hosting types, the common client interface, HTTP via the IDE's `HttpRequests`, the shared model |
    | `api/GitLabClient.kt`, `api/GitHubClient.kt`, `api/BitbucketCloudClient.kt`, `api/BitbucketServerClient.kt` | GitLab REST v4; GitHub REST and GraphQL (review threads); Bitbucket Cloud REST 2.0; Bitbucket Data Center REST 1.0 |
    | `core/UnifiedDiff.kt` | splits Bitbucket's raw diff into files and hunks |
    | `util/MrBundle.kt`, `messages/MrBundle*.properties` | English and Russian strings |
    ```
    в строке `git/GitCli.kt` заменить `refs/merge-requests/<iid>/head` на «the request's head ref (`refs/merge-requests/<iid>/head`, `refs/pull/<n>/head`, `refs/pull-requests/<id>/from`, or the source branch on Bitbucket Cloud)».
  - Limitations — добавить:
    ```markdown
    - GitHub and Bitbucket accept comments only on lines of the diff hunks; the **+** is not shown elsewhere.
    - Suggestions: inserting works on GitLab and GitHub, applying only on GitLab (GitHub and Bitbucket have no API for it).
    - GitHub: an approval can't be withdrawn by its author, only dismissed — that needs write access to the repository.
    - Bitbucket Data Center: resolving threads and multi-line comments rely on undocumented API fields and may not work on older servers.
    - Bitbucket Cloud: a pull request from a fork is fetched over https from the fork; a private fork needs git credentials for it.
    - Remotes that use an SSH host alias (`Host github-work` in `~/.ssh/config`) are not matched to a connection.
    ```
- [ ] **Step 4: README.ru.md** — те же изменения по-русски: заголовок `# MR Navigator`; интро «Плагин для ревью merge request'ов GitLab и pull request'ов GitHub и Bitbucket прямо в IDE…»; убрать замечание о русском интерфейсе (если есть); тот же список токенов, «Подключения», «Язык», таблица файлов и ограничения — переводом текста из Step 3. Подписи UI — русские («Checkout и ревью», «Вернуться», «Показать скрытые (N)» и т.д., как в `MrBundle_ru.properties`).

- [ ] **Step 5: Verify**

Run: `./gradlew -q test buildPlugin`
Expected: PASS; `build/distributions/mr-navigator-0.3.0.zip` существует.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "Release 0.3.0: GitHub, Bitbucket, English UI"   # + trailer lines
```

- [ ] **Step 7: Manual check list for the user** (вывести в итоговом сообщении, не выполнять):
  1. Обновление с 0.2.0: GitLab-подключение и токен на месте, список MR открывается без формы.
  2. GitHub: список, checkout, комментарий к строке и к диапазону, ответ, resolve, approve; «+» нет вне ханков.
  3. Bitbucket Cloud (приватный репозиторий): diff открывается (редирект с авторизацией), комментарий, resolve, approve; PR из форка.
  4. Bitbucket DC: многострочный комментарий, resolve треда.
  5. Язык: English / Русский / Авто после перезапуска IDE.
