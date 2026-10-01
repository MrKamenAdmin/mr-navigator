# Ревью-воркфлоу — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** локальный merge base, кириллица в путях Bitbucket, Markdown в комментариях, правка/удаление своих комментариев, вердикты + request changes + черновики с отправкой ревью пачкой, устаревшие треды на полях diff, CI и Merge — для GitLab, GitHub, Bitbucket Cloud и Bitbucket DC.

**Architecture:** новые операции добавляются в `HostingClient` и реализуются в четырёх клиентах; разбор ответов и сборка запросов — чистые `internal` функции в companion-объектах клиентов (покрыты тестами), как в 0.3; черновики живут в `MrReviewService` и `PropertiesComponent`; UI — `ThreadPopup`, `MrDetailsPanel`, `CommentMarkers` и два новых диалога.

**Tech Stack:** Kotlin 2.4, IntelliJ Platform 2026.2, JUnit 4 + kotlin.test, `util/Json.kt`, `HttpRequests`.

**Spec:** `docs/superpowers/specs/2026-10-01-review-workflow-design.md`

## Global Constraints

- `./gradlew test buildPlugin` зелёный после каждой задачи; новых зависимостей нет.
- Код, комментарии, сообщения коммитов — по-английски, в стиле окружающего кода.
- Все строки UI — через `msg(key, …)`, каждый ключ в `MrBundle.properties` и `MrBundle_ru.properties`; апостроф только удвоенный; кавычки `“ ”` (en), `« »` (ru). Тесты `BundleTest` это проверяют.
- Коммиты заканчиваются строками:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_01EnGJB37gkEWNCmbgkHDW2c
  ```
- Ветка `review-workflow`. Сетевые и PasswordSafe-вызовы — только в фоне (`Bg.run`, пул), никогда на EDT.
- `Http.call` поддерживает GET/POST/PUT/PATCH/DELETE (`HttpRequests.patch` есть в платформе).

## Review Focus

1. **GitHub-ревью без итогового текста** с вердиктом «Комментарий»: в доке `body` обязателен для COMMENT. Ожидание: отправляется без ошибки, а если сервер вернёт 422 — пользователь видит причину (уже работает через `errors[]`), черновики не теряются. Без живого API не тестируется; в `MrReviewService.submitReview` черновики очищаются только после успешного вызова клиента — ревьюеру проверить порядок. Тест в Task 4: `GitHubClient.reviewPayload` не шлёт пустой `body`.
2. **Черновики на строке, которой больше нет** (в MR пришли новые коммиты): черновик не рисуется на полях, но виден и удаляем в диалоге ревью. Тест в Task 4: `Drafts.decode` переживает мусор и неполные записи.
3. **Пустой или удалённый `head_pipeline` / нет проверок вовсе**: строки CI нет, а не «CI: passed». Тест в Task 6: `Checks.of(emptyList())` → `NONE`.
4. **Merge base без `startSha` локально** (целевая ветка force-push'нута): понятная ошибка `error.noMergeBase`, а не NPE. Тест в Task 1: `MrSession.refs` без `localBase` бросает `ApiException` с этим текстом.
5. **Bitbucket Cloud merge дольше таймаута** (202 + опрос): по истечении 60 с — сообщение «ещё идёт, проверьте в браузере», не зависание. Тест в Task 6: `BitbucketCloudClient.taskDone` распознаёт `SUCCESS`/`PENDING`.

---

### Task 1: Escape в путях и локальный merge base

**Files:**
- Modify: `core/UnifiedDiff.kt` (`unquote`), `api/Models.kt` (`DiffRefs`), `core/DiffLineMap.kt` (`position` — `baseSha: String?`), `core/MrReviewService.kt` (`MrSession.localBase`, `refs`, `base`, `ensureCommits`), `diff/MrDiffOpener.kt` (`refs.baseSha` → `session.base`), `api/GitHubClient.kt` (без `/compare`), `api/BitbucketCloudClient.kt` (без `merge-base`), оба `.properties`
- Test: `UnifiedDiffTest.kt`, `GitHubTest.kt`, `BitbucketTest.kt`, `LogicTest.kt`

**Interfaces:**
- Produces: `data class DiffRefs(val baseSha: String?, val startSha: String, val headSha: String)`; `MrSession.localBase: String?` (var), `MrSession.refs: DiffRefs` (base заполнен или `ApiException(error.noMergeBase)`), `MrSession.base: String`; `GitHubClient.parsePull(m: Map<String, Any?>): MergeRequest` (без `mergeBase`); `BitbucketCloudClient.diffRefs(m, repoPath, prPath, get)` возвращает `DiffRefs(null, start, head)`.

- [ ] **Step 1: Failing tests**

`UnifiedDiffTest` — добавить:
```kotlin
    @Test
    fun quotedPaths() {
        // git quotes non-ASCII names as octal bytes of UTF-8: "При.go".
        val p = "\"a/\\320\\237\\321\\200\\320\\270.go\""
        val diff = "diff --git $p ${p.replace("a/", "b/")}\n--- $p\n+++ ${p.replace("a/", "b/")}\n@@ -1 +1 @@\n-a\n+b\n"
        assertEquals("При.go", UnifiedDiff.split(diff).single().newPath)
        val q = "\"a/say \\\"hi\\\"\\t.go\""
        val quoted = "diff --git $q ${q.replace("a/", "b/")}\n--- $q\n+++ ${q.replace("a/", "b/")}\n@@ -1 +1 @@\n-a\n+b\n"
        assertEquals("say \"hi\"\t.go", UnifiedDiff.split(quoted).single().newPath)
    }
```
`GitHubTest`:
- в `pull()` заменить вызовы `GitHubClient.parsePull(m, "mb")` → `GitHubClient.parsePull(m)`, ожидание refs → `DiffRefs(null, "b1", "h1")`; строку `assertNull(GitHubClient.parsePull(m, null).diffRefs)` заменить на
  `assertNull(GitHubClient.parsePull(obj("""{"number":1,"base":{"sha":"b"}}""")).diffRefs)`; вызов для «merged» — `GitHubClient.parsePull(obj(…))`.
- удалить тест `mergeBase()` целиком.

`BitbucketTest.cloudForkRefs`: ожидание → `DiffRefs(null, "0123456789abbbbb", "abc123def456aaaa")`; из fake-`get` убрать ветку `"/merge-base/" in path`; добавить `assertTrue(calls.none { "/merge-base/" in it })`.

`LogicTest` — добавить:
```kotlin
    @Test
    fun mergeBaseIsRequiredForRefs() {
        MrBundle.locale = java.util.Locale.ENGLISH
        val mr = GitHubClient.parsePull(Json.parse("""{"number":5,"base":{"sha":"b"},"head":{"sha":"h"}}""").obj())
        val s = MrSession(ProjectRef("https://github.com", "o/r"), Connection(HostingType.GITHUB, "https://github.com"),
            GitCli(java.io.File(".")), "origin", mr, emptyList(), emptyList(), Reviews(emptyList(), emptyList()))
        val e = kotlin.runCatching { s.refs }.exceptionOrNull()
        assertTrue(e is ApiException && "merge base" in e.message!!, e.toString())
        s.localBase = "mb"
        assertEquals(DiffRefs("mb", "b", "h"), s.refs)
        assertEquals("mb", s.base)
    }
