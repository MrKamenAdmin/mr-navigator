package me.brekhin.mrnavigator

import com.intellij.diff.util.Side
import me.brekhin.mrnavigator.core.DiffLineMap
import me.brekhin.mrnavigator.diff.LineMapping
import me.brekhin.mrnavigator.diff.threadLines
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CommentMarkersTest {
    /** One side of a side-by-side diff: editor lines are file lines of that side. */
    private class OneSide(private val side: Side) : LineMapping {
        override fun toEditor(side: Side, fileLine: Int) = if (side == this.side) fileLine else null
        override fun fromEditor(editorLine: Int) = side to editorLine
        override fun newSideLine(editorLine: Int) = if (side == Side.RIGHT) editorLine else null
    }

    private val map = DiffLineMap("@@ -2,3 +2,4 @@\n two\n-three\n+THREE\n+three-and-half\n four")

    @Test
    fun threadIconGoesOnTheFirstLineOfARange() {
        val right = OneSide(Side.RIGHT)
        // A range 2..5: the position points at its last line, the icon belongs on the first.
        val range = map.position("b", "s", "h", "f.go", "f.go", end = DiffLineMap.Line(5, true), start = DiffLineMap.Line(2, true))
        assertEquals(1..4, threadLines(range, right))
        assertEquals(3..3, threadLines(map.position("b", "s", "h", "f.go", "f.go", 4, onNewSide = true), right))
        // A removed line is not in the right editor; a range from a removed line starts at its end there.
        assertNull(threadLines(map.position("b", "s", "h", "f.go", "f.go", 3, onNewSide = false), right))
        val fromRemoved = map.position("b", "s", "h", "f.go", "f.go", end = DiffLineMap.Line(4, true), start = DiffLineMap.Line(3, false))
        assertEquals(3..3, threadLines(fromRemoved, right))
        assertEquals(2..2, threadLines(map.position("b", "s", "h", "f.go", "f.go", 3, onNewSide = false), OneSide(Side.LEFT)))
    }
}
