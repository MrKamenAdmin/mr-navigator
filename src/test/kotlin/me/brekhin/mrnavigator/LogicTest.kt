package me.brekhin.mrnavigator

import me.brekhin.mrnavigator.api.FileChange
import me.brekhin.mrnavigator.api.LinePoint
import me.brekhin.mrnavigator.api.Position
import me.brekhin.mrnavigator.core.DiffLineMap
import me.brekhin.mrnavigator.core.HiddenFiles
import me.brekhin.mrnavigator.git.RemoteUrl
import me.brekhin.mrnavigator.util.Json
import me.brekhin.mrnavigator.util.Markdown
import me.brekhin.mrnavigator.util.Suggestion
import me.brekhin.mrnavigator.util.TimeAgo
import me.brekhin.mrnavigator.util.obj
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogicTest {
    @Test
    fun remoteUrls() {
        assertEquals(RemoteUrl("gitlab.com", "group/sub/proj"), RemoteUrl.parse("git@gitlab.com:group/sub/proj.git"))
        assertEquals(RemoteUrl("git.corp.ru", "team/api"), RemoteUrl.parse("https://git.corp.ru/team/api.git"))
        assertEquals(RemoteUrl("git.corp.ru", "team/api"), RemoteUrl.parse("https://user:tok@git.corp.ru:8443/team/api"))
        assertEquals(RemoteUrl("git.corp.ru", "team/api"), RemoteUrl.parse("ssh://git@git.corp.ru:2222/team/api.git"))
        assertNull(RemoteUrl.parse("https://git.corp.ru/onlyone"))
        assertEquals("team/api", RemoteUrl.projectPath(RemoteUrl.parse("https://host/gitlab/team/api.git")!!, "https://host/gitlab/"))
        assertEquals("team/api", RemoteUrl.projectPath(RemoteUrl.parse("git@host:team/api.git")!!, "https://host/gitlab"))
        assertNull(RemoteUrl.projectPath(RemoteUrl.parse("git@other:team/api.git")!!, "https://host"))
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
    }

    @Test
    fun timeAgoAndStats() {
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
    }

    @Test
    fun markdown() {
        assertEquals("a <code>&lt;b&gt;</code> <b>c</b>", Markdown.toHtml("a `<b>` **c**"))
        assertEquals("<ul><li>x</li><li>y</li></ul>", Markdown.toHtml("- x\n- y"))
        assertEquals("<div><i>Suggestion:</i></div><pre><code>foo()</code></pre>", Markdown.toHtml("```suggestion:-0+0\nfoo()\n```"))
        assertEquals("<div><i>Suggestion:</i></div><pre><code>// ```\nx</code></pre>", Markdown.toHtml("````suggestion:-1+0\n// ```\nx\n````"))
    }
}