```
(`Reviews` появляется в Task 4 — до неё последним аргументом передать `emptyList<String>()` и заменить в Task 4.) Импорты: `me.brekhin.mrnavigator.api.*`, `me.brekhin.mrnavigator.core.MrSession`, `me.brekhin.mrnavigator.git.GitCli`, `me.brekhin.mrnavigator.util.MrBundle`.

- [ ] **Step 2: Run — FAIL** (`./gradlew test`): unresolved `parsePull(m)`, `localBase`, `base`; `quotedPaths` падает на сравнении.

- [ ] **Step 3: Implement**

`UnifiedDiff.unquote`:
```kotlin
    /** A path git quoted for unusual characters: C escapes, non-ASCII as octal bytes of UTF-8 ("\320\237"). */
    private fun unquote(raw: String): String {
        val s = raw.trim()
        if (s.length < 2 || !s.startsWith('"') || !s.endsWith('"')) return s
        val out = java.io.ByteArrayOutputStream()
        var i = 1
        val end = s.length - 1
        while (i < end) {
            val c = s[i]
            if (c != '\\' || i + 1 >= end) {
                out.writeBytes(c.toString().toByteArray(Charsets.UTF_8))
                i++
                continue
            }
            val n = s[i + 1]
            if (n in '0'..'7') {
                var j = i + 1
                while (j < minOf(i + 4, end) && s[j] in '0'..'7') j++
                out.write(s.substring(i + 1, j).toInt(8))
                i = j
            } else {
                out.write(when (n) { 'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; 'a' -> '\u0007'; 'b' -> '\b'; 'f' -> '\u000c'; 'v' -> '\u000b'; else -> n }.code)
                i += 2
            }
        }
        return out.toString(Charsets.UTF_8)
    }
```
(заменяет прежний `unquote`; `path()` и `gitPaths()` уже вызывают его.)

`Models.kt`:
```kotlin
/** [baseSha] — the merge base; null when the server doesn't give it (GitHub, Bitbucket Cloud) and git computes it. */
data class DiffRefs(val baseSha: String?, val startSha: String, val headSha: String) {
```
(`from()` без изменений.)

`DiffLineMap.position` — оба перегруженных метода: `baseSha: String?`.

`MrReviewService.kt`, в `MrSession` вместо `val refs`:
```kotlin
    /** Merge base computed by git when the server doesn't give it (GitHub, Bitbucket Cloud); set by ensureCommits. */
    @Volatile var localBase: String? = null

    /** Diff refs with the merge base known — for GitHub and Bitbucket Cloud only after [MrReviewService.ensureCommits]. */
    val refs: DiffRefs
        get() {
            val r = mr.diffRefs ?: throw ApiException(msg("error.noDiffRefs", ref, type.title))
            return if (r.baseSha != null) r else r.copy(baseSha = localBase ?: throw ApiException(msg("error.noMergeBase", ref)))
        }

    val base: String get() = refs.baseSha!!
```
`ensureCommits`:
```kotlin
    /** Makes sure base and head commits exist locally (needed to show file contents); computes a missing merge base. */
    fun ensureCommits(s: MrSession) {
        val r = s.mr.diffRefs ?: throw ApiException(msg("error.noDiffRefs", s.ref, s.type.title))
        val remote = s.remoteName
        if (!s.git.hasCommit(r.headSha)) {
            s.git.run("fetch", s.mr.fetchUrl ?: remote, "+${s.mr.fetchRef}:refs/mr-review/${s.mr.iid}", timeoutMs = 300_000)
        }
        if (!s.git.hasCommit(r.baseSha ?: s.localBase ?: r.startSha)) {
            s.git.run("fetch", remote, s.mr.targetBranch, timeoutMs = 300_000)
        }
        if (r.baseSha == null && s.localBase == null && s.git.hasCommit(r.startSha) && s.git.hasCommit(r.headSha)) {
            s.localBase = s.git.run("merge-base", r.startSha, r.headSha, allowFail = true).trim().ifEmpty { null }
        }
        if (!s.git.hasCommit(r.headSha) || !s.git.hasCommit(s.base)) {
            throw GitException(msg("error.fetchFailed", s.type.term, remote))
        }
    }
```
`MrDiffOpener.Producer.process`: `git.showFile(refs.baseSha, …)` → `git.showFile(session.base, …)`; в `leftTitle` `refs.baseSha.take(8)` → `session.base.take(8)`.

`GitHubClient`:
```kotlin
    override fun mergeRequest(project: ProjectRef, iid: Long): MergeRequest = parsePull(get("${repo(project)}/pulls/$iid").obj())
```
удалить `mergeBase()`; `parsePull(m: Map<String, Any?>)` — без параметра, `diffRefs = if (baseSha != null && headSha != null) DiffRefs(null, baseSha, headSha) else null`; KDoc parsePull: «The merge base is computed by git after fetching (see MrReviewService.ensureCommits).» Вызов в `mergeRequests` — `parsePull(it)`.

`BitbucketCloudClient.diffRefs`: удалить строку с `merge-base`, вернуть `DiffRefs(null, start, head)`; KDoc: убрать упоминание merge base, добавить «the merge base is computed by git after fetching».

Ключи:
```properties
error.noMergeBase={0}: the merge base of the branches is unknown — open the diff again after the branches are fetched
```
```properties
error.noMergeBase={0}: неизвестен общий предок веток — откройте diff ещё раз, когда ветки скачаются
```

- [ ] **Step 4: Run — PASS** (`./gradlew test buildPlugin`).
- [ ] **Step 5: Commit** — `Compute the merge base with git for GitHub and Bitbucket Cloud; decode quoted diff paths`.

---

### Task 2: Markdown в комментариях

**Files:**
- Modify: `util/Markdown.kt` (подпись suggestion, удалить `toHtml`/`inline`), `ui/ThreadPopup.kt` (`htmlBody`, сигнатуры `notesView`/`noteView`)
- Test: `LogicTest.kt` (`markdown()` переписать)

**Interfaces:**
- Consumes: `MergeRequest.projectWebUrl`.
- Produces: `Markdown.gfmToHtml` ставит `<div><i>Suggestion:</i></div>` перед блоком ```` ```suggestion… ````; `Markdown.toHtml` удалён; `ThreadPopup.notesView(d: Discussion, s: MrSession, onApply)`, `noteView(n: Note, separator: Boolean, s: MrSession, onApply)`.

- [ ] **Step 1: Failing test** — заменить тело `LogicTest.markdown()`:
```kotlin
    @Test
    fun markdown() {
        fun html(md: String) = Markdown.gfmToHtml(md, "https://x")
        val text = html("a `<b>` **c**")
        assertTrue("<code>&lt;b&gt;</code>" in text, text); assertTrue("<strong>c</strong>" in text, text)
        assertTrue("<div><i>Suggestion:</i></div><pre><code>foo()" in html("```suggestion:-0+0\nfoo()\n```"))
        // GitLab uses a longer fence when the code itself has ```
        assertTrue("<div><i>Suggestion:</i></div><pre><code>// ```\nx" in html("````suggestion:-1+0\n// ```\nx\n````"))
        assertTrue("<div><i>Suggestion:</i></div><pre><code>y" in html("```suggestion\ny\n```")) // GitHub
    }
```
- [ ] **Step 2: Run — FAIL** (нет подписи).
- [ ] **Step 3: Implement**

`Markdown.kt`: добавить
```kotlin
    // A ```suggestion block (GitLab "suggestion:-N+0", GitHub plain) is labelled, as on the web.
    private val SUGGESTION = Regex("""<pre><code class="language-suggestion[^"]*">""")
```
и в цепочку `gfmToHtml` после `TASK`: `.replace(SUGGESTION, "<div><i>Suggestion:</i></div><pre><code>")`. Удалить `toHtml` и `inline`. KDoc объекта: «Markdown → HTML with the parser bundled in the IDE: [gfmToHtml] for descriptions and comments.»

`ThreadPopup.kt`:
```kotlin
    private fun htmlBody(markdown: String, baseUrl: String): JComponent = JEditorPane().apply {
        editorKit = HTMLEditorKitBuilder.simple()
        text = "<html>${Markdown.gfmToHtml(markdown, baseUrl)}</html>"
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.empty()
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        font = UIUtil.getLabelFont()
        addHyperlinkListener { if (it.eventType == HyperlinkEvent.EventType.ACTIVATED) it.url?.let { url -> BrowserUtil.browse(url) } }
    }
```
`notesView(d, session.type, applySuggestions)` → `notesView(d, session, applySuggestions)`; внутри `noteView(n, separator = i > 0, s, onApply)`; в `noteView` — `htmlBody(n.body, s.mr.projectWebUrl)` и `suggestionState(n, s.type, onApply)`. Импорт `com.intellij.util.ui.HTMLEditorKitBuilder`, `me.brekhin.mrnavigator.core.MrSession` (уже есть).

- [ ] **Step 4: Run — PASS.** **Step 5: Commit** — `Render comments as GitHub-flavoured Markdown`.

---

### Task 3: Правка и удаление своих комментариев

**Files:**
- Modify: `api/Http.kt` (PATCH), `api/Hosting.kt` (интерфейс), четыре клиента, `core/MrReviewService.kt`, `ui/ThreadPopup.kt`, оба `.properties`
- Test: `GitHubTest.kt`

**Interfaces:**
- Produces: `HostingClient.editNote(project: ProjectRef, mr: MergeRequest, d: Discussion, note: Note, body: String)`, `deleteNote(project, mr, d, note)`; `MrReviewService.editNote(s, d, note, body)`, `deleteNote(s, d, note)`; `GitHubClient.commentsPath(repo: String, d: Discussion): String` (internal).

- [ ] **Step 1: Failing test** (`GitHubTest`):
```kotlin
    @Test
    fun commentPaths() {
        val review = GitHubClient.parseThread(obj("""{"id":"T","comments":{"nodes":[{"databaseId":7}]}}"""), "h")
        val general = GitHubClient.parseIssueComment(obj("""{"id":5}"""))
        assertEquals("/repos/o/r/pulls/comments", GitHubClient.commentsPath("/repos/o/r", review))
        assertEquals("/repos/o/r/issues/comments", GitHubClient.commentsPath("/repos/o/r", general))
    }
```
- [ ] **Step 2: Run — FAIL.**
- [ ] **Step 3: Implement**

`Http.call`: `"PATCH" -> HttpRequests.patch(url, "application/json")`.

`HostingClient`:
```kotlin
    fun editNote(project: ProjectRef, mr: MergeRequest, d: Discussion, note: Note, body: String)
    fun deleteNote(project: ProjectRef, mr: MergeRequest, d: Discussion, note: Note)
```
GitLab:
```kotlin
    override fun editNote(project: ProjectRef, mr: MergeRequest, d: Discussion, note: Note, body: String) {
        json("PUT", "${mrPath(project, mr)}/discussions/${d.id}/notes/${note.id}", mapOf("body" to body))
    }

    override fun deleteNote(project: ProjectRef, mr: MergeRequest, d: Discussion, note: Note) {
        json("DELETE", "${mrPath(project, mr)}/discussions/${d.id}/notes/${note.id}")
    }
```
GitHub:
```kotlin
    override fun editNote(project: ProjectRef, mr: MergeRequest, d: Discussion, note: Note, body: String) {
        send("PATCH", "${commentsPath(repo(project), d)}/${note.id}", mapOf("body" to body))
    }

    override fun deleteNote(project: ProjectRef, mr: MergeRequest, d: Discussion, note: Note) {
        http.call("DELETE", api + "${commentsPath(repo(project), d)}/${note.id}")
    }
```
и в companion:
```kotlin
        /** General comments are issue comments on GitHub, line threads — review comments. */
        internal fun commentsPath(repo: String, d: Discussion) = if (d.id.startsWith(ISSUE)) "$repo/issues/comments" else "$repo/pulls/comments"
```
Bitbucket Cloud:
```kotlin
    override fun editNote(project: ProjectRef, mr: MergeRequest, d: Discussion, note: Note, body: String) {
        send("PUT", "${pr(project, mr)}/comments/${note.id}", mapOf("content" to mapOf("raw" to body)))
    }

    override fun deleteNote(project: ProjectRef, mr: MergeRequest, d: Discussion, note: Note) {
        send("DELETE", "${pr(project, mr)}/comments/${note.id}", null)
    }
```
Bitbucket DC (обе операции требуют актуальную `version`):
```kotlin
    override fun editNote(project: ProjectRef, mr: MergeRequest, d: Discussion, note: Note, body: String) {
        val path = "${pr(project, mr)}/comments/${note.id}"
        send("PUT", path, mapOf("text" to body, "version" to (get(path).int("version") ?: 0)))
    }

    /** DC refuses (409) to delete a comment that has replies; its message is shown to the user. */
    override fun deleteNote(project: ProjectRef, mr: MergeRequest, d: Discussion, note: Note) {
        val path = "${pr(project, mr)}/comments/${note.id}"
        http.call("DELETE", api + path + "?version=" + (get(path).int("version") ?: 0))
    }
```
`MrReviewService`:
```kotlin
    fun editNote(s: MrSession, d: Discussion, note: Note, body: String) {
        client(s.connection).editNote(s.project, s.mr, d, note, body)
        refreshDiscussions(s)
    }

    fun deleteNote(s: MrSession, d: Discussion, note: Note) {
        client(s.connection).deleteNote(s.project, s.mr, d, note)
        refreshDiscussions(s)
    }
```
`ThreadPopup`:
- в `showThread` после `applySuggestions`:
  ```kotlin
          val me = service.currentUserCached(session.connection)?.username
          val own = Own(
              edit = { note, text ->
                  busy(true)
                  Bg.run(project, msg("popup.editTask"), work = { service.editNote(session, discussion, note, text) },
                      onError = { busy(false); Notify.error(project, msg("popup.editFailed"), it) }) { popup.cancel() }
              },
              delete = { note ->
                  if (Messages.showYesNoDialog(project, msg("popup.deleteConfirm"), msg("popup.delete"), null) == Messages.YES) {
                      busy(true)
                      Bg.run(project, msg("popup.deleteTask"), work = { service.deleteNote(session, discussion, note) },
                          onError = { busy(false); Notify.error(project, msg("popup.deleteFailed"), it) }) { popup.cancel() }
                  }
              },
          )
  ```
  и `notesView(discussion, session, applySuggestions)` → `notesView(discussion, session, me, own, applySuggestions)`.
- класс в объекте:
  ```kotlin
      /** What the author can do with their own notes. */
      private class Own(val edit: (Note, String) -> Unit, val delete: (Note) -> Unit)
  ```
- `notesView(d, s, me: String?, own: Own, onApply)` передаёт `me`, `own` в `noteView`.
- `noteView` — переписать:
  ```kotlin
      private fun noteView(n: Note, separator: Boolean, s: MrSession, me: String?, own: Own, onApply: (List<Long>, JButton) -> Unit): JComponent {
          val panel = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
              isOpaque = false
              border = if (separator) {
                  JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0), JBUI.Borders.emptyTop(8))
              } else {
                  JBUI.Borders.empty()
              }
          }
          val body = htmlBody(n.body, s.mr.projectWebUrl)
          fun showInCenter(c: JComponent) {
              (panel.layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER)?.let { panel.remove(it) }
              panel.add(c, BorderLayout.CENTER)
              panel.revalidate(); panel.repaint()
          }
          val header = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
              isOpaque = false
              add(JBLabel(n.author?.name ?: "?").apply { font = JBUI.Fonts.label().asBold() })
              val meta = listOfNotNull(n.author?.username?.let { "@$it" }, TimeAgo.format(n.createdAt).ifEmpty { null })
              add(JBLabel("  " + meta.joinToString(" · ")).apply { foreground = UIUtil.getContextHelpForeground() })
              if (me != null && n.author?.username == me) {
                  add(JBLabel("   "))
                  add(ActionLink(msg("popup.edit")) {
                      val area = inputArea("").apply { text = n.body }
                      val save = primary(msg("popup.save")).apply {
                          addActionListener { area.text.trim().takeIf { it.isNotEmpty() }?.let { own.edit(n, it) } }
                      }
                      val cancel = JButton(msg("popup.cancel")).apply { addActionListener { showInCenter(body) } }
                      showInCenter(JPanel(BorderLayout(0, JBUI.scale(4))).apply {
                          isOpaque = false
                          add(JBScrollPane(area).apply { preferredSize = Dimension(WIDTH, JBUI.scale(90)) }, BorderLayout.CENTER)
                          add(row(listOf(save, cancel)), BorderLayout.SOUTH)
                      })
                      area.requestFocusInWindow()
                  })
                  add(JBLabel(" · "))
                  add(ActionLink(msg("popup.delete")) { own.delete(n) })
              }
          }
          panel.add(header, BorderLayout.NORTH)
          panel.add(body, BorderLayout.CENTER)
          suggestionState(n, s.type, onApply)?.let {
              panel.add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false; add(it) }, BorderLayout.SOUTH)
          }
          return panel
      }
  ```
  Импорты: `com.intellij.ui.components.ActionLink`, `com.intellij.openapi.ui.Messages`.

Ключи (en / ru):
```properties
popup.edit=Edit
popup.delete=Delete
popup.save=Save
popup.cancel=Cancel
popup.editTask=Saving the comment
popup.editFailed=Comment not saved
popup.deleteTask=Deleting the comment
popup.deleteFailed=Comment not deleted
popup.deleteConfirm=Delete this comment?
```
```properties
popup.edit=Изменить
popup.delete=Удалить
popup.save=Сохранить
popup.cancel=Отмена
popup.editTask=Сохранение комментария
popup.editFailed=Комментарий не сохранён
popup.deleteTask=Удаление комментария
popup.deleteFailed=Комментарий не удалён
popup.deleteConfirm=Удалить этот комментарий?
```
- [ ] **Step 4: Run — PASS.** **Step 5: Commit** — `Edit and delete your own comments`.

---
### Task 4: Вердикты, request changes, черновики и отправка ревью

**Files:**
- Create: `core/Drafts.kt`, `ui/ReviewDialog.kt`, `src/test/kotlin/me/brekhin/mrnavigator/GitLabTest.kt`
- Modify: `api/Models.kt` (`Verdict`, `Reviews`, `Draft`), `api/Hosting.kt` (интерфейс), четыре клиента, `core/MrReviewService.kt`, `ui/MrDetailsPanel.kt`, `ui/ThreadPopup.kt`, `diff/CommentMarkers.kt`, оба `.properties`
- Test: `GitLabTest.kt`, `GitHubTest.kt`, `BitbucketTest.kt`, `LogicTest.kt`

**Interfaces:**
- Produces:
  - `enum class Verdict { COMMENT, APPROVE, REQUEST_CHANGES }` (имена совпадают с `event` GitHub);
    `data class Reviews(val approved: List<String>, val changesRequested: List<String>)` c `Reviews.NONE`;
    `data class Draft(val id: String, val body: String, val position: Position)`.
  - `HostingClient`: `approvedBy` удалён; `reviews(project, mr): Reviews`,
    `submitReview(project, mr, drafts: List<Draft>, verdict: Verdict, summary: String)`, `withdrawChanges(project, mr)`.
  - `object Drafts { fun encode(drafts: List<Draft>): String; fun decode(text: String?): List<Draft> }`.
  - `MrSession.reviews: Reviews` (var, вместо `approvedBy`), `MrSession.onFile(p: Position, change: FileChange): Boolean`.
  - `MrReviewService.drafts(s)`, `saveDraft(s, draft)`, `removeDraft(s, id)`, `submitReview(s, verdict, summary)`,
    `withdrawChanges(s)`, `refreshReviews(s)` (вместо `refreshApprovals`).
  - `ThreadPopup.showDraft(project, session, draft, at, onClosed)`; `ReviewDialog(project, session, drafts, verdict)` с `verdict`, `summary`.
  - Чистые функции: `GitLabClient.draftNote(d)`, `GitLabClient.withChangesRequested(reviewers)`, `GitHubClient.reviewStates(reviews)`,
    `GitHubClient.reviewPayload(drafts, verdict, summary, headSha)`, `BitbucketCloudClient.participants(ps)`,
    `BitbucketServerClient.reviewers(rs)`, `BitbucketServerClient.reviewPayload(verdict, summary)`.

- [ ] **Step 1: Failing tests**

`GitLabTest.kt` (новый):
```kotlin
package me.brekhin.mrnavigator

