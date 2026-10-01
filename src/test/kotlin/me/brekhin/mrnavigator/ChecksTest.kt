package me.brekhin.mrnavigator

import me.brekhin.mrnavigator.api.ApiException
import me.brekhin.mrnavigator.api.Check
import me.brekhin.mrnavigator.api.Checks
import me.brekhin.mrnavigator.api.CiState
import me.brekhin.mrnavigator.api.MergeStrategy
import me.brekhin.mrnavigator.util.Json
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
        // CI only decorates the card: an unreadable answer (an error, an HTML page) hides the line.
        assertEquals(Checks.NONE, Checks.orNone { throw ApiException("down", 502) })
        assertEquals(Checks.NONE, Checks.orNone { Json.parse("<html>sso</html>"); Checks.NONE.copy(state = CiState.SUCCESS) })
    }
}
