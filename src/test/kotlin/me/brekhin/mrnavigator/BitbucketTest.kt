package me.brekhin.mrnavigator

import me.brekhin.mrnavigator.api.BitbucketCloudClient
import me.brekhin.mrnavigator.api.BitbucketServerClient
import me.brekhin.mrnavigator.api.Connection
import me.brekhin.mrnavigator.api.DiffRefs
import me.brekhin.mrnavigator.api.HostingType
import me.brekhin.mrnavigator.api.LinePoint
import me.brekhin.mrnavigator.core.DiffLineMap
import me.brekhin.mrnavigator.git.RemoteUrl
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
}