import me.brekhin.mrnavigator.api.Draft
import me.brekhin.mrnavigator.api.GitLabClient
import me.brekhin.mrnavigator.core.DiffLineMap
import me.brekhin.mrnavigator.util.Json
import me.brekhin.mrnavigator.util.obj
import org.junit.Test
import kotlin.test.assertEquals

class GitLabTest {
    private fun obj(s: String) = Json.parse(s).obj()

    @Test
    fun reviewPieces() {
        val p = DiffLineMap("@@ -1 +1 @@\n-a\n+b").position("b", "s", "h", "f.go", "f.go", 1, onNewSide = true)
        val note = GitLabClient.draftNote(Draft("1", "x", p))
        assertEquals("x", note["note"]); assertEquals(p.toJson(), note["position"])
        val reviewers = listOf("""{"user":{"username":"a"},"state":"requested_changes"}""", """{"user":{"username":"b"},"state":"reviewed"}""")
            .map { obj(it) }
        assertEquals(listOf("a"), GitLabClient.withChangesRequested(reviewers))
    }
}
```
`GitHubTest.approvalsAndPaging` — `GitHubClient.approvers(reviews)` → `GitHubClient.reviewStates(reviews)` с ожиданием
`Reviews(approved = listOf("b"), changesRequested = listOf("a"))`; добавить:
```kotlin
    @Test
    fun reviewPayloads() {
        val map = DiffLineMap("@@ -2,3 +2,4 @@\n two\n-three\n+THREE\n+three-and-half\n four")
        val draft = Draft("1", "fix", map.position("b", "s", "h", "f.go", "f.go", 3, onNewSide = true))
        assertEquals(
            mapOf("commit_id" to "h", "event" to "COMMENT", "comments" to listOf(mapOf("body" to "fix", "path" to "f.go", "line" to 3, "side" to "RIGHT"))),
            GitHubClient.reviewPayload(listOf(draft), Verdict.COMMENT, " ", "h"),
        )
        assertEquals(mapOf("commit_id" to "h", "event" to "REQUEST_CHANGES", "body" to "why"), GitHubClient.reviewPayload(emptyList(), Verdict.REQUEST_CHANGES, "why", "h"))
    }
```
`BitbucketTest` — добавить:
```kotlin
    @Test
    fun reviewStates() {
        val cloud = listOf("""{"user":{"nickname":"a"},"approved":true,"state":"approved"}""", """{"user":{"nickname":"b"},"approved":false,"state":"changes_requested"}""",
            """{"user":{"nickname":"c"},"approved":false,"state":null}""").map { obj(it) }
        assertEquals(Reviews(listOf("a"), listOf("b")), BitbucketCloudClient.participants(cloud))
        val server = listOf("""{"user":{"slug":"a"},"approved":true,"status":"APPROVED"}""", """{"user":{"slug":"b"},"approved":false,"status":"NEEDS_WORK"}""").map { obj(it) }
        assertEquals(Reviews(listOf("a"), listOf("b")), BitbucketServerClient.reviewers(server))
        assertEquals(mapOf("commentText" to "ok", "participantStatus" to "APPROVED"), BitbucketServerClient.reviewPayload(Verdict.APPROVE, "ok"))
        assertEquals(mapOf("participantStatus" to "NEEDS_WORK"), BitbucketServerClient.reviewPayload(Verdict.REQUEST_CHANGES, ""))
        assertEquals(emptyMap(), BitbucketServerClient.reviewPayload(Verdict.COMMENT, " "))
    }
```
`LogicTest` — добавить:
```kotlin
    @Test
    fun draftsRoundTrip() {
        val m = DiffLineMap("@@ -2,3 +2,4 @@\n two\n-three\n+THREE\n+three-and-half\n four")
        val drafts = listOf(
            Draft("1", "fix \"this\"\nplease", m.position("b", "s", "h", "f.go", "f.go", 3, onNewSide = true)),
            Draft("2", "range", m.position("b", "s", "h", "f.go", "f.go", end = DiffLineMap.Line(4, true), start = DiffLineMap.Line(3, false))),
        )
        assertEquals(drafts, Drafts.decode(Drafts.encode(drafts)))
        assertEquals(emptyList(), Drafts.decode("not json"))
        assertEquals(emptyList(), Drafts.decode(null))
        // Entries without an id or a position are dropped, the rest survive.
        assertEquals(listOf("ok"), Drafts.decode("""[{"id":"x"},{"body":"no id","position":{}},{"id":"ok","position":{"new_line":1,"new_path":"a"}}]""").map { it.id })
    }
```
В `LogicTest.mergeBaseIsRequiredForRefs` последний аргумент `MrSession(…)` → `Reviews.NONE`. Импорты: `Draft`, `Reviews`, `Verdict`, `me.brekhin.mrnavigator.core.Drafts`, `BitbucketServerClient`.

- [ ] **Step 2: Run — FAIL** (unresolved `Draft`, `Reviews`, `Verdict`, `Drafts`, …).

- [ ] **Step 3: Models and interface**

`Models.kt` — в конец:
```kotlin
/** The reviewer's decision sent with a review; the names are GitHub's review events. */
enum class Verdict { COMMENT, APPROVE, REQUEST_CHANGES }

/** Usernames by their current verdict on the request. */
data class Reviews(val approved: List<String>, val changesRequested: List<String>) {
    companion object {
        val NONE = Reviews(emptyList(), emptyList())
    }
}

/** A line comment kept in the IDE until the review is submitted. */
data class Draft(val id: String, val body: String, val position: Position)
```
`Hosting.kt`, `HostingClient`: вместо `approvedBy`:
```kotlin
    /** Who approved and who requested changes. */
    fun reviews(project: ProjectRef, mr: MergeRequest): Reviews
    /** Publishes [drafts] and [summary] with [verdict] — with one notification where the server can. */
    fun submitReview(project: ProjectRef, mr: MergeRequest, drafts: List<Draft>, verdict: Verdict, summary: String)
    fun withdrawChanges(project: ProjectRef, mr: MergeRequest)
```

`core/Drafts.kt`:
```kotlin
package me.brekhin.mrnavigator.core

import me.brekhin.mrnavigator.api.Draft
import me.brekhin.mrnavigator.api.Position
import me.brekhin.mrnavigator.util.Json
import me.brekhin.mrnavigator.util.arr
import me.brekhin.mrnavigator.util.o
import me.brekhin.mrnavigator.util.obj
import me.brekhin.mrnavigator.util.str

