package me.brekhin.mrnavigator

import me.brekhin.mrnavigator.api.ApiException
import me.brekhin.mrnavigator.api.CiState
import me.brekhin.mrnavigator.api.Draft
import me.brekhin.mrnavigator.api.GitLabClient
import me.brekhin.mrnavigator.core.DiffLineMap
import me.brekhin.mrnavigator.util.Json
import me.brekhin.mrnavigator.util.obj
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun ciAndMerge() {
        val p = GitLabClient.pipeline(obj("""{"id":5,"status":"running","web_url":"https://g/p/5"}"""))
        assertEquals(CiState.RUNNING, p.state); assertEquals("https://g/p/5", p.url)
        assertEquals(CiState.FAILED, GitLabClient.pipeline(obj("""{"status":"failed"}""")).state)
        assertEquals(CiState.NONE, GitLabClient.pipeline(obj("""{"status":"skipped"}""")).state)
        assertEquals(CiState.MANUAL, GitLabClient.pipeline(obj("""{"status":"manual"}""")).state)
        // Jobs in the order they were created; the pipeline's own status wins (a job may be allowed to fail).
        val jobs = listOf("""{"id":3,"name":"deploy","stage":"deploy","status":"manual","web_url":"j3"}""",
            """{"id":1,"name":"build","stage":"build","status":"success","web_url":"j1"}""",
            """{"id":2,"name":"lint","stage":"test","status":"failed","allow_failure":true,"web_url":"j2"}""").map { obj(it) }
        val withJobs = GitLabClient.pipeline(obj("""{"id":5,"status":"success","web_url":"https://g/p/5"}"""), jobs)
        assertEquals(CiState.SUCCESS, withJobs.state); assertEquals("https://g/p/5", withJobs.url)
        assertEquals(listOf("build · build", "test · lint", "deploy · deploy"), withJobs.items.map { it.name })
        assertEquals(listOf(CiState.SUCCESS, CiState.FAILED, CiState.MANUAL), withJobs.items.map { it.state })
        assertEquals("j2", withJobs.items[1].url)
        val o = GitLabClient.mergeOptions(obj("""{"detailed_merge_status":"not_approved"}"""), obj("""{"squash_option":"default_on"}"""))
        assertEquals(listOf("merge", "squash"), o.strategies.map { it.id }); assertEquals("squash", o.defaultStrategy)
        assertEquals("not approved", o.blocker); assertTrue(o.canDeleteBranch)
        val ok = GitLabClient.mergeOptions(obj("""{"detailed_merge_status":"mergeable"}"""), obj("""{"squash_option":"never"}"""))
        assertNull(ok.blocker); assertEquals(listOf("merge"), ok.strategies.map { it.id }); assertFalse(ok.deleteBranch)
        // GitLab is still computing the status: the merge itself will tell.
        for (status in listOf("checking", "unchecked", "preparing", "approvals_syncing"))
            assertNull(GitLabClient.mergeOptions(obj("""{"detailed_merge_status":"$status"}"""), obj("{}")).blocker, status)
        assertTrue(GitLabClient.mergeOptions(obj("""{"force_remove_source_branch":true}"""), obj("{}")).deleteBranch)
    }

    @Test
    fun failedReviewLeavesNoServerDrafts() {
        val p = DiffLineMap("@@ -1 +1 @@\n-a\n+b").position("b", "s", "h", "f.go", "f.go", 1, onNewSide = true)
        val drafts = listOf(Draft("1", "x", p), Draft("2", "y", p))
        val calls = ArrayList<String>()
        var published: List<Draft>? = null
        var next = 10L
        val failing = { method: String, path: String, _: Any? ->
            calls += "$method $path"
            if (path.endsWith("bulk_publish")) throw ApiException("boom", 500)
            mapOf("id" to next++)
        }
        assertFailsWith<ApiException> { GitLabClient.publishDrafts("/mr", drafts, "sum", { published = it }, failing) }
        // Nothing got published, so this attempt's server drafts are removed — a retry won't send them twice.
        assertNull(published)
        assertEquals(listOf("DELETE /mr/draft_notes/10", "DELETE /mr/draft_notes/11", "DELETE /mr/draft_notes/12"), calls.filter { it.startsWith("DELETE") })
        calls.clear()
        GitLabClient.publishDrafts("/mr", drafts, "", { published = it }) { method, path, _ -> calls += "$method $path"; mapOf("id" to 1L) }
        assertEquals(drafts, published)
        assertEquals(listOf("POST /mr/draft_notes", "POST /mr/draft_notes", "POST /mr/draft_notes/bulk_publish"), calls)
    }
}
