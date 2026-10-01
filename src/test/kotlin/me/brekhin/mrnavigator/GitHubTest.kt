package me.brekhin.mrnavigator

import me.brekhin.mrnavigator.api.ApiException
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
    fun mergeBase() {
        val repo = "/repos/o/r"
        assertEquals("mb", GitHubClient.mergeBase(repo, "b1", "h1") { path ->
            assertEquals("$repo/compare/b1...h1?per_page=1", path)
            obj("""{"merge_base_commit":{"sha":"mb"}}""")
        })
        // A failed compare leaves the refs unknown instead of failing the whole pull request.
        assertNull(GitHubClient.mergeBase(repo, "b1", "h1") { throw ApiException("GitHub: error 500", 500) })
        assertNull(GitHubClient.mergeBase(repo, null, "h1") { error("not called") })
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
            "diffSide":"LEFT","comments":{"nodes":[{"databaseId":9,"body":"old"}]}}"""), "h1")
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