/** Drafts of a review as stored in the IDE between restarts: a JSON list of {id, body, position}. */
object Drafts {
    fun encode(drafts: List<Draft>): String =
        Json.write(drafts.map { mapOf("id" to it.id, "body" to it.body, "position" to it.position.toJson()) })

    /** Unreadable entries are dropped: a draft is not worth an error. */
    fun decode(text: String?): List<Draft> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            Json.parse(text).arr().mapNotNull { e ->
                val m = e.obj()
                val id = m.str("id") ?: return@mapNotNull null
                val position = Position.from(m.o("position")) ?: return@mapNotNull null
                Draft(id, m.str("body") ?: "", position)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
```

- [ ] **Step 4: Clients**

GitLab — поля и методы:
```kotlin
    private val graphqlUrl = serverUrl.trimEnd('/') + "/api/graphql"
    private val gql = Http("GitLab") { it.setRequestProperty("Authorization", "Bearer $token") }
```
(`token` — параметр конструктора, уже есть.) Старый `approvedBy` сделать `private fun approvedBy(...)` (тело то же) и добавить:
```kotlin
    override fun reviews(project: ProjectRef, mr: MergeRequest): Reviews = Reviews(approvedBy(project, mr), requestedChanges(project, mr))

    private fun requestedChanges(project: ProjectRef, mr: MergeRequest): List<String> = try {
        withChangesRequested(paged("${mrPath(project, mr)}/reviewers"))
    } catch (e: ApiException) {
        emptyList()
    }

    /** Draft notes published at once — one notification, like "Submit review" on the web. */
    override fun submitReview(project: ProjectRef, mr: MergeRequest, drafts: List<Draft>, verdict: Verdict, summary: String) {
        val path = "${mrPath(project, mr)}/draft_notes"
        drafts.forEach { json("POST", path, draftNote(it)) }
        if (summary.isNotBlank()) json("POST", path, mapOf("note" to summary))
        if (drafts.isNotEmpty() || summary.isNotBlank()) json("POST", "$path/bulk_publish", emptyMap<String, Any?>())
        when (verdict) {
            Verdict.APPROVE -> approve(project, mr)
            Verdict.REQUEST_CHANGES -> mutate("mergeRequestRequestChanges", project, mr)
            Verdict.COMMENT -> Unit
        }
    }

    override fun withdrawChanges(project: ProjectRef, mr: MergeRequest) = mutate("mergeRequestDestroyRequestedChanges", project, mr)

    /** Request changes is in REST only since GitLab 19.2, in GraphQL long before. */
    private fun mutate(name: String, project: ProjectRef, mr: MergeRequest) {
        val query = "mutation(\$p: ID!, \$iid: String!) { $name(input: {projectPath: \$p, iid: \$iid}) { errors } }"
        val variables = mapOf("p" to project.path, "iid" to mr.iid.toString())
        val r = gql.call("POST", graphqlUrl, mapOf("query" to query, "variables" to variables)).json().obj()
        val errors = r.a("errors").mapNotNull { it.obj().str("message") } +
            r.o("data")?.o(name)?.a("errors").orEmpty().mapNotNull { it as? String }
        if (errors.isNotEmpty()) throw ApiException("GitLab: " + errors.joinToString("; "))
    }

    companion object {
        internal fun draftNote(d: Draft): Map<String, Any?> = mapOf("note" to d.body, "position" to d.position.toJson())

        internal fun withChangesRequested(reviewers: List<Map<String, Any?>>): List<String> =
            reviewers.filter { it.str("state") == "requested_changes" }.mapNotNull { it.o("user")?.str("username") }
    }
```
импорты `me.brekhin.mrnavigator.util.a`, `me.brekhin.mrnavigator.util.o`.

GitHub:
```kotlin
    override fun reviews(project: ProjectRef, mr: MergeRequest): Reviews = reviewStates(paged("${pull(project, mr)}/reviews"))

    /** One review with all comments: one notification. */
    override fun submitReview(project: ProjectRef, mr: MergeRequest, drafts: List<Draft>, verdict: Verdict, summary: String) {
        send("POST", "${pull(project, mr)}/reviews", reviewPayload(drafts, verdict, summary, mr.diffRefs?.headSha ?: mr.sha))
    }

    override fun unapprove(project: ProjectRef, mr: MergeRequest) = dismiss(project, mr, "APPROVED")

    override fun withdrawChanges(project: ProjectRef, mr: MergeRequest) = dismiss(project, mr, "CHANGES_REQUESTED")

    /** A reviewer can't withdraw their review on GitHub, only dismiss it — that needs write access to the repository. */
    private fun dismiss(project: ProjectRef, mr: MergeRequest, state: String) {
        val me = currentUser().username
        val review = paged("${pull(project, mr)}/reviews").lastOrNull { it.o("user")?.str("login") == me && it.str("state") == state } ?: return
        send("PUT", "${pull(project, mr)}/reviews/${review.long("id")}/dismissals", mapOf("message" to "Review withdrawn", "event" to "DISMISS"))
    }
```
(старый `unapprove` и `approvedBy` удалить.) В companion `approvers` заменить на:
```kotlin
        /** The latest decisive review of each user (comment-only reviews don't change the state). */
        internal fun reviewStates(reviews: List<Map<String, Any?>>): Reviews {
            val last = LinkedHashMap<String, String>()
            for (r in reviews) {
                val login = r.o("user")?.str("login") ?: continue
                val state = r.str("state") ?: continue
                if (state != "COMMENTED" && state != "PENDING") last[login] = state
            }
            return Reviews(last.filterValues { it == "APPROVED" }.keys.toList(), last.filterValues { it == "CHANGES_REQUESTED" }.keys.toList())
        }

        internal fun reviewPayload(drafts: List<Draft>, verdict: Verdict, summary: String, headSha: String?): Map<String, Any?> {
            val payload = linkedMapOf<String, Any?>("commit_id" to headSha, "event" to verdict.name)
            if (summary.isNotBlank()) payload["body"] = summary
            if (drafts.isNotEmpty()) payload["comments"] = drafts.map { commentPayload(it.body, it.position, null) - "commit_id" }
            return payload
        }
```

Bitbucket Cloud:
```kotlin
    override fun reviews(project: ProjectRef, mr: MergeRequest): Reviews =
        participants(get(pr(project, mr)).a("participants").map { it.obj() })

    /** Bitbucket Cloud has no API for a batched review: the comments go one by one. */
    override fun submitReview(project: ProjectRef, mr: MergeRequest, drafts: List<Draft>, verdict: Verdict, summary: String) {
        drafts.forEach { createDiscussion(project, mr, it.body, it.position) }
        if (summary.isNotBlank()) createDiscussion(project, mr, summary, null)
        when (verdict) {
            Verdict.APPROVE -> approve(project, mr)
            Verdict.REQUEST_CHANGES -> send("POST", "${pr(project, mr)}/request-changes", emptyMap<String, Any?>())
            Verdict.COMMENT -> Unit
        }
    }

    override fun withdrawChanges(project: ProjectRef, mr: MergeRequest) {
        send("DELETE", "${pr(project, mr)}/request-changes", null)
    }
```
(удалить `approvedBy`.) В companion:
```kotlin
        internal fun participants(ps: List<Map<String, Any?>>): Reviews {
            fun who(p: Map<String, Any?>) = p.o("user")?.str("nickname")
            return Reviews(ps.filter { it.bool("approved") }.mapNotNull(::who), ps.filter { it.str("state") == "changes_requested" }.mapNotNull(::who))
        }
```

Bitbucket DC:
```kotlin
    override fun reviews(project: ProjectRef, mr: MergeRequest): Reviews = reviewers(get(pr(project, mr)).a("reviewers").map { it.obj() })

    /** Pending comments published by one "review" call with the status: one notification. */
    override fun submitReview(project: ProjectRef, mr: MergeRequest, drafts: List<Draft>, verdict: Verdict, summary: String) {
        val path = pr(project, mr)
        drafts.forEach { send("POST", "$path/comments", commentPayload(it.body, it.position, mr.diffRefs) + ("state" to "PENDING")) }
        if (drafts.isEmpty() && summary.isBlank()) {
            when (verdict) {
                Verdict.APPROVE -> setStatus(project, mr, "APPROVED")
                Verdict.REQUEST_CHANGES -> setStatus(project, mr, "NEEDS_WORK")
                Verdict.COMMENT -> Unit
            }
            return
        }
        send("PUT", "$path/review?version=${get(path).int("version") ?: 0}", reviewPayload(verdict, summary))
    }

    override fun withdrawChanges(project: ProjectRef, mr: MergeRequest) = setStatus(project, mr, "UNAPPROVED")
```
(удалить `approvedBy`.) В companion:
```kotlin
        internal fun reviewers(rs: List<Map<String, Any?>>): Reviews {
            fun who(r: Map<String, Any?>) = r.o("user")?.str("slug")
            return Reviews(rs.filter { it.bool("approved") }.mapNotNull(::who), rs.filter { it.str("status") == "NEEDS_WORK" }.mapNotNull(::who))
        }

        internal fun reviewPayload(verdict: Verdict, summary: String): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
            if (summary.isNotBlank()) put("commentText", summary)
            when (verdict) {
                Verdict.APPROVE -> put("participantStatus", "APPROVED")
                Verdict.REQUEST_CHANGES -> put("participantStatus", "NEEDS_WORK")
                Verdict.COMMENT -> Unit
            }
        }
```

- [ ] **Step 5: Service**

`MrSession`: параметр `@Volatile var approvedBy: List<String>` → `@Volatile var reviews: Reviews`; добавить
```kotlin
    /** Whether a line position belongs to [change] (new path; old path for a deleted file). */
    fun onFile(p: Position, change: FileChange): Boolean =
        p.newPath == change.newPath || (p.newPath == null && p.oldPath == change.oldPath)
```
и `threadsFor` переписать через него: `discussions.filter { d -> !d.isSystem && d.position?.let { onFile(it, change) } == true }`.

`MrReviewService`: в `loadSession` — `val reviews = client.reviews(repo.project, full)` и передать в `MrSession`; `refreshApprovals` → 
```kotlin
    fun refreshReviews(s: MrSession) {
        s.reviews = client(s.connection).reviews(s.project, s.mr)
        fireChanged()
    }
```
и раздел черновиков:
```kotlin
    // ------------------------------------------------------------- review

    private val drafts = java.util.concurrent.ConcurrentHashMap<String, MutableList<Draft>>()
    private fun draftsKey(s: MrSession) = "me.brekhin.mrnavigator.drafts.${s.project.serverUrl}/${s.project.path}!${s.mr.iid}"

    private fun draftList(s: MrSession): MutableList<Draft> = drafts.getOrPut(draftsKey(s)) {
        java.util.Collections.synchronizedList(Drafts.decode(PropertiesComponent.getInstance(ideProject).getValue(draftsKey(s))).toMutableList())
    }

    /** Comments kept in the IDE until the review is submitted; they survive restarts. */
    fun drafts(s: MrSession): List<Draft> = draftList(s).let { synchronized(it) { it.toList() } }

    fun saveDraft(s: MrSession, draft: Draft) = changeDrafts(s) { list ->
        val i = list.indexOfFirst { it.id == draft.id }
        if (i >= 0) list[i] = draft else list += draft
    }

