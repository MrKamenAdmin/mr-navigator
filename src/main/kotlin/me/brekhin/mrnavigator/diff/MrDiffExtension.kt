package me.brekhin.mrnavigator.diff

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.tools.util.base.DiffViewerBase
import com.intellij.diff.tools.util.base.DiffViewerListener
import com.intellij.diff.tools.util.side.OnesideTextDiffViewer
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.diff.util.Side

/** Adds GitLab comment markers to diff viewers opened by [MrDiffOpener]. */
class MrDiffExtension : DiffExtension() {
    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
        val ctx = request.getUserData(MrDiffOpener.CONTEXT_KEY) ?: return
        val project = context.project ?: return

        when (viewer) {
            is TwosideTextDiffViewer -> {
                for (side in Side.entries) {
                    val editor = viewer.getEditor(side)
                    CommentMarkers(project, editor, ctx, SideMapping(side), viewer)
                }
            }
            // New or deleted file: only one side is shown.
            is OnesideTextDiffViewer -> CommentMarkers(project, viewer.editor, ctx, SideMapping(viewer.side), viewer)
            is UnifiedDiffViewer -> {
                val markers = CommentMarkers(project, viewer.editor, ctx, UnifiedMapping(viewer), viewer)
                // The unified document is rebuilt after every rediff — redraw the markers then.
                (viewer as DiffViewerBase).addListener(object : DiffViewerListener() {
                    override fun onAfterRediff() = markers.refresh()
                })
            }
        }
    }

    private class SideMapping(private val side: Side) : LineMapping {
        override fun toEditor(side: Side, fileLine: Int) = if (side == this.side) fileLine else null
        override fun fromEditor(editorLine: Int) = side to editorLine
        override fun newSideLine(editorLine: Int) = if (side == Side.RIGHT) editorLine else null
        override val isOldSideEditor: Boolean get() = side == Side.LEFT
    }

    private class UnifiedMapping(private val viewer: UnifiedDiffViewer) : LineMapping {
        override fun toEditor(side: Side, fileLine: Int): Int? =
            viewer.transferLineToOnesideStrict(side, fileLine).takeIf { it >= 0 }

        override fun fromEditor(editorLine: Int): Pair<Side, Int>? {
            val pair = viewer.transferLineFromOnesideStrict(editorLine) ?: return null
            val side = pair.second
            val line = side.select(pair.first)
            return if (line >= 0) side to line else null
        }

        override fun newSideLine(editorLine: Int): Int? {
            val pair = viewer.transferLineFromOnesideStrict(editorLine) ?: return null
            val right = Side.RIGHT.select(pair.first)
            // For a removed line the viewer fills the right number approximately even in strict mode,
            // so check that this right line really is shown at editorLine.
            return right.takeIf { it >= 0 && viewer.transferLineToOnesideStrict(Side.RIGHT, it) == editorLine }
        }
    }
}
