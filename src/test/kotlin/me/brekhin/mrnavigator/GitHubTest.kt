package me.brekhin.mrnavigator

import me.brekhin.mrnavigator.api.ApiException
import me.brekhin.mrnavigator.api.CiState
import me.brekhin.mrnavigator.api.DiffRefs
import me.brekhin.mrnavigator.api.Draft
import me.brekhin.mrnavigator.api.GitHubClient
import me.brekhin.mrnavigator.api.HostingType
import me.brekhin.mrnavigator.api.LinePoint
import me.brekhin.mrnavigator.api.Reviews
import me.brekhin.mrnavigator.api.Verdict
import me.brekhin.mrnavigator.core.DiffLineMap
import me.brekhin.mrnavigator.util.Json
import me.brekhin.mrnavigator.util.msg
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
        val mr = GitHubClient.parsePull(m)
        assertEquals(42L, mr.iid)
        assertEquals("feature", mr.sourceBranch); assertEquals("main", mr.targetBranch)
        // The merge base is computed by git after fetching.
        assertEquals(DiffRefs(null, "b1", "h1"), mr.diffRefs); assertEquals("h1", mr.sha)
        assertEquals("refs/pull/42/head", mr.fetchRef); assertNull(mr.fetchUrl)
        assertEquals(5, mr.userNotesCount)
        assertTrue(mr.draft); assertTrue(mr.hasConflicts); assertEquals("open", mr.state)
        assertEquals("alice", mr.author?.username)
        assertNull(GitHubClient.parsePull(obj("""{"number":1,"base":{"sha":"b"}}""")).diffRefs)
        assertEquals("merged", GitHubClient.parsePull(obj("""{"number":1,"state":"closed","merged_at":"2026-09-30T10:00:00Z"}""")).state)
    }

    @Test
    fun commentPaths() {
        val review = GitHubClient.parseThread(obj("""{"id":"T","comments":{"nodes":[{"databaseId":7}]}}"""), "h")
        val general = GitHubClient.parseIssueComment(obj("""{"id":5}"""))
        assertEquals("/repos/o/r/pulls/comments", GitHubClient.commentsPath("/repos/o/r", review))
        assertEquals("/repos/o/r/issues/comments", GitHubClient.commentsPath("/repos/o/r", general))
    }

    @Test
    fun reviewPayloads() {
        val draft = Draft("1", "fix", map.position("b", "s", "h", "f.go", "f.go", 3, onNewSide = true))
        assertEquals(
            mapOf("commit_id" to "h", "event" to "COMMENT", "comments" to listOf(mapOf("body" to "fix", "path" to "f.go", "line" to 3, "side" to "RIGHT"))),
            GitHubClient.reviewPayload(listOf(draft), Verdict.COMMENT, " ", "h"),
        )
        assertEquals(mapOf("commit_id" to "h", "event" to "REQUEST_CHANGES", "body" to "why"), GitHubClient.reviewPayload(emptyList(), Verdict.REQUEST_CHANGES, "why", "h"))
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
            "diffSide":"LEFT","comments":{"nodes":[{"databaseId":9,"body":"old","originalCommit":{"oid":"old1"}}]}}"""), "h1")
        assertEquals(5, old.position!!.oldLine); assertTrue(old.position!!.isOutdatedFor("h1"))
        // An outdated thread keeps the commit it was written on: the gutter maps its line from there.
        assertEquals("old1", old.position!!.headSha)
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
        assertEquals(Reviews(approved = listOf("b"), changesRequested = listOf("a")), GitHubClient.reviewStates(reviews))
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
        assertEquals(listOf("squash", "rebase"), o.strategies.map { it.id }); assertEquals(msg("merge.conflicts"), o.blocker); assertTrue(o.canDeleteBranch)
        fun blocker(state: String) = GitHubClient.mergeOptions(obj("""{"mergeable":true,"mergeable_state":"$state"}"""), obj("{}")).blocker
        assertEquals(msg("merge.protected"), blocker("blocked")); assertEquals(msg("merge.draft"), blocker("draft"))
        assertNull(blocker("unstable")); assertNull(blocker("clean"))
        // Without push access GitHub hides the allow_* flags: offer all, the server decides.
        val fork = obj("""{"mergeable":true,"head":{"repo":{"full_name":"me/r"}},"base":{"repo":{"full_name":"o/r"}}}""")
        val f = GitHubClient.mergeOptions(fork, obj("{}"))
        assertEquals(listOf("merge", "squash", "rebase"), f.strategies.map { it.id }); assertFalse(f.canDeleteBranch); assertNull(f.blocker)
        // The repository deletes merged branches itself: deleting it again would fail a successful merge.
        assertFalse(GitHubClient.mergeOptions(pr, obj("""{"delete_branch_on_merge":true}""")).canDeleteBranch)
    }
}
