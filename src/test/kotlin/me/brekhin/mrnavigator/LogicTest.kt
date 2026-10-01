package me.brekhin.mrnavigator

import me.brekhin.mrnavigator.api.ApiException
import me.brekhin.mrnavigator.api.DiffRefs
import me.brekhin.mrnavigator.api.Draft
import me.brekhin.mrnavigator.api.FileChange
import me.brekhin.mrnavigator.api.GitHubClient
import me.brekhin.mrnavigator.api.Http
import me.brekhin.mrnavigator.api.ProjectRef
import me.brekhin.mrnavigator.api.Reviews
import me.brekhin.mrnavigator.api.basicOrBearer
import me.brekhin.mrnavigator.api.LinePoint
import me.brekhin.mrnavigator.api.MergeRequest
import me.brekhin.mrnavigator.api.Note
import me.brekhin.mrnavigator.api.NoteSuggestion
import me.brekhin.mrnavigator.api.Position
import me.brekhin.mrnavigator.core.DiffLineMap
import me.brekhin.mrnavigator.core.Drafts
import me.brekhin.mrnavigator.core.HiddenFiles
import me.brekhin.mrnavigator.core.MrSession
import me.brekhin.mrnavigator.git.GitCli
import me.brekhin.mrnavigator.git.RemoteUrl
import me.brekhin.mrnavigator.util.Json
import me.brekhin.mrnavigator.util.Markdown
import me.brekhin.mrnavigator.util.MrBundle
import me.brekhin.mrnavigator.util.Suggestion
import me.brekhin.mrnavigator.util.TimeAgo
import me.brekhin.mrnavigator.util.obj
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import me.brekhin.mrnavigator.api.Connection
import me.brekhin.mrnavigator.api.HostingType
import me.brekhin.mrnavigator.settings.MrReviewSettings