    fun removeDraft(s: MrSession, id: String) = changeDrafts(s) { list -> list.removeAll { it.id == id } }

    private fun changeDrafts(s: MrSession, change: (MutableList<Draft>) -> Unit) {
        val list = draftList(s)
        synchronized(list) {
            change(list)
            PropertiesComponent.getInstance(ideProject).setValue(draftsKey(s), Drafts.encode(list).takeIf { list.isNotEmpty() })
        }
        fireChanged()
    }

    /** Sends the drafts with [summary] and [verdict]; the drafts are dropped only after the server took them. */
    fun submitReview(s: MrSession, verdict: Verdict, summary: String) {
        client(s.connection).submitReview(s.project, s.mr, drafts(s), verdict, summary)
        changeDrafts(s) { it.clear() }
        refreshDiscussions(s)
        refreshReviews(s)
    }

    fun withdrawChanges(s: MrSession) {
        client(s.connection).withdrawChanges(s.project, s.mr)
        refreshReviews(s)
    }
```

- [ ] **Step 6: UI**

`ThreadPopup`:
- поле и помощник:
  ```kotlin
      /** Text typed into a popup closed without sending — offered again when the same popup opens. */
      private val unsent = HashMap<String, String>()

      private fun keepUnsent(key: String, input: JBTextArea, sent: Boolean) {
          if (!sent && input.text.isNotBlank()) unsent[key] = input.text else unsent.remove(key)
      }
  ```
- `showThread`: `val key = "t:${discussion.id}"`, `var sent = false`; после создания `input` — `input.text = unsent[key].orEmpty()`; в успехе ответа `{ sent = true; popup.cancel() }`; `popup = build(panel, input, title(discussion)) { keepUnsent(key, input, sent); onClosed() }`.
- `showNew`: `val key = position?.let { "n:${session.ref}:${it.newPath ?: it.oldPath}:${it.lineLabel()}" } ?: "g:${session.ref}"`, `var sent = false`, восстановление текста, `sent = true` в успехе, `keepUnsent` при закрытии; кнопка:
  ```kotlin
          val addToReview = JButton(msg("popup.addToReview")).apply {
              isVisible = position != null
              toolTipText = msg("popup.addToReview.tooltip")
              addActionListener {
                  val text = input.text.trim()
                  if (text.isNotEmpty() && position != null) {
                      service.saveDraft(session, Draft(java.util.UUID.randomUUID().toString(), text, position))
                      sent = true
                      popup.cancel()
                  }
              }
          }
  ```
  `left = listOf(send, addToReview, suggestionButton(...))`.
- новый метод:
  ```kotlin
      /** A draft of the review: change its text or delete it. */
      fun showDraft(project: Project, session: MrSession, draft: Draft, at: RelativePoint, onClosed: () -> Unit = {}) {
          val service = MrReviewService.getInstance(project)
          lateinit var popup: JBPopup
          val input = inputArea(msg("popup.commentPlaceholder")).apply { text = draft.body }
          val save = primary(msg("popup.save"))
          val delete = JButton(msg("popup.delete"))
          val submit = {
              input.text.trim().takeIf { it.isNotEmpty() }?.let {
                  service.saveDraft(session, draft.copy(body = it))
                  popup.cancel()
              }
              Unit
          }
          save.addActionListener { submit() }
          submitOnCtrlEnter(input, submit)
          delete.addActionListener {
              service.removeDraft(session, draft.id)
              popup.cancel()
          }
          val p = draft.position
          val panel = JPanel(BorderLayout()).apply {
              border = JBUI.Borders.empty(8, 10, 10, 10)
              add(editor(input, left = listOf(save), right = listOf(delete)), BorderLayout.CENTER)
          }
          popup = build(panel, input, msg("popup.draftTitle", (p.newPath ?: p.oldPath)?.substringAfterLast('/'), p.lineLabel()), onClosed)
          popup.show(at)
      }
  ```

`CommentMarkers.refresh()` — после цикла по тредам и до создания их иконок:
```kotlin
        // Drafts of the review on this file.
        for (draft in service.drafts(s)) {
            val p = draft.position
            if (!s.onFile(p, ctx.change)) continue
            val (side, line1) = when {
                p.newLine != null -> Side.RIGHT to p.newLine
                p.oldLine != null -> Side.LEFT to p.oldLine
                else -> continue
            }
            val editorLine = mapping.toEditor(side, line1 - 1)?.takeIf { it in 0 until lineCount } ?: continue
            val h = editor.markupModel.addLineHighlighter(editorLine, HighlighterLayer.LAST, null)
            h.gutterIconRenderer = DraftIcon(editorLine, draft)
            threadHighlighters += h
        }
```
и renderer:
```kotlin
    private inner class DraftIcon(private val line: Int, private val draft: Draft) : GutterIconRenderer() {
        override fun getIcon(): Icon = AllIcons.Actions.Edit
        override fun getTooltipText() = msg("diff.draft", StringUtil.escapeXmlEntities(draft.body.lineSequence().first().take(120)))
        override fun isNavigateAction() = true
        override fun getAlignment() = Alignment.LEFT

        override fun getClickAction(): AnAction = object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) = ThreadPopup.showDraft(project, session, draft, pointUnder(line))
        }

        override fun equals(other: Any?) = other is DraftIcon && other.line == line && other.draft == draft
        override fun hashCode() = line * 31 + draft.hashCode()
    }
```
(импорт `me.brekhin.mrnavigator.api.Draft`).

`ui/ReviewDialog.kt`:
```kotlin
package me.brekhin.mrnavigator.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.panel
import me.brekhin.mrnavigator.api.Draft
import me.brekhin.mrnavigator.api.Verdict
import me.brekhin.mrnavigator.core.MrReviewService
import me.brekhin.mrnavigator.core.MrSession
import me.brekhin.mrnavigator.util.Markdown
import me.brekhin.mrnavigator.util.msg
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.ButtonGroup
import javax.swing.JComponent

/** Submits a review: the drafts, a summary and a verdict. */
class ReviewDialog(project: Project, private val session: MrSession, private val drafts: List<Draft>, verdict: Verdict) :
    DialogWrapper(project, true) {
    private val service = MrReviewService.getInstance(project)
    private val summaryArea = JBTextArea(5, 60).apply { lineWrap = true; wrapStyleWord = true }
    private val comment = JBRadioButton(msg("review.comment"), verdict == Verdict.COMMENT)
    private val approve = JBRadioButton(msg("review.approve"), verdict == Verdict.APPROVE)
    private val requestChanges = JBRadioButton(msg("review.requestChanges"), verdict == Verdict.REQUEST_CHANGES)

    val verdict: Verdict
        get() = when {
            approve.isSelected -> Verdict.APPROVE
            requestChanges.isSelected -> Verdict.REQUEST_CHANGES
            else -> Verdict.COMMENT
        }
    val summary: String get() = summaryArea.text.trim()

    init {
        title = msg("review.title", session.ref)
        ButtonGroup().apply { add(comment); add(approve); add(requestChanges) }
        setOKButtonText(msg("review.submit"))
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        if (drafts.isNotEmpty()) {
            row { label(msg("review.drafts", drafts.size)) }
            for (d in drafts) {
                val p = d.position
                val where = "${(p.newPath ?: p.oldPath)?.substringAfterLast('/')}:${p.lineLabel()}"
                row { comment("$where — ${Markdown.escape(d.body.lineSequence().first().take(80))}") }
            }
        }
        row { label(msg("review.summary")) }
        row { scrollCell(summaryArea).align(Align.FILL) }.resizableRow()
        row { cell(comment); cell(approve); cell(requestChanges) }
    }

    override fun createLeftSideActions(): Array<Action> =
        if (drafts.isEmpty()) emptyArray() else arrayOf(object : DialogWrapperAction(msg("review.discard")) {
            override fun doAction(e: ActionEvent) {
                if (Messages.showYesNoDialog(contentPanel, msg("review.discardConfirm", drafts.size), msg("review.discard"), null) != Messages.YES) return
                drafts.forEach { service.removeDraft(session, it.id) }
                close(CANCEL_EXIT_CODE)
            }
        })

    override fun doValidate(): ValidationInfo? =
        if (verdict == Verdict.REQUEST_CHANGES && summary.isEmpty()) ValidationInfo(msg("review.summaryRequired"), summaryArea) else null

    override fun getPreferredFocusedComponent(): JComponent = summaryArea
}
```

`MrDetailsPanel`:
- поля: `private val requestChangesButton = JButton()`, `private val reviewButton = JButton(AllIcons.Actions.Edit)`; в ряд кнопок после `approveButton`: `add(requestChangesButton); add(reviewButton)`.
- слушатели:
  ```kotlin
          requestChangesButton.addActionListener {
              val s = session ?: return@addActionListener
              if (hasRequestedChanges(s)) withdrawChanges(s) else submitReview(s, Verdict.REQUEST_CHANGES)
          }
          reviewButton.addActionListener { session?.let { submitReview(it, Verdict.COMMENT) } }
  ```
- `render()`: meta-строка — вместо `approvedBy`:
  ```kotlin
              (if (s.reviews.approved.isNotEmpty()) " · " + msg("details.approvedBy", s.reviews.approved.joinToString()) else "") +
              (if (s.reviews.changesRequested.isNotEmpty()) " · " + msg("details.changesRequested", s.reviews.changesRequested.joinToString()) else "")
  ```
  и
  ```kotlin
          requestChangesButton.text = msg(if (hasRequestedChanges(s)) "details.withdrawChanges" else "details.requestChanges")
          val drafts = service.drafts(s).size
          reviewButton.isVisible = drafts > 0
          reviewButton.text = msg("details.review", drafts)
  ```
- `isApprovedByMe`: `me.username in s.reviews.approved`; новый
  ```kotlin
      private fun hasRequestedChanges(s: MrSession): Boolean {
          val me = service.currentUserCached(s.connection) ?: return false
          return me.username in s.reviews.changesRequested
      }
  ```
- `toggleApprove`: `service.refreshApprovals(s)` → `service.refreshReviews(s)`.
- новые:
  ```kotlin
      private fun submitReview(s: MrSession, verdict: Verdict) {
          val dialog = ReviewDialog(project, s, service.drafts(s), verdict)
          if (!dialog.showAndGet()) return
          val chosen = dialog.verdict
          val summary = dialog.summary
          Bg.run(project, msg("review.task"), work = { service.submitReview(s, chosen, summary) },
              onError = { Notify.error(project, msg("review.failed"), it) }) { Notify.info(project, msg("review.sent", s.ref)) }
      }

      private fun withdrawChanges(s: MrSession) {
          Bg.run(project, msg("details.withdrawTask"), work = { service.withdrawChanges(s) }) { }
      }
  ```

Ключи (en / ru):
```properties
details.requestChanges=Request changes…
details.withdrawChanges=Withdraw change request
details.withdrawTask=Withdrawing the change request
details.review=Review ({0})…
details.changesRequested=changes requested: {0}
popup.addToReview=Add to Review
popup.addToReview.tooltip=Keep the comment as a draft and send it with the review
popup.draftTitle=Draft · {0}:{1}
diff.draft=Draft: {0}
review.title=Review of {0}
review.drafts=Comments ({0}):
review.summary=Summary:
review.comment=Comment
review.approve=Approve
review.requestChanges=Request changes
review.submit=Submit
review.discard=Discard Drafts
review.discardConfirm=Delete {0} draft comments?
review.summaryRequired=Say what to change
review.task=Submitting the review
review.failed=Review not submitted
review.sent={0}: review submitted
```
```properties
details.requestChanges=Запросить изменения…
details.withdrawChanges=Снять запрос изменений
details.withdrawTask=Снятие запроса изменений
details.review=Ревью ({0})…
details.changesRequested=changes requested: {0}
popup.addToReview=В ревью
popup.addToReview.tooltip=Сохранить как черновик и отправить вместе с ревью
popup.draftTitle=Черновик · {0}:{1}
diff.draft=Черновик: {0}
review.title=Ревью {0}
review.drafts=Комментарии ({0}):
review.summary=Итоговый комментарий:
review.comment=Комментарий
review.approve=Approve
review.requestChanges=Запросить изменения
review.submit=Отправить
review.discard=Удалить черновики
review.discardConfirm=Удалить черновики ({0})?
review.summaryRequired=Напишите, что нужно изменить
review.task=Отправка ревью
review.failed=Ревью не отправлено
review.sent={0}: ревью отправлено
```
- [ ] **Step 7: Run — PASS** (`./gradlew test buildPlugin`). **Step 8: Commit** — `Batch comments into a review; request changes`.

---

### Task 5: Устаревшие треды на полях diff

**Files:**
- Modify: `api/GitHubClient.kt` (`originalCommit`), `core/MrReviewService.kt` (`MrSession.relocated`, `relocate`), `diff/MrDiffOpener.kt`, `diff/CommentMarkers.kt`
- Test: `GitHubTest.kt`, `LogicTest.kt`

**Interfaces:**
- Consumes: `MrSession.onFile`, `isOutdated` (Task 4 / 0.3).
- Produces: `MrSession.relocated: ConcurrentHashMap<String, Int>` (discussion id → строка новой версии, 1-based); `MrReviewService.relocate(s: MrSession, change: FileChange)`.

- [ ] **Step 1: Failing tests**

`GitHubTest.threads` — в фикстуре устаревшего треда комментарий `{"databaseId":9,"body":"old","originalCommit":{"oid":"old1"}}` и проверка
`assertEquals("old1", old.position!!.headSha)`.
`LogicTest` — добавить (фиксирует перенос строк по `-U0` diff):
```kotlin
    @Test
    fun relocateOutdatedLines() {
        // Old version → new: line 2 changed, two lines added after line 5.
        val diff = "diff --git a/f.go b/f.go\n--- a/f.go\n+++ b/f.go\n@@ -2 +2 @@\n-old\n+new\n@@ -5,0 +6,2 @@\n+x\n+y\n"
        val m = DiffLineMap(diff)
        assertEquals(1, m.newFor(1)); assertNull(m.newFor(2)); assertEquals(5, m.newFor(5)); assertEquals(9, m.newFor(7))
    }
