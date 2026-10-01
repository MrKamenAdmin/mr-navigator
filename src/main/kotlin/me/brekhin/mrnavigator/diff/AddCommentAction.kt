package me.brekhin.mrnavigator.diff

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.DumbAwareAction
import me.brekhin.mrnavigator.util.msg

/** "Comment on line" in the context menu of MR diff editors. */
class AddCommentAction : DumbAwareAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor?.getUserData(CommentMarkers.KEY) != null
        e.presentation.text = msg("action.addComment")
    }

    override fun actionPerformed(e: AnActionEvent) {
        e.getData(CommonDataKeys.EDITOR)?.getUserData(CommentMarkers.KEY)?.commentAtCaret()
    }
}