class LogicTest {
    private fun gitlab(url: String) = Connection(HostingType.GITLAB, url)

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
    fun legacyMigrationRetriesUntilTheTokenIsReadable() {
        val s = MrReviewSettings()
        // A locked keychain at the first try must not drop the old GitLab setup for good.
        s.migrateLegacy { false }
        assertTrue(s.connections.isEmpty())
        s.migrateLegacy { it == "https://gitlab.com" }
        assertEquals(listOf(Connection(HostingType.GITLAB, "https://gitlab.com")), s.connections)
        s.connections = emptyList()
        s.migrateLegacy { true } // done once — a removed connection is not brought back
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

    @Test
    fun remoteUrls() {
        assertEquals(RemoteUrl("gitlab.com", "group/sub/proj"), RemoteUrl.parse("git@gitlab.com:group/sub/proj.git"))
        assertEquals(RemoteUrl("git.corp.ru", "team/api"), RemoteUrl.parse("https://git.corp.ru/team/api.git"))
        assertEquals(RemoteUrl("git.corp.ru", "team/api"), RemoteUrl.parse("https://user:tok@git.corp.ru:8443/team/api"))
        assertEquals(RemoteUrl("git.corp.ru", "team/api"), RemoteUrl.parse("ssh://git@git.corp.ru:2222/team/api.git"))
        assertNull(RemoteUrl.parse("https://git.corp.ru/onlyone"))
        assertEquals("team/api", RemoteUrl.projectPath(RemoteUrl.parse("https://host/gitlab/team/api.git")!!, gitlab("https://host/gitlab/")))
        assertEquals("team/api", RemoteUrl.projectPath(RemoteUrl.parse("git@host:team/api.git")!!, gitlab("https://host/gitlab")))
        assertNull(RemoteUrl.projectPath(RemoteUrl.parse("git@other:team/api.git")!!, gitlab("https://host")))
    }

    @Test
    fun hiddenFiles() {
        val h = HiddenFiles(listOf(".pb.go", " .pb.gw.go "))
        assertTrue(h.isHidden("api/user.pb.go"))
        assertTrue(h.isHidden("api/user_grpc.pb.go"))
        assertTrue(h.isHidden("api/user.pb.gw.go"))
        assertFalse(h.isHidden("api/user.go"))
        val renamed = FileChange("old.pb.go", "new.go", false, false, true, "", false)
        assertFalse(h.isHidden(renamed))
        val deleted = FileChange("gone.pb.go", "gone.pb.go", false, true, false, "", false)
        assertTrue(h.isHidden(deleted))
        assertFalse(HiddenFiles(listOf(".pb.go"), enabled = false).isHidden("a.pb.go"))
        assertEquals(listOf(".pb.go", "_mock.go"), HiddenFiles.parse(".pb.go\n\n _mock.go ,.pb.go"))
    }

    @Test
    fun lineMap() {
        // old: 1..10; new: line 3 replaced by two lines, line 8 removed
        val diff = """
            @@ -2,3 +2,4 @@ func a() {
             two
            -three
            +THREE
            +three-and-half
             four
            @@ -7,3 +8,2 @@ func b() {
             seven
            -eight
             nine
        """.trimIndent()
        val m = DiffLineMap(diff)
        assertTrue(m.isAdded(3)); assertTrue(m.isAdded(4)); assertTrue(m.isRemoved(3)); assertTrue(m.isRemoved(8))
        assertEquals(1, m.oldFor(1))       // before first hunk
        assertEquals(2, m.oldFor(2))       // context
        assertNull(m.oldFor(3))            // added
        assertEquals(4, m.oldFor(5))       // context "four"
        assertEquals(5, m.oldFor(6))       // between hunks, shifted by +1
        assertEquals(7, m.oldFor(8))       // context "seven"
        assertEquals(9, m.oldFor(9))       // context "nine"
        assertEquals(10, m.oldFor(10))     // after last hunk (+1 then -1)
        assertEquals(6, m.newFor(5))
        assertNull(m.newFor(8))
        assertEquals(10, m.newFor(10))

        val p = m.position("b", "s", "h", "f.go", "f.go", 3, onNewSide = true)
        assertEquals(null, p.oldLine); assertEquals(3, p.newLine)
        val q = m.position("b", "s", "h", "f.go", "f.go", 8, onNewSide = false)
        assertEquals(8, q.oldLine); assertEquals(null, q.newLine)
        val r = m.position("b", "s", "h", "f.go", "f.go", 6, onNewSide = true)
        assertEquals(5, r.oldLine); assertEquals(6, r.newLine)

        // pure insertion / pure deletion hunks
        val ins = DiffLineMap("@@ -3,0 +4,2 @@\n+a\n+b")
        assertEquals(3, ins.newFor(3)); assertEquals(6, ins.newFor(4)); assertEquals(3, ins.oldFor(3)); assertEquals(4, ins.oldFor(6))
        val del = DiffLineMap("@@ -5,2 +4,0 @@\n-a\n-b")
        assertEquals(4, del.oldFor(4)); assertEquals(7, del.oldFor(5)); assertEquals(5, del.newFor(7))
        // new file
        val nf = DiffLineMap("@@ -0,0 +1,2 @@\n+a\n+b\n")
        assertTrue(nf.isAdded(1) && nf.isAdded(2))
    }

    @Test
    fun mergeBaseIsRequiredForRefs() {
        MrBundle.locale = java.util.Locale.ENGLISH
        val mr = GitHubClient.parsePull(Json.parse("""{"number":5,"base":{"sha":"b"},"head":{"sha":"h"}}""").obj())
        val s = MrSession(ProjectRef("https://github.com", "o/r"), Connection(HostingType.GITHUB, "https://github.com"),
            GitCli(java.io.File(".")), "origin", mr, emptyList(), emptyList(), Reviews.NONE)
        val e = kotlin.runCatching { s.refs }.exceptionOrNull()
        assertTrue(e is ApiException && "merge base" in e.message!!, e.toString())
        s.localBase = "mb"
        assertEquals(DiffRefs("mb", "b", "h"), s.refs)
        assertEquals("mb", s.base)
    }

    @Test
    fun commentsDoNotNeedTheMergeBase() {
        // A new comment's position needs only the refs the server gave: no merge base yet on GitHub / Bitbucket Cloud.
        val mr = GitHubClient.parsePull(Json.parse("""{"number":5,"base":{"sha":"b"},"head":{"sha":"h"}}""").obj())
        val s = MrSession(ProjectRef("https://github.com", "o/r"), Connection(HostingType.GITHUB, "https://github.com"),
            GitCli(java.io.File(".")), "origin", mr, emptyList(), emptyList(), Reviews.NONE)
        assertEquals(DiffRefs(null, "b", "h"), s.diffRefs)
    }

    @Test
    fun draftsOfAnOlderVersionAreNotSent() {
        val m = DiffLineMap("@@ -1 +1 @@\n-a\n+b")
        val old = Draft("1", "old", m.position("b", "s", "h1", "f.go", "f.go", 1, onNewSide = true))
        val now = Draft("2", "now", m.position("b", "s", "h2", "f.go", "f.go", 1, onNewSide = true))
        assertEquals(listOf(now), Drafts.current(listOf(old, now), "h2"))
        assertEquals(listOf(old, now), Drafts.current(listOf(old, now), null))
    }

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

    @Test
    fun relocateOutdatedLines() {
        // Old version → new: line 2 changed, two lines added after line 5.
        val diff = "diff --git a/f.go b/f.go\n--- a/f.go\n+++ b/f.go\n@@ -2 +2 @@\n-old\n+new\n@@ -5,0 +6,2 @@\n+x\n+y\n"
        val m = DiffLineMap(diff)
        assertEquals(1, m.newFor(1)); assertNull(m.newFor(2)); assertEquals(5, m.newFor(5)); assertEquals(9, m.newFor(7))
    }

    @Test
    fun rangesWithinOneHunk() {
        // Hunks: new lines 2..5 / old 2..4, and new 8..9 / old 7..9.
        val m = DiffLineMap("@@ -2,3 +2,4 @@\n two\n-three\n+THREE\n+three-and-half\n four\n@@ -7,3 +8,2 @@\n seven\n-eight\n nine")
        assertTrue(m.withinOneHunk(DiffLineMap.Line(8, true)))
        assertFalse(m.withinOneHunk(DiffLineMap.Line(6, true)))
        assertTrue(m.withinOneHunk(DiffLineMap.Line(4, true), start = DiffLineMap.Line(3, false)))
        // GitHub rejects a range that spans two hunks, though both ends are in the diff.
        assertFalse(m.withinOneHunk(DiffLineMap.Line(9, true), start = DiffLineMap.Line(3, true)))
    }

    @Test
    fun lineRanges() {
        val diff = """
            @@ -2,3 +2,4 @@ func a() {
             two
            -three
            +THREE
            +three-and-half
             four
        """.trimIndent()
        val m = DiffLineMap(diff)
        val sha = DiffLineMap.sha1("f.go")
        assertEquals("f.go".let { java.security.MessageDigest.getInstance("SHA-1").digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }, sha)
        assertEquals(40, sha.length)

        // Counters as in GitLab's parser: "-three" is (3,3), "+THREE" is (4,3), "+three-and-half" (4,4), " four" (4,5)
        assertEquals(LinePoint("${sha}_3_3", "old", 3, null), m.point("f.go", DiffLineMap.Line(3, onNewSide = false)))
        assertEquals(LinePoint("${sha}_4_3", "new", null, 3), m.point("f.go", DiffLineMap.Line(3, onNewSide = true)))
        assertEquals(LinePoint("${sha}_4_4", "new", null, 4), m.point("f.go", DiffLineMap.Line(4, onNewSide = true)))
        assertEquals(LinePoint("${sha}_4_5", null, 4, 5), m.point("f.go", DiffLineMap.Line(5, onNewSide = true)))
        assertEquals(LinePoint("${sha}_2_2", null, 2, 2), m.point("f.go", DiffLineMap.Line(2, onNewSide = false)))
        // outside hunks
        assertEquals(LinePoint("${sha}_9_10", null, 9, 10), m.point("f.go", DiffLineMap.Line(10, onNewSide = true)))

        // Range -3 .. +4 (unified view): position = end line, line_range with both ends
        val p = m.position("b", "s", "h", "f.go", "f.go", end = DiffLineMap.Line(4, true), start = DiffLineMap.Line(3, false))
        assertEquals(null, p.oldLine); assertEquals(4, p.newLine)
        assertEquals("${sha}_3_3", p.lineRange!!.start.lineCode)
        assertEquals("${sha}_4_4", p.lineRange!!.end.lineCode)
        assertTrue(p.isMultiLine)
        assertEquals("-3–+4", p.lineLabel())
        assertEquals("5", m.position("b", "s", "h", "f.go", "f.go", 5, onNewSide = true).lineLabel())
        assertEquals("-3", m.position("b", "s", "h", "f.go", "f.go", 3, onNewSide = false).lineLabel())
        val json = Json.write(p.toJson())
        assertTrue(json.contains(""""line_range":{"start":{"line_code":"${sha}_3_3","type":"old","old_line":3,"new_line":null}"""), json)
        // Single line: no line_range
        assertFalse(m.position("b", "s", "h", "f.go", "f.go", end = DiffLineMap.Line(4, true), start = DiffLineMap.Line(4, true)).toJson().containsKey("line_range"))
        // Range starting on an unchanged line: type null is sent explicitly, as GitLab's web UI does
        val ctxRange = m.position("b", "s", "h", "f.go", "f.go", end = DiffLineMap.Line(4, true), start = DiffLineMap.Line(2, true))
        assertTrue(Json.write(ctxRange.toJson()).contains(""""start":{"line_code":"${sha}_2_2","type":null,"old_line":2,"new_line":2}"""))
        assertEquals("2–+4", ctxRange.lineLabel())

        // Parsing a position with line_range back (shape from the GitLab API docs)
        val parsed = Position.from(Json.parse("""{"position_type":"text","new_line":11,"old_line":11,"new_path":"a","old_path":"a",
            "line_range":{"start":{"line_code":"x_10_10","type":"new","old_line":null,"new_line":10},
                          "end":{"line_code":"x_11_11","type":"old","old_line":11,"new_line":11}}}""").obj())!!
        assertEquals(LinePoint("x_10_10", "new", null, 10), parsed.lineRange!!.start)
        assertTrue(parsed.isMultiLine)
        assertEquals("+10–11", parsed.lineLabel())

        // New file: old counter stays at the hunk start (0)
        val nf = DiffLineMap("@@ -0,0 +1,2 @@\n+a\n+b\n")
        assertEquals("${DiffLineMap.sha1("n.go")}_0_2", nf.point("n.go", DiffLineMap.Line(2, true)).lineCode)
    }

    @Test
    fun suggestions() {
        val one = Suggestion.block(listOf("\treturn nil"))
        assertEquals("```suggestion:-0+0\n\treturn nil\n```", one.text)
        assertEquals("\treturn nil", one.text.substring(one.contentStart, one.contentEnd))

        val three = Suggestion.block(listOf("a", "b", "c"))
        assertTrue(three.text.startsWith("```suggestion:-2+0\n"))
        assertEquals("a\nb\nc", three.text.substring(three.contentStart, three.contentEnd))

        // Content with a ``` fence inside needs a longer fence (as GitLab's repeatCodeBackticks)
        val fenced = Suggestion.block(listOf("// ```go", "x := 1", "// ````"))
        assertTrue(fenced.text.startsWith("`````suggestion:-2+0\n"), fenced.text)
        assertTrue(fenced.text.endsWith("\n`````"))
        assertEquals("````", Suggestion.fence("a ``` b ```` c").dropLast(1))
        // GitHub: the range comes from the comment itself.
        assertEquals("```suggestion\na\nb\n```", Suggestion.block(listOf("a", "b"), gitlab = false).text)
    }

    @Test
    fun timeAgoAndStats() {
        me.brekhin.mrnavigator.util.MrBundle.locale = java.util.Locale.forLanguageTag("ru")
        val now = java.time.Instant.parse("2026-10-01T12:00:00Z")
        val utc = java.time.ZoneId.of("UTC")
        assertEquals("только что", TimeAgo.format("2026-10-01T11:59:30Z", now, utc))
        assertEquals("1 минуту назад", TimeAgo.format("2026-10-01T11:59:00Z", now, utc))
        assertEquals("21 минуту назад", TimeAgo.format("2026-10-01T11:38:00.123Z", now, utc))
        assertEquals("22 минуты назад", TimeAgo.format("2026-10-01T11:38:00Z", now, utc))
        assertEquals("11 часов назад", TimeAgo.format("2026-10-01T04:00:00+03:00", now, utc))
        assertEquals("3 часа назад", TimeAgo.format("2026-10-01T09:00:00Z", now, utc))
        assertEquals("вчера", TimeAgo.format("2026-09-30T08:00:00Z", now, utc))
        assertEquals("5 дней назад", TimeAgo.format("2026-09-26T08:00:00Z", now, utc))
        assertEquals("21 день назад", TimeAgo.format("2026-09-10T08:00:00Z", now, utc))
        assertTrue(TimeAgo.format("2026-06-01T08:00:00Z", now, utc).endsWith("2026"))
        assertEquals("", TimeAgo.format(null, now, utc))

        val c = FileChange("a.go", "a.go", false, false, false, "@@ -1,3 +1,4 @@\n ctx\n-old\n+new\n+new2\n--- not a header, a removed line\n", false)
        assertEquals(2 to 2, c.stats)
    }

    @Test
    fun repoScanner() {
        val tmp = kotlin.io.path.createTempDirectory("repos").toFile()
        try {
            fun repo(path: String, gitFile: Boolean = false) {
                val d = java.io.File(tmp, path).apply { mkdirs() }
                if (gitFile) java.io.File(d, ".git").writeText("gitdir: elsewhere") else java.io.File(d, ".git").mkdirs()
            }
            repo("api")
            repo("services/billing")
            repo("services/billing/sub", gitFile = true) // nested submodule
            repo("node_modules/pkg")                     // skipped
            repo("a/b/c/d/e/too-deep")                   // deeper than 4
            java.io.File(tmp, ".hidden/repo/.git").mkdirs() // hidden dirs skipped
            val names = me.brekhin.mrnavigator.git.RepoScanner.find(tmp).map { me.brekhin.mrnavigator.git.RepoScanner.displayName(tmp, it) }
            assertEquals(listOf("api", "services/billing", "services/billing/sub"), names)

            // Folder inside a repo: the enclosing repo is found upwards
            val inside = java.io.File(tmp, "api/cmd").apply { mkdirs() }
            assertEquals(listOf("api"), me.brekhin.mrnavigator.git.RepoScanner.find(inside).map { it.name })
        } finally {
            tmp.deleteRecursively()
        }
    }

    @Test
    fun json() {
        val v = Json.parse("""{"a":[1,2.5,"x\nЖ"],"b":{"c":null,"d":true},"e":-3}""").obj()
        assertEquals(listOf(1L, 2.5, "x\nЖ"), v["a"])
        assertEquals(-3L, v["e"])
        val back = Json.write(mapOf("body" to "a \"q\"\nb", "n" to 3, "p" to mapOf("x" to null)))
        assertEquals("""{"body":"a \"q\"\nb","n":3,"p":{"x":null}}""", back)
        assertEquals(Json.parse(back), Json.parse(Json.write(Json.parse(back))))
        assertEquals("""{"ids":[7,8]}""", Json.write(mapOf("ids" to listOf(7L, 8L))))
    }

    @Test
    fun noteSuggestions() {
        val n = Note.from(Json.parse("""{"id":1,"suggestions":[{"id":7,"appliable":true,"applied":false},{"id":8,"applied":true}]}""").obj())
        assertEquals(listOf(NoteSuggestion(7, true, false), NoteSuggestion(8, false, true)), n.suggestions)
        assertEquals(emptyList(), Note.from(Json.parse("""{"id":2}""").obj()).suggestions)
    }

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

    @Test
    fun projectWebUrls() {
        // Relative links of a description resolve against the project, not the request page.
        assertEquals("https://git.corp/team/api", MergeRequest.projectUrl("https://git.corp/team/api/-/merge_requests/12"))
        assertEquals("https://github.com/o/r", MergeRequest.projectUrl("https://github.com/o/r/pull/42"))
        assertEquals("https://bitbucket.org/ws/repo", MergeRequest.projectUrl("https://bitbucket.org/ws/repo/pull-requests/7"))
        assertEquals("https://bb.corp/projects/P/repos/r", MergeRequest.projectUrl("https://bb.corp/projects/P/repos/r/pull-requests/12/overview"))
    }

    @Test
    fun descriptionMarkdown() {
        val base = "https://git.corp/team/api"
        fun html(md: String) = Markdown.gfmToHtml(md, base)
        assertTrue("<h2>Title</h2>" in html("## Title"))
        assertTrue("<ol><li>a</li><li>b</li></ol>" in html("1. a\n2. b"))
        assertTrue("<td>1</td>" in html("| x |\n|---|\n| 1 |"))
        assertTrue("☑ done" in html("- [x] done"))
        assertTrue("☐ todo" in html("- [ ] todo"))
        assertTrue("<span style=\"text-decoration: line-through\">old</span>" in html("~~old~~"))
        // Raw HTML from the author is shown as text, never interpreted (Swing would instantiate <object>).
        val raw = html("<object classid=\"javax.swing.JButton\"></object> and <b>x</b>")
        assertFalse("<object" in raw); assertFalse("<b>" in raw)
        assertTrue("&lt;b&gt;" in raw)
        // Images become links; relative URLs (GitLab uploads) are resolved against the project.
        val img = html("![shot](/uploads/ab/s.png)")
        assertFalse("<img" in img)
        assertTrue("<a href=\"$base/uploads/ab/s.png\">shot</a>" in img)
        assertTrue("<a href=\"https://e.com\">e</a>" in html("[e](https://e.com)"))
        assertTrue("<a href=\"#x\">" in html("[x](#x)"))
        // Root-relative links resolve against the host; only GitLab's /uploads/ belong to the project.
        assertTrue("<a href=\"https://git.corp/team/api/-/issues/1\">" in html("[i](/team/api/-/issues/1)"))
        assertTrue("<a href=\"$base/docs/a.md\">" in html("[d](docs/a.md)"))
        // Links come from other people: only web and mail links open, the rest stay plain text.
        assertTrue("<a href=\"mailto:a@b.c\">" in html("[m](mailto:a@b.c)"))
        for (link in listOf("[f](file:///Applications/Calculator.app)", "[s](smb://host/share)", "<vscode://open?x=1>", "[j](javascript:alert(1))")) {
            val out = html(link)
            assertFalse("href=\"file:" in out || "href=\"smb:" in out || "href=\"vscode:" in out || "href=\"javascript:" in out, out)
        }
    }

    @Test
    fun apiErrorMessages() {
        assertEquals("401 Unauthorized", Http.errorMessage("""{"message":"401 Unauthorized"}"""))           // GitLab, GitHub
        assertEquals("insufficient_scope", Http.errorMessage("""{"error":"insufficient_scope"}"""))         // GitLab OAuth
        assertEquals("Bad diff", Http.errorMessage("""{"type":"error","error":{"message":"Bad diff"}}""")) // Bitbucket Cloud
        assertEquals("No such PR", Http.errorMessage("""{"errors":[{"context":null,"message":"No such PR"}]}""")) // Bitbucket DC
        assertEquals("<html>oops</html>", Http.errorMessage("<html>oops</html>"))
        // GitHub 422: the reason is in errors[], as plain strings or {message} objects.
        assertEquals("Validation Failed: Can not approve your own pull request",
            Http.errorMessage("""{"message":"Validation Failed","errors":["Can not approve your own pull request"]}"""))
        assertEquals("Unprocessable Entity: line could not be resolved",
            Http.errorMessage("""{"message":"Unprocessable Entity","errors":[{"resource":"PullRequestReviewComment","field":"line","message":"line could not be resolved"}]}"""))
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
}