```
- [ ] **Step 2: Run — FAIL** (`GitHubTest.threads`: headSha `h1` вместо `old1`).
- [ ] **Step 3: Implement**

GitHub: в `THREADS` у комментариев — `nodes { databaseId body createdAt url author { login ... on User { name } } originalCommit { oid } }`; в `parseThread` вычислить `comments` до `position` и:
```kotlin
            // An outdated thread keeps the commit it was written on: the gutter maps its line from there.
            val written = if (outdated) comments.firstOrNull()?.o("originalCommit")?.str("oid") ?: headSha else headSha
```
и передать `written` вместо `headSha` в `Position(…)`.

`MrSession`:
```kotlin
    /** Where outdated threads sit in the current code (thread id → line of the new version); see MrReviewService.relocate. */
    val relocated = java.util.concurrent.ConcurrentHashMap<String, Int>()
```
`MrReviewService`:
```kotlin
    /**
     * Finds where outdated threads of [change] sit in the current code: a diff from the version they were
     * written on (when that commit is local) maps their line, unless the line itself changed. Blocking.
     */
    fun relocate(s: MrSession, change: FileChange) {
        val head = s.mr.diffRefs?.headSha ?: return
        for (d in s.threadsFor(change)) {
            val p = d.position ?: continue
            val line = p.newLine ?: continue
            val written = p.headSha ?: continue
            if (!s.isOutdated(d) || written == head || d.id in s.relocated || !s.git.hasCommit(written)) continue
            val diff = s.git.run("diff", "-U0", written, head, "--", change.newPath, allowFail = true)
            DiffLineMap(diff).newFor(line)?.let { s.relocated[d.id] = it }
        }
    }
```
`MrDiffOpener.Producer.process`, перед `return request`:
```kotlin
                // Outdated threads of this file: where are their lines now? A failure only costs their gutter icons.
                try {
                    MrReviewService.getInstance(project).relocate(session, change)
                } catch (e: GitException) {
                }
```
(импорт `me.brekhin.mrnavigator.git.GitException`).

`CommentMarkers.refresh()` — внутри цикла по тредам заменить строки с `isOutdated`/`newLine`/`oldLine`:
```kotlin
            // An outdated thread is drawn only where its line moved to, if MrReviewService.relocate found it;
            // it is always listed on the Discussion tab.
            val moved = if (s.isOutdated(d)) s.relocated[d.id] ?: ctx.session.relocated[d.id] ?: continue else null
            val newLine = moved ?: p.newLine
            val oldLine = if (moved != null) null else p.oldLine
```
`ThreadsIcon`:
```kotlin
        override fun getIcon(): Icon = when {
            threads.all { it.resolved } -> AllIcons.General.InspectionsOK
            threads.all { session.isOutdated(it) } -> IconLoader.getDisabledIcon(AllIcons.General.Balloon)
            else -> AllIcons.General.Balloon
        }
```
в тултипе после `$range` — `+ (if (session.isOutdated(d)) " <i>(${msg("thread.outdated")})</i>" else "")`; в клике:
```kotlin
                val outdated = session.isOutdated(d)
                val start = if (outdated) null else rangeStart(d, line)
                val highlight = start?.let { highlightLines(it, line) }
                val suggestion = if (!outdated && d.position?.newLine != null) newSideText(start ?: line, line) else null
```
(импорт `com.intellij.openapi.util.IconLoader`).

- [ ] **Step 4: Run — PASS.** **Step 5: Commit** — `Show outdated threads where their line moved`.

---

### Task 6: CI и Merge

**Files:**
- Create: `ui/MergeDialog.kt`, `src/test/kotlin/me/brekhin/mrnavigator/ChecksTest.kt`
- Modify: `api/Models.kt`, `api/Hosting.kt`, четыре клиента, `core/MrReviewService.kt`, `ui/MrDetailsPanel.kt`, оба `.properties`
- Test: `ChecksTest.kt`, `GitLabTest.kt`, `GitHubTest.kt`, `BitbucketTest.kt`, `BundleTest.kt`

**Interfaces:**
- Produces: `enum class CiState { SUCCESS, FAILED, RUNNING, NONE }`, `Check(name, state, url)`, `Checks(state, url, items)` с `Checks.NONE` и `Checks.of(items, url)`; `MergeStrategy(id, title)` с `MergeStrategy.of(id)`; `MergeOptions(strategies, defaultStrategy, canDeleteBranch, blocker)`; `HostingClient.checks/mergeOptions/merge`; `MrSession.checks: Checks` (var); `MrReviewService.mergeOptions(s)`, `merge(s, strategy, deleteBranch)`; чистые `GitLabClient.pipeline/mergeOptions(mr, project)`, `GitHubClient.checksOf(runs, statuses, url)/mergeOptions(pr, repo)`, `BitbucketCloudClient.statuses(items, url)/mergeOptions(pr)/taskDone(task)`, `BitbucketServerClient.builds(items, url)/mergeOptions(check, config)`.

- [ ] **Step 1: Failing tests**

`ChecksTest.kt`:
```kotlin
package me.brekhin.mrnavigator

import me.brekhin.mrnavigator.api.Check
import me.brekhin.mrnavigator.api.Checks
import me.brekhin.mrnavigator.api.CiState
import me.brekhin.mrnavigator.api.MergeStrategy
import org.junit.Test
import kotlin.test.assertEquals

class ChecksTest {
    private fun c(state: CiState) = Check("x", state, null)

    @Test
    fun foldsChecks() {
        assertEquals(CiState.NONE, Checks.of(emptyList(), null).state)
        assertEquals(CiState.NONE, Checks.of(listOf(c(CiState.NONE)), null).state)
        assertEquals(CiState.FAILED, Checks.of(listOf(c(CiState.SUCCESS), c(CiState.FAILED), c(CiState.RUNNING)), null).state)
        assertEquals(CiState.RUNNING, Checks.of(listOf(c(CiState.SUCCESS), c(CiState.RUNNING)), null).state)
        assertEquals(CiState.SUCCESS, Checks.of(listOf(c(CiState.SUCCESS), c(CiState.NONE)), null).state)
        assertEquals("Merge commit", MergeStrategy.of("merge_commit").title)
    }
}
```
`GitLabTest` — добавить:
```kotlin
    @Test
    fun ciAndMerge() {
        val p = GitLabClient.pipeline(obj("""{"id":5,"status":"running","web_url":"https://g/p/5"}"""))
        assertEquals(CiState.RUNNING, p.state); assertEquals("https://g/p/5", p.url)
        assertEquals(CiState.FAILED, GitLabClient.pipeline(obj("""{"status":"failed"}""")).state)
        assertEquals(CiState.NONE, GitLabClient.pipeline(obj("""{"status":"skipped"}""")).state)
        val o = GitLabClient.mergeOptions(obj("""{"detailed_merge_status":"not_approved"}"""), obj("""{"squash_option":"default_on"}"""))
        assertEquals(listOf("merge", "squash"), o.strategies.map { it.id }); assertEquals("squash", o.defaultStrategy)
        assertEquals("not approved", o.blocker); assertTrue(o.canDeleteBranch)
        val ok = GitLabClient.mergeOptions(obj("""{"detailed_merge_status":"mergeable"}"""), obj("""{"squash_option":"never"}"""))
        assertNull(ok.blocker); assertEquals(listOf("merge"), ok.strategies.map { it.id })
    }
```
`GitHubTest` — добавить:
```kotlin
    @Test
    fun ciAndMerge() {
        val runs = listOf("""{"name":"build","status":"completed","conclusion":"success","html_url":"u1"}""",
            """{"name":"lint","status":"in_progress","conclusion":null}""").map { obj(it) }
        val statuses = listOf("""{"context":"ci/x","state":"failure","target_url":"u2"}""").map { obj(it) }
        val checks = GitHubClient.checksOf(runs, statuses, "https://github.com/o/r/pull/1/checks")
        assertEquals(CiState.FAILED, checks.state); assertEquals(listOf("build", "lint", "ci/x"), checks.items.map { it.name })
        assertEquals(CiState.RUNNING, checks.items[1].state)
        val pr = obj("""{"mergeable":false,"mergeable_state":"dirty","head":{"repo":{"full_name":"o/r"}},"base":{"repo":{"full_name":"o/r"}}}""")
        val o = GitHubClient.mergeOptions(pr, obj("""{"allow_merge_commit":false,"allow_squash_merge":true,"allow_rebase_merge":true}"""))
        assertEquals(listOf("squash", "rebase"), o.strategies.map { it.id }); assertEquals("dirty", o.blocker); assertTrue(o.canDeleteBranch)
        // Without push access GitHub hides the allow_* flags: offer all, the server decides.
        val fork = obj("""{"mergeable":true,"head":{"repo":{"full_name":"me/r"}},"base":{"repo":{"full_name":"o/r"}}}""")
        val f = GitHubClient.mergeOptions(fork, obj("{}"))
        assertEquals(listOf("merge", "squash", "rebase"), f.strategies.map { it.id }); assertFalse(f.canDeleteBranch); assertNull(f.blocker)
    }
