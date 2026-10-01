package me.brekhin.mrnavigator.diff

import com.intellij.diff.util.Side
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import me.brekhin.mrnavigator.api.Discussion
import me.brekhin.mrnavigator.api.Position
import me.brekhin.mrnavigator.core.DiffLineMap
import me.brekhin.mrnavigator.core.MrReviewService
import me.brekhin.mrnavigator.core.MrSession
import me.brekhin.mrnavigator.ui.Notify
import me.brekhin.mrnavigator.ui.ThreadPopup
import me.brekhin.mrnavigator.util.msg
import java.awt.Color
import java.awt.Point
import javax.swing.SwingUtilities
import javax.swing.Icon

/**
 * Converts between editor lines and file lines (0-based) of a diff editor.
 * A side-by-side editor shows one side; the unified editor shows both.
 */
interface LineMapping {
    /** Editor line for [fileLine] of [side], or null if that side is not shown in this editor. */
    fun toEditor(side: Side, fileLine: Int): Int?

    /** Side and file line under [editorLine], or null. */
    fun fromEditor(editorLine: Int): Pair<Side, Int>?

    /** Line of the new version shown at [editorLine] (0-based), or null if it is not there (removed line, old side). */
    fun newSideLine(editorLine: Int): Int?

    /**
     * The left editor of a side-by-side diff (old version only). Unchanged lines there are the same as on
     * the right, so "+" is offered only on removed lines — those can be commented only from this side.
     */
    val isOldSideEditor: Boolean get() = false
}

/**
 * Editor lines a thread covers in the editor of [mapping]: its line, or a range from the first line —
 * where its icon goes (the position itself points at the last line). Null when the line isn't shown there.
 */
internal fun threadLines(p: Position, mapping: LineMapping): IntRange? {
    val end = when {
        p.newLine != null -> mapping.toEditor(Side.RIGHT, p.newLine - 1)
        p.oldLine != null -> mapping.toEditor(Side.LEFT, p.oldLine - 1)
        else -> null
    } ?: return null
    val start = p.lineRange?.start?.let { s ->
        // Prefer the side the range starts on; the other side is a fallback for side-by-side editors.
        listOfNotNull(
            s.newLine?.takeIf { s.type == "new" }?.let { Side.RIGHT to it },
            s.oldLine?.let { Side.LEFT to it },
            s.newLine?.let { Side.RIGHT to it },
        ).firstNotNullOfOrNull { (side, line) -> mapping.toEditor(side, line - 1) }
    }?.takeIf { it in 0..end }
    return (start ?: end)..end
}

/**
 * Comment markers in one editor of an MR diff:
 *  - a gutter icon on every line with a thread (click — open the thread);
 *  - a "+" icon on the hovered line (click — new comment; with several lines selected — a comment on the range);
 *  - while a thread on several lines is open, its lines are highlighted.
 */
