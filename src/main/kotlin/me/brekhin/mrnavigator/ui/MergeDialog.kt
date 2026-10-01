package me.brekhin.mrnavigator.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.UIUtil
import me.brekhin.mrnavigator.api.MergeOptions
import me.brekhin.mrnavigator.api.MergeStrategy
import me.brekhin.mrnavigator.core.MrSession
import me.brekhin.mrnavigator.util.msg
import javax.swing.JComponent

/** Merge: the server's reason it can't be merged now, the strategy and whether to delete the source branch. */
class MergeDialog(project: Project, session: MrSession, private val options: MergeOptions) : DialogWrapper(project, true) {
    private val strategyCombo = ComboBox(options.strategies.toTypedArray()).apply {
        options.strategies.firstOrNull { it.id == options.defaultStrategy }?.let { selectedItem = it }
    }
    private val deleteBranchBox = JBCheckBox(msg("merge.deleteBranch"), options.deleteBranch)

    val strategy: String? get() = (strategyCombo.selectedItem as? MergeStrategy)?.id
    val deleteBranch: Boolean get() = options.canDeleteBranch && deleteBranchBox.isSelected

    init {
        title = msg("merge.title", session.ref, session.mr.targetBranch)
        setOKButtonText(msg("merge.ok"))
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        options.blocker?.let { b ->
            row { label(msg("merge.blocked", b)).applyToComponent { foreground = UIUtil.getErrorForeground() } }
        }
        if (options.strategies.isNotEmpty()) row(msg("merge.strategy")) { cell(strategyCombo) }
        if (options.canDeleteBranch) row { cell(deleteBranchBox) }
    }
}