```
`BitbucketTest` — добавить:
```kotlin
    @Test
    fun ciAndMerge() {
        val cloud = BitbucketCloudClient.statuses(listOf("""{"name":"build","state":"SUCCESSFUL","url":"u"}""", """{"key":"k","state":"STOPPED"}""").map { obj(it) }, "pr")
        assertEquals(CiState.FAILED, cloud.state); assertEquals(listOf("build", "k"), cloud.items.map { it.name })
        val o = BitbucketCloudClient.mergeOptions(obj("""{"destination":{"branch":{"merge_strategies":["merge_commit","squash"],"default_merge_strategy":"squash"}}}"""))
        assertEquals(listOf("merge_commit", "squash"), o.strategies.map { it.id }); assertEquals("squash", o.defaultStrategy)
        assertTrue(BitbucketCloudClient.taskDone(obj("""{"task_status":"SUCCESS"}"""))); assertFalse(BitbucketCloudClient.taskDone(obj("""{"task_status":"PENDING"}""")))
        val dc = BitbucketServerClient.builds(listOf("""{"name":"b","state":"INPROGRESS"}""").map { obj(it) }, "pr")
        assertEquals(CiState.RUNNING, dc.state)
        val config = obj("""{"defaultStrategy":{"id":"no-ff"},"strategies":[{"id":"no-ff","name":"Merge commit","enabled":true},{"id":"squash","name":"Squash","enabled":false}]}""")
        val d = BitbucketServerClient.mergeOptions(obj("""{"vetoes":[{"summaryMessage":"Needs 2 approvals"}]}"""), config)
        assertEquals(listOf(MergeStrategy("no-ff", "Merge commit")), d.strategies); assertEquals("no-ff", d.defaultStrategy)
        assertEquals("Needs 2 approvals", d.blocker); assertFalse(d.canDeleteBranch)
        assertNull(BitbucketServerClient.mergeOptions(obj("{}"), null).blocker)
    }
```
`BundleTest.everyHostingHasItsStrings` — в цикл по языкам добавить
`CiState.entries.filter { it != CiState.NONE }.forEach { MrBundle.message("details.ci.${it.name}") }`.
Импорты по месту (`CiState`, `MergeStrategy`, `assertTrue/assertFalse/assertNull`).

- [ ] **Step 2: Run — FAIL.**
- [ ] **Step 3: Models and interface** — `Models.kt` в конец:
```kotlin
enum class CiState { SUCCESS, FAILED, RUNNING, NONE }

data class Check(val name: String, val state: CiState, val url: String?)

/** CI of the head commit; [state] folds the checks: a failure wins, then a running one. */
data class Checks(val state: CiState, val url: String?, val items: List<Check>) {
    companion object {
        val NONE = Checks(CiState.NONE, null, emptyList())

        fun of(items: List<Check>, url: String?): Checks {
            val state = when {
                items.any { it.state == CiState.FAILED } -> CiState.FAILED
                items.any { it.state == CiState.RUNNING } -> CiState.RUNNING
                items.any { it.state == CiState.SUCCESS } -> CiState.SUCCESS
                else -> CiState.NONE
            }
            return Checks(state, url, items)
        }
    }
}

data class MergeStrategy(val id: String, val title: String) {
    override fun toString() = title

    companion object {
        /** "merge_commit" → "Merge commit", for servers that give only ids. */
        fun of(id: String) = MergeStrategy(id, id.replace('_', ' ').replaceFirstChar { it.uppercase() })
    }
}

/** How the request can be merged; [blocker] — the server's reason it can't be now, if any. */
data class MergeOptions(val strategies: List<MergeStrategy>, val defaultStrategy: String?, val canDeleteBranch: Boolean, val blocker: String?)
```
`HostingClient`:
```kotlin
    /** CI of the head commit. */
    fun checks(project: ProjectRef, mr: MergeRequest): Checks
    fun mergeOptions(project: ProjectRef, mr: MergeRequest): MergeOptions
    /** [strategy] — an id from [mergeOptions], null for the server's default. */
    fun merge(project: ProjectRef, mr: MergeRequest, strategy: String?, deleteBranch: Boolean)
```

- [ ] **Step 4: Clients**

GitLab:
```kotlin
    override fun checks(project: ProjectRef, mr: MergeRequest): Checks =
        json("GET", "${proj(project)}/merge_requests/${mr.iid}").obj().o("head_pipeline")?.let { pipeline(it) } ?: Checks.NONE

    override fun mergeOptions(project: ProjectRef, mr: MergeRequest): MergeOptions =
        mergeOptions(json("GET", "${proj(project)}/merge_requests/${mr.iid}").obj(), json("GET", proj(project)).obj())

    override fun merge(project: ProjectRef, mr: MergeRequest, strategy: String?, deleteBranch: Boolean) {
        val payload = linkedMapOf<String, Any?>("squash" to (strategy == "squash"), "should_remove_source_branch" to deleteBranch)
        mr.diffRefs?.headSha?.let { payload["sha"] = it }
        json("PUT", "${mrPath(project, mr)}/merge", payload)
    }
```
companion:
```kotlin
        internal fun ciState(status: String?): CiState = when (status) {
            "success" -> CiState.SUCCESS
            "failed", "canceled", "canceling" -> CiState.FAILED
            null, "skipped" -> CiState.NONE
            else -> CiState.RUNNING
        }

        internal fun pipeline(p: Map<String, Any?>): Checks {
            val url = p.str("web_url")
            return Checks.of(listOf(Check("Pipeline #${p.long("id") ?: ""}", ciState(p.str("status")), url)), url)
        }

        /** Squash per the project's setting; the merge method itself (merge / rebase / ff) is the project's too. */
        internal fun mergeOptions(mr: Map<String, Any?>, project: Map<String, Any?>): MergeOptions {
            val squash = project.str("squash_option") ?: "default_off"
            val strategies = when (squash) {
                "never" -> listOf("merge")
                "always" -> listOf("squash")
                else -> listOf("merge", "squash")
            }.map { MergeStrategy.of(it) }
            val default = if (squash == "always" || squash == "default_on") "squash" else "merge"
            val blocker = mr.str("detailed_merge_status")?.takeIf { it != "mergeable" }?.replace('_', ' ')
            return MergeOptions(strategies, default, canDeleteBranch = true, blocker = blocker)
        }
```
(импорт `me.brekhin.mrnavigator.util.long`.)

GitHub:
```kotlin
    override fun checks(project: ProjectRef, mr: MergeRequest): Checks {
        val sha = mr.diffRefs?.headSha ?: mr.sha ?: return Checks.NONE
        val runs = get("${repo(project)}/commits/$sha/check-runs?per_page=100").obj().a("check_runs").map { it.obj() }
        val statuses = get("${repo(project)}/commits/$sha/status").obj().a("statuses").map { it.obj() }
        return checksOf(runs, statuses, "${mr.webUrl}/checks")
    }

    override fun mergeOptions(project: ProjectRef, mr: MergeRequest): MergeOptions =
        mergeOptions(get("${pull(project, mr)}").obj(), get(repo(project)).obj())

    override fun merge(project: ProjectRef, mr: MergeRequest, strategy: String?, deleteBranch: Boolean) {
        val payload = linkedMapOf<String, Any?>()
        mr.sha?.let { payload["sha"] = it }
        strategy?.let { payload["merge_method"] = it }
        send("PUT", "${pull(project, mr)}/merge", payload)
        if (deleteBranch) http.call("DELETE", api + "${repo(project)}/git/refs/heads/${mr.sourceBranch}")
    }
```
companion:
```kotlin
        internal fun checksOf(runs: List<Map<String, Any?>>, statuses: List<Map<String, Any?>>, url: String?): Checks = Checks.of(
            runs.map { Check(it.str("name") ?: "?", runState(it.str("status"), it.str("conclusion")), it.str("html_url")) } +
                statuses.map { Check(it.str("context") ?: "?", statusState(it.str("state")), it.str("target_url")) },
            url,
        )

        private fun runState(status: String?, conclusion: String?) = when {
            status != "completed" -> CiState.RUNNING
            conclusion == "success" || conclusion == "neutral" || conclusion == "skipped" -> CiState.SUCCESS
            else -> CiState.FAILED
        }

        private fun statusState(state: String?) = when (state) {
            "success" -> CiState.SUCCESS
            "pending" -> CiState.RUNNING
            else -> CiState.FAILED
        }

        internal fun mergeOptions(pr: Map<String, Any?>, repo: Map<String, Any?>): MergeOptions {
            // The allow_* flags are hidden without push access: then offer all and let the server decide.
            fun allowed(key: String) = repo[key] as? Boolean ?: true
            val strategies = listOfNotNull(
                "merge".takeIf { allowed("allow_merge_commit") },
                "squash".takeIf { allowed("allow_squash_merge") },
                "rebase".takeIf { allowed("allow_rebase_merge") },
            ).map { MergeStrategy.of(it) }
            val head = pr.o("head")?.o("repo")?.str("full_name")
            val sameRepo = head != null && head == pr.o("base")?.o("repo")?.str("full_name")
            val state = pr.str("mergeable_state")
            val blocked = pr["mergeable"] == false || state == "dirty" || state == "blocked"
            return MergeOptions(strategies, strategies.firstOrNull()?.id, sameRepo, if (blocked) state ?: "not mergeable" else null)
        }
```

Bitbucket Cloud:
```kotlin
    override fun checks(project: ProjectRef, mr: MergeRequest): Checks = statuses(paged("${pr(project, mr)}/statuses"), mr.webUrl)

    override fun mergeOptions(project: ProjectRef, mr: MergeRequest): MergeOptions = mergeOptions(get(pr(project, mr)))

    /** A long merge answers 202 with a task to poll; after a minute the user is sent to the browser. */
    override fun merge(project: ProjectRef, mr: MergeRequest, strategy: String?, deleteBranch: Boolean) {
        val payload = linkedMapOf<String, Any?>("close_source_branch" to deleteBranch)
        strategy?.let { payload["merge_strategy"] = it }
        val task = http.call("POST", api + "${pr(project, mr)}/merge", payload).header("Location") ?: return
        repeat(60) {
            Thread.sleep(1000)
            ProgressManager.checkCanceled()
            if (taskDone(http.call("GET", task).json().obj())) return
        }
        throw ApiException(msg("merge.stillRunning", "#${mr.iid}"))
    }
