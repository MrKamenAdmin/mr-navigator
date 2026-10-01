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
