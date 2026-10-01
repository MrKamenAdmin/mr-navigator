package me.brekhin.mrnavigator.core

import me.brekhin.mrnavigator.api.LinePoint
import me.brekhin.mrnavigator.api.LineRange
import me.brekhin.mrnavigator.api.Position
import java.security.MessageDigest

/**
 * Maps lines between the old and the new version of a file using the hunks of its unified diff,
 * and builds GitLab comment positions from them.
 *
 * All line numbers here are 1-based, as in GitLab.
 *
 * The old/new counters of added and removed lines follow GitLab's own diff parser
 * (lib/gitlab/diff/parser.rb): both counters start at the hunk header values, an added line
 * advances only the new counter, a removed line only the old one. They form line codes.
 */
class DiffLineMap(diff: String) {
    private class Hunk(val oldStart: Int, val oldCount: Int, val newStart: Int, val newCount: Int)

    private val hunks = ArrayList<Hunk>()
    private val added = HashSet<Int>()          // new-side lines
    private val removed = HashSet<Int>()        // old-side lines
    private val newToOld = HashMap<Int, Int>()  // context lines inside hunks
    private val oldToNew = HashMap<Int, Int>()
    private val addedOldCounter = HashMap<Int, Int>()   // added new line → old counter at that point
    private val removedNewCounter = HashMap<Int, Int>() // removed old line → new counter at that point

    init {
        var oldLine = 0
        var newLine = 0
        var inHunk = false
        for (raw in diff.split('\n')) {
            val line = raw.removeSuffix("\r")
            val m = HUNK.find(line)
            if (m != null && line.startsWith("@@")) {
                val (os, oc, ns, nc) = m.destructured
                val hunk = Hunk(os.toInt(), oc.ifEmpty { "1" }.toInt(), ns.toInt(), nc.ifEmpty { "1" }.toInt())
                hunks += hunk
                oldLine = hunk.oldStart
                newLine = hunk.newStart
                inHunk = true
                continue
            }
            if (!inHunk || line.isEmpty() && raw.isEmpty()) continue
            when (line.firstOrNull()) {
                '+' -> {
                    added += newLine
                    addedOldCounter[newLine] = oldLine
                    newLine++
                }
                '-' -> {
                    removed += oldLine
                    removedNewCounter[oldLine] = newLine
                    oldLine++
                }
                ' ' -> {
                    newToOld[newLine] = oldLine
                    oldToNew[oldLine] = newLine
                    newLine++
                    oldLine++
                }
                '\\' -> Unit // "\ No newline at end of file"
                else -> inHunk = false
            }
        }
    }

    fun isAdded(newLine: Int) = newLine in added
    fun isRemoved(oldLine: Int) = oldLine in removed

    /** Whether [line] lies in a hunk — GitHub and Bitbucket accept comments only on such lines. */
    fun inHunk(line: Int, onNewSide: Boolean): Boolean = hunkOf(line, onNewSide) != null

    /** A comment GitHub and Bitbucket accept: on a line of a hunk, a range — within one hunk. */
    fun withinOneHunk(end: Line, start: Line? = null): Boolean {
        val hunk = hunkOf(end.line, end.onNewSide) ?: return false
        return start == null || hunkOf(start.line, start.onNewSide) == hunk
    }

    private fun hunkOf(line: Int, onNewSide: Boolean): Hunk? = hunks.firstOrNull { h ->
        if (onNewSide) line >= h.newStart && line < h.newStart + h.newCount
        else line >= h.oldStart && line < h.oldStart + h.oldCount
    }

    /** Old line for a new-side line that is not added (context or untouched). */
    fun oldFor(newLine: Int): Int? {
        if (newLine in added) return null
        newToOld[newLine]?.let { return it }
        // Outside hunks: shift by the size difference of all hunks above.
        var delta = 0
        for (h in hunks) {
            if (end(h.newStart, h.newCount) <= newLine) delta += h.newCount - h.oldCount else break
        }
        return newLine - delta
    }

    /** New line for an old-side line that is not removed. */
    fun newFor(oldLine: Int): Int? {
        if (oldLine in removed) return null
        oldToNew[oldLine]?.let { return it }
        var delta = 0
        for (h in hunks) {
            if (end(h.oldStart, h.oldCount) <= oldLine) delta += h.newCount - h.oldCount else break
        }
        return oldLine + delta
    }

    // First line after the hunk on one side. For an empty side ("-5,0") start is the line *before* the change.
    private fun end(start: Int, count: Int) = if (count == 0) start + 1 else start + count

    /** A line of the new ([onNewSide]) or old version of the file. */
    data class Line(val line: Int, val onNewSide: Boolean)

    /** old_line / new_line of a comment on [l]: an added line has no old line, a removed one no new line. */
    private fun lines(l: Line): Pair<Int?, Int?> =
        if (l.onNewSide) oldFor(l.line) to l.line else l.line to newFor(l.line)

    /** Range end point in GitLab's format; [filePath] is new_path (old_path for deleted files). */
    fun point(filePath: String, l: Line): LinePoint {
        val (oldLine, newLine) = lines(l)
        val oldCounter = oldLine ?: addedOldCounter[l.line] ?: 0
        val newCounter = newLine ?: removedNewCounter[l.line] ?: 0
        val type = when {
            l.onNewSide && isAdded(l.line) -> "new"
            !l.onNewSide && isRemoved(l.line) -> "old"
            else -> null
        }
        return LinePoint("${sha1(filePath)}_${oldCounter}_$newCounter", type, oldLine, newLine)
    }

    /**
     * Position of a comment on [end], or on the range [start]..[end] when [start] is given
     * (as in GitLab, the position itself points at the last line of the range).
     */
    fun position(
        baseSha: String, startSha: String, headSha: String,
        oldPath: String, newPath: String,
        end: Line, start: Line? = null,
    ): Position {
        val (oldLine, newLine) = lines(end)
        val range = if (start != null && start != end) {
            val filePath = newPath.ifEmpty { oldPath }
            LineRange(point(filePath, start), point(filePath, end))
        } else null
        return Position(baseSha, startSha, headSha, oldPath, newPath, oldLine, newLine, range)
    }

    /** Single-line shortcut. */
    fun position(
        baseSha: String, startSha: String, headSha: String,
        oldPath: String, newPath: String,
        line: Int, onNewSide: Boolean,
    ): Position = position(baseSha, startSha, headSha, oldPath, newPath, Line(line, onNewSide))

    companion object {
        private val HUNK = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")

        fun sha1(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