```
companion:
```kotlin
        internal fun statuses(items: List<Map<String, Any?>>, url: String?): Checks = Checks.of(items.map {
            val state = when (it.str("state")) {
                "SUCCESSFUL" -> CiState.SUCCESS
                "INPROGRESS" -> CiState.RUNNING
                else -> CiState.FAILED
            }
            Check(it.str("name") ?: it.str("key") ?: "?", state, it.str("url"))
        }, url)

        internal fun mergeOptions(pr: Map<String, Any?>): MergeOptions {
            val branch = pr.o("destination")?.o("branch")
            val strategies = branch?.a("merge_strategies").orEmpty().mapNotNull { it as? String }.map { MergeStrategy.of(it) }
            return MergeOptions(strategies, branch?.str("default_merge_strategy"), canDeleteBranch = true, blocker = null)
        }

        /** A merge task is done at SUCCESS; a failed one comes as an HTTP error of the poll. */
        internal fun taskDone(task: Map<String, Any?>): Boolean = task.str("task_status") == "SUCCESS"
```
(импорт `com.intellij.openapi.progress.ProgressManager`.)

Bitbucket DC:
```kotlin
    private val root = serverUrl.trimEnd('/')
```
(в конструкторе класса; `api` = `"$root/rest/api/latest"`.)
```kotlin
    override fun checks(project: ProjectRef, mr: MergeRequest): Checks {
        val sha = mr.sha ?: return Checks.NONE
        // Deprecated since 7.14, yet still the only call that lists all builds of a commit.
        val builds = http.call("GET", "$root/rest/build-status/latest/commits/$sha?limit=100").json().obj().a("values").map { it.obj() }
        return builds(builds, mr.webUrl)
    }

    override fun mergeOptions(project: ProjectRef, mr: MergeRequest): MergeOptions {
        val check = get("${pr(project, mr)}/merge")
        val config = try {
            get("${repo(project)}/settings/pull-requests").o("mergeConfig")
        } catch (e: ApiException) {
            null
        }
        return mergeOptions(check, config)
    }

    override fun merge(project: ProjectRef, mr: MergeRequest, strategy: String?, deleteBranch: Boolean) {
        val path = pr(project, mr)
        val payload = linkedMapOf<String, Any?>()
        strategy?.let { payload["strategyId"] = it }
        send("POST", "$path/merge?version=${get(path).int("version") ?: 0}", payload)
    }
```
companion:
```kotlin
        internal fun builds(items: List<Map<String, Any?>>, url: String?): Checks = Checks.of(items.map {
            val state = when (it.str("state")) {
                "SUCCESSFUL" -> CiState.SUCCESS
                "INPROGRESS" -> CiState.RUNNING
                "FAILED", "CANCELLED" -> CiState.FAILED
                else -> CiState.NONE
            }
            Check(it.str("name") ?: it.str("key") ?: "?", state, it.str("url"))
        }, url)

        internal fun mergeOptions(check: Map<String, Any?>, config: Map<String, Any?>?): MergeOptions {
            val strategies = config?.a("strategies").orEmpty().map { it.obj() }.filter { it.bool("enabled") }
                .mapNotNull { s -> s.str("id")?.let { MergeStrategy(it, s.str("name") ?: it) } }
            val vetoes = check.a("vetoes").mapNotNull { it.obj().str("summaryMessage") }
            val blocker = when {
                vetoes.isNotEmpty() -> vetoes.joinToString("; ")
                check.bool("conflicted") -> "conflicted"
                else -> null
            }
            return MergeOptions(strategies, config?.o("defaultStrategy")?.str("id"), canDeleteBranch = false, blocker = blocker)
        }
```

- [ ] **Step 5: Service and UI**

`MrSession`: `/** CI of the head commit, loaded with the session. */ @Volatile var checks: Checks = Checks.NONE`.
`MrReviewService.loadSession` — после создания `s`:
```kotlin
        s.checks = try {
            client.checks(repo.project, full)
        } catch (e: ApiException) {
            Checks.NONE
        }
```
и
```kotlin
    fun mergeOptions(s: MrSession): MergeOptions = client(s.connection).mergeOptions(s.project, s.mr)

    fun merge(s: MrSession, strategy: String?, deleteBranch: Boolean) = client(s.connection).merge(s.project, s.mr, strategy, deleteBranch)
```
`ui/MergeDialog.kt`:
```kotlin
package me.brekhin.mrnavigator.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import me.brekhin.mrnavigator.api.MergeOptions
import me.brekhin.mrnavigator.api.MergeStrategy
import me.brekhin.mrnavigator.core.MrSession
import me.brekhin.mrnavigator.util.msg
import javax.swing.JComponent

/** Merge: the server's reason it can't be merged now, the strategy and whether to delete the source branch. */
class MergeDialog(project: Project, session: MrSession, private val options: MergeOptions) : DialogWrapper(project, true) {
    private val strategyCombo = ComboBox(options.strategies.toTypedArray()).apply {
        options.strategies.firstOrNull { it.id == options.defaultStrategy }?.let { selectedItem = it }
    }
    private val deleteBranchBox = JBCheckBox(msg("merge.deleteBranch"))

    val strategy: String? get() = (strategyCombo.selectedItem as? MergeStrategy)?.id
    val deleteBranch: Boolean get() = options.canDeleteBranch && deleteBranchBox.isSelected

    init {
        title = msg("merge.title", session.ref, session.mr.targetBranch)
        setOKButtonText(msg("merge.ok"))
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        options.blocker?.let { b ->
            row { label(msg("merge.blocked", b)).applyToComponent { foreground = UIUtil.getErrorForeground() } }
        }
        if (options.strategies.isNotEmpty()) row(msg("merge.strategy")) { cell(strategyCombo) }
        if (options.canDeleteBranch) row { cell(deleteBranchBox) }
    }
}
```
`MrDetailsPanel`:
- поля:
  ```kotlin
      private val ci = JBLabel().apply {
          cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
          addMouseListener(object : MouseAdapter() {
              override fun mouseClicked(e: MouseEvent) {
                  session?.checks?.url?.let { BrowserUtil.browse(it) }
              }
          })
      }
      private val mergeButton = JButton(msg("details.merge"))
  ```
  в `header`: `add(title); add(meta); add(ci); add(checkoutState)` и `ci` в список `alignmentX = LEFT_ALIGNMENT`; `mergeButton` в ряд кнопок после `reviewButton`; `mergeButton.addActionListener { merge() }`.
- в `render()`:
  ```kotlin
          val checks = s.checks
          ci.isVisible = checks.state != CiState.NONE
          ci.icon = when (checks.state) {
              CiState.SUCCESS -> AllIcons.General.InspectionsOK
              CiState.FAILED -> AllIcons.General.Error
              else -> AllIcons.Actions.Execute
          }
          if (checks.state != CiState.NONE) ci.text = msg("details.ci.${checks.state.name}")
          ci.toolTipText = checks.items.joinToString("<br>", "<html>", "</html>") { c ->
              val mark = when (c.state) { CiState.SUCCESS -> "✓"; CiState.FAILED -> "✗"; CiState.RUNNING -> "…"; CiState.NONE -> "·" }
              "$mark ${Markdown.escape(c.name)}"
          }
          mergeButton.isVisible = mr.state == "opened" || mr.state == "open"
  ```
- новый:
  ```kotlin
      private fun merge() {
          val s = session ?: return
          Bg.run(project, msg("merge.loading", s.ref), work = { service.mergeOptions(s) }) { options ->
              val dialog = MergeDialog(project, s, options)
              if (!dialog.showAndGet()) return@run
              val strategy = dialog.strategy
              val deleteBranch = dialog.deleteBranch
              Bg.run(project, msg("merge.task", s.ref), work = { service.merge(s, strategy, deleteBranch) },
                  onError = { Notify.error(project, msg("merge.failed"), it) }) {
                  Notify.info(project, msg("merge.done", s.ref, s.mr.targetBranch))
                  load(s.mr, s.type)
              }
          }
      }
  ```
  импорты: `java.awt.Cursor`, `me.brekhin.mrnavigator.api.CiState`, `me.brekhin.mrnavigator.api.Verdict` (уже), `MergeDialog` в том же пакете.

Ключи (en / ru):
```properties
details.ci.SUCCESS=CI passed
details.ci.FAILED=CI failed
details.ci.RUNNING=CI running
details.merge=Merge…
merge.loading=Checking whether {0} can be merged
merge.title=Merge {0} into {1}
merge.blocked=Not mergeable now: {0}
merge.strategy=Strategy:
merge.deleteBranch=Delete the source branch
merge.ok=Merge
merge.task=Merging {0}
merge.failed=Merge failed
merge.done={0} merged into {1}
merge.stillRunning={0} is still merging: check it in the browser
```
```properties
details.ci.SUCCESS=CI пройден
details.ci.FAILED=CI упал
details.ci.RUNNING=CI идёт
details.merge=Merge…
merge.loading=Проверка, можно ли влить {0}
merge.title=Merge {0} в {1}
merge.blocked=Сейчас влить нельзя: {0}
merge.strategy=Стратегия:
merge.deleteBranch=Удалить исходную ветку
merge.ok=Merge
merge.task=Merge {0}
merge.failed=Merge не удался
merge.done={0} влит в {1}
merge.stillRunning=Merge {0} ещё идёт — проверьте в браузере
```
- [ ] **Step 6: Run — PASS.** **Step 7: Commit** — `Show CI status and merge from the IDE`.

---

### Task 7: Документация и версия 0.4.0

**Files:** `README.md`, `README.ru.md`, `CHANGELOG.md`, `build.gradle.kts`, `src/main/resources/META-INF/plugin.xml`

- [ ] **Step 1:** `build.gradle.kts` → `version = "0.4.0"`.
- [ ] **Step 2:** `CHANGELOG.md` — в начало:
```markdown
## 0.4.0

- Reviews: keep line comments as drafts and submit them together with a summary and a verdict — Comment, Approve or Request changes. One notification on GitLab, GitHub and Bitbucket Data Center.
- Request changes and withdraw the request; the card shows who approved and who requested changes.
- Edit and delete your own comments.
- CI status of the head commit in the card, and Merge with the strategies the server allows.
- Outdated threads (GitLab, GitHub) are drawn in the gutter where their line moved, if it didn't change.
- Comments are rendered as GitHub-flavoured Markdown.
- GitHub and Bitbucket Cloud: the merge base is computed by git — pull requests from forks get their diff on Bitbucket Cloud.
- Bitbucket: files with non-ASCII names in the diff.
- A comment typed into a popup closed without sending is offered again.
```
- [ ] **Step 3:** `plugin.xml` — в `<li>` про комментарии: «Comments: line and multi-line threads, replies, resolve, suggestions, general comments; drafts submitted as one review; edit and delete your own; approve, request changes, merge, CI status.»
- [ ] **Step 4:** README (en/ru) — в Features: пункт про ревью пачкой и Request changes, правку/удаление, CI и Merge, Markdown в комментариях; в Limitations убрать «Threads that became outdated … are not drawn in the gutter» и добавить: «Outdated threads are drawn in the gutter only on GitLab and GitHub, when the commit they were written on is in the local repository and their line didn't change.», «Bitbucket Cloud has no API for batched reviews: the comments of a review are sent one by one (a notification each).», «Merge: no auto-merge or merge queue; Bitbucket Data Center can't delete the source branch from the plugin.»; в таблицу How it works — строки `core/Drafts.kt` (drafts of a review) и `ui/ReviewDialog.kt`, `ui/MergeDialog.kt`.
- [ ] **Step 5:** `./gradlew test buildPlugin` — PASS, `build/distributions/mr-navigator-0.4.0.zip` есть.
- [ ] **Step 6: Commit** — `Release 0.4.0: reviews, request changes, CI and merge`.