class CommentMarkers(
    private val project: Project,
    private val editor: Editor,
    private val ctx: MrFileContext,
    private val mapping: LineMapping,
    parent: Disposable,
) {
    private val threadHighlighters = ArrayList<RangeHighlighter>()
    private var hoverHighlighter: RangeHighlighter? = null
    private var hoverLine = -1
    /** Selected editor lines as seen on the last mouse move — a gutter click must not depend on focus changes. */
    private var lastSelection: IntRange? = null
    private val service = MrReviewService.getInstance(project)

    /**
     * The newest session of the same MR version: after "Refresh" the service holds a new
     * session object, and this diff should follow it rather than the one it was opened with.
     */
    private val session: MrSession
        get() = service.session?.takeIf {
            it.mr.iid == ctx.session.mr.iid && it.mr.diffRefs?.headSha == ctx.session.mr.diffRefs?.headSha
        } ?: ctx.session

    init {
        editor.putUserData(KEY, this)
        editor.addEditorMouseMotionListener(object : EditorMouseMotionListener {
            override fun mouseMoved(e: EditorMouseEvent) = hover(e.logicalPosition.line)
        }, parent)
        editor.addEditorMouseListener(object : EditorMouseListener {
            override fun mouseExited(e: EditorMouseEvent) {
                // The same listener serves the text area and the gutter: moving from one to the other
                // is also an "exit" — hide the "+" only when the mouse really left the editor.
                val me = e.mouseEvent
                val p = SwingUtilities.convertPoint(me.component, me.point, editor.component)
                if (!editor.component.contains(p)) hover(-1)
            }
        }, parent)
        service.addListener(parent) { refresh() }
        refresh()
    }

    /** Re-reads threads of the session and redraws the icons. */
    fun refresh() {
        if (editor.isDisposed) return
        threadHighlighters.forEach { it.dispose() }
        threadHighlighters.clear()
        // The document may have been rebuilt (unified viewer): forget the old "+" too.
        hoverHighlighter?.dispose()
        hoverHighlighter = null
        hoverLine = -1

        val s = session
        val lineCount = editor.document.lineCount
        val byLine = LinkedHashMap<Int, MutableList<Discussion>>()
        for (d in s.threadsFor(ctx.change)) {
            val p = d.position ?: continue
            // Outdated thread (written for an older version of the MR) — its lines no longer match.
            // It is still listed on the Discussion tab.
            if (s.isOutdated(d)) continue
            val lines = threadLines(p, mapping)?.takeIf { it.last < lineCount } ?: continue
            byLine.getOrPut(lines.first) { ArrayList() } += d
        }
        for ((line, threads) in byLine) {
            val h = editor.markupModel.addLineHighlighter(line, HighlighterLayer.LAST, null)
            h.gutterIconRenderer = ThreadsIcon(line, threads)
            threadHighlighters += h
        }
    }

    /** Opens the "new comment" popup for the selected lines or the caret line (context-menu action). */
    fun commentAtCaret() = newComment(selectedLines()?.last ?: editor.caretModel.logicalPosition.line)

    /** Editor lines covered by the selection (a selection ending at column 0 does not include that line). */
    private fun selectedLines(): IntRange? {
        val sel = editor.selectionModel
        if (!sel.hasSelection()) return null
        val doc = editor.document
        val first = doc.getLineNumber(sel.selectionStart)
        var last = doc.getLineNumber(sel.selectionEnd)
        if (last > first && sel.selectionEnd == doc.getLineStartOffset(last)) last--
        return first..last
    }

    private fun hover(line: Int) {
        if (line >= 0) lastSelection = selectedLines()
        if (line == hoverLine || editor.isDisposed) return
        hoverLine = line
        hoverHighlighter?.dispose()
        hoverHighlighter = null
        if (line < 0 || line >= editor.document.lineCount) return
        if (ctx.change.tooLarge) return
        val (side, fileLine) = mapping.fromEditor(line) ?: return
        if (!session.type.commentsOutsideHunks && !session.lineMap(ctx.change).inHunk(fileLine + 1, side == Side.RIGHT)) return
        if (mapping.isOldSideEditor && side == Side.LEFT && !session.lineMap(ctx.change).isRemoved(fileLine + 1)) return
        if (threadHighlighters.any { it.isValid && editor.document.getLineNumber(it.startOffset) == line }) return
        hoverHighlighter = editor.markupModel.addLineHighlighter(line, HighlighterLayer.LAST, null).also {
            it.gutterIconRenderer = AddIcon(line)
        }
    }

    private fun newComment(editorLine: Int) {
        if (ctx.change.tooLarge) {
            Notify.info(project, msg("diff.tooLarge", session.type.title))
            return
        }
        // With several lines selected and the click inside the selection — comment on the whole range.
        val selection = (selectedLines() ?: lastSelection)?.takeIf { editorLine in it && it.first != it.last }
        val endEditorLine = selection?.last ?: editorLine
        val (endSide, endLine) = mapping.fromEditor(endEditorLine) ?: return
        // The first selected line may be a gap (e.g. the unified view header) — take the first mappable one.
        val startEditorLine = selection?.firstOrNull { mapping.fromEditor(it) != null }
        val start = startEditorLine?.let { mapping.fromEditor(it) }

        val s = session
        val refs = s.refs
        val c = ctx.change
        val map = s.lineMap(c)
        val end = DiffLineMap.Line(endLine + 1, endSide == Side.RIGHT)
        val first = start?.let { (side, line) -> DiffLineMap.Line(line + 1, side == Side.RIGHT) }
        if (!s.type.commentsOutsideHunks && !map.withinOneHunk(end, first)) {
            Notify.info(project, msg("diff.outsideHunk", s.type.title))
            return
        }
        val position = map.position(refs.baseSha, refs.startSha, refs.headSha, c.oldPath, c.newPath, end = end, start = first)
        val highlight = startEditorLine?.let { highlightLines(it, endEditorLine) }
        val suggestion = if (position.newLine != null) newSideText(startEditorLine ?: endEditorLine, endEditorLine) else null
        ThreadPopup.showNew(project, s, position, pointUnder(endEditorLine), suggestion) { highlight?.dispose() }
    }

    /**
     * Current text of the new-version lines among editor lines [from]..[to] — the lines a suggestion
     * would replace. Null if there are none or they are not consecutive in the new file.
     */
    private fun newSideText(from: Int, to: Int): List<String>? {
        val doc = editor.document
        if (from < 0 || to >= doc.lineCount || from > to) return null
        val lines = (from..to).mapNotNull { l -> mapping.newSideLine(l)?.let { it to l } }
        if (lines.isEmpty()) return null
        if (lines.zipWithNext().any { (a, b) -> b.first != a.first + 1 }) return null
        return lines.map { (_, l) ->
            doc.charsSequence.subSequence(doc.getLineStartOffset(l), doc.getLineEndOffset(l)).toString()
        }
    }

    private fun highlightLines(from: Int, to: Int): RangeHighlighter? {
        val doc = editor.document
        if (from < 0 || to >= doc.lineCount || from > to) return null
        val attrs = TextAttributes().apply { backgroundColor = RANGE_BACKGROUND }
        return editor.markupModel.addRangeHighlighter(
            doc.getLineStartOffset(from), doc.getLineEndOffset(to),
            HighlighterLayer.SELECTION - 1, attrs, HighlighterTargetArea.LINES_IN_RANGE,
        )
    }

    private fun pointUnder(line: Int): RelativePoint {
        val xy = editor.logicalPositionToXY(LogicalPosition(line, 0))
        return RelativePoint(editor.contentComponent, Point(xy.x, xy.y + editor.lineHeight))
    }

    // ------------------------------------------------------------ renderers

    private inner class ThreadsIcon(private val line: Int, private val threads: List<Discussion>) : GutterIconRenderer() {
        override fun getIcon(): Icon =
            if (threads.all { it.resolved }) AllIcons.General.InspectionsOK else AllIcons.General.Balloon

        override fun getTooltipText(): String = threads.joinToString("<hr>") { d ->
            val first = d.first
            val preview = first?.body?.lineSequence()?.firstOrNull()?.take(120).orEmpty()
            val replies = d.notes.count { !it.system } - 1
            val range = d.position?.takeIf { it.isMultiLine }?.let { " <i>(${msg("diff.lines", it.lineLabel())})</i>" }.orEmpty()
            "<b>${StringUtil.escapeXmlEntities(first?.author?.name ?: "?")}</b>$range: ${StringUtil.escapeXmlEntities(preview)}" +
                (if (replies > 0) " (+$replies)" else "") + (if (d.resolved) " ✓" else "")
        }

        override fun isNavigateAction() = true
        override fun getAlignment() = Alignment.LEFT

        override fun getClickAction(): AnAction = object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) {
                // Several threads on one line: open the first unresolved one.
                val d = threads.firstOrNull { !it.resolved } ?: threads.first()
                val lines = d.position?.let { threadLines(it, mapping) } ?: line..line
                val highlight = if (lines.first != lines.last) highlightLines(lines.first, lines.last) else null
                val suggestion = if (d.position?.newLine != null) newSideText(lines.first, lines.last) else null
                // Under the last line: the highlighted range stays in sight.
                ThreadPopup.showThread(project, session, d, pointUnder(lines.last), suggestion) { highlight?.dispose() }
            }
        }

        override fun equals(other: Any?) = other is ThreadsIcon && other.line == line && other.threads == threads
        override fun hashCode() = line * 31 + threads.hashCode()
    }

    private inner class AddIcon(private val line: Int) : GutterIconRenderer() {
        override fun getIcon(): Icon = AllIcons.General.Add
        override fun getTooltipText() =
            if ((selectedLines() ?: lastSelection)?.let { line in it && it.first != it.last } == true) msg("diff.commentRange", session.type.title)
            else msg("diff.commentLine", session.type.title)
        override fun isNavigateAction() = true
        override fun getAlignment() = Alignment.LEFT

        override fun getClickAction(): AnAction = object : DumbAwareAction() {
            override fun actionPerformed(e: AnActionEvent) = newComment(line)
        }

        override fun equals(other: Any?) = other is AddIcon && other.line == line
        override fun hashCode() = line
    }

    companion object {
        val KEY: Key<CommentMarkers> = Key.create("me.brekhin.mrnavigator.commentMarkers")
        private val RANGE_BACKGROUND = JBColor(Color(0xFF, 0xF3, 0xC4), Color(0x4B, 0x45, 0x2A))
    }
}
