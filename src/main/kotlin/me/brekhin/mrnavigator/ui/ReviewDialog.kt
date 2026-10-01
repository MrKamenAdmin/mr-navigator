package me.brekhin.mrnavigator.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBRadioButton
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.dsl.builder.Align
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.panel
import me.brekhin.mrnavigator.api.Draft
import me.brekhin.mrnavigator.api.Verdict
import me.brekhin.mrnavigator.core.MrReviewService
import me.brekhin.mrnavigator.core.MrSession
import me.brekhin.mrnavigator.util.Markdown
import me.brekhin.mrnavigator.util.msg
import java.awt.event.ActionEvent
import javax.swing.Action
import javax.swing.ButtonGroup
import javax.swing.JComponent

/** Submits a review: the drafts, a summary and a verdict. */
class ReviewDialog(project: Project, private val session: MrSession, private val drafts: List<Draft>, verdict: Verdict) :
    DialogWrapper(project, true) {
    private val service = MrReviewService.getInstance(project)
    private val summaryArea = JBTextArea(5, 60).apply { lineWrap = true; wrapStyleWord = true }
    private val comment = JBRadioButton(msg("review.comment"), verdict == Verdict.COMMENT)
    private val approve = JBRadioButton(msg("review.approve"), verdict == Verdict.APPROVE)
    private val requestChanges = JBRadioButton(msg("review.requestChanges"), verdict == Verdict.REQUEST_CHANGES)

    val verdict: Verdict
        get() = when {
            approve.isSelected -> Verdict.APPROVE
            requestChanges.isSelected -> Verdict.REQUEST_CHANGES
            else -> Verdict.COMMENT
        }
    val summary: String get() = summaryArea.text.trim()

    init {
        title = msg("review.title", session.ref)
        ButtonGroup().apply { add(comment); add(approve); add(requestChanges) }
        setOKButtonText(msg("review.submit"))
        init()
    }

    override fun createCenterPanel(): JComponent = panel {
        if (drafts.isNotEmpty()) {
            row { label(msg("review.drafts", drafts.size)) }
            val head = session.mr.diffRefs?.headSha
            for (d in drafts) {
                val p = d.position
                val where = "${(p.newPath ?: p.oldPath)?.substringAfterLast('/')}:${p.lineLabel()}"
                // Written for an older version: its line may hold other code now, so it isn't sent.
                val outdated = if (p.isOutdatedFor(head)) " <i>(${msg("review.outdated")})</i>" else ""
                lateinit var line: Row
                line = row {
                    comment("$where — ${Markdown.escape(d.body.lineSequence().first().take(80))}$outdated")
                    link(msg("review.remove")) {
                        service.removeDraft(session, d.id)
                        line.visible(false)
                    }
                }
            }
        }
        row { label(msg("review.summary")) }
        row { scrollCell(summaryArea).align(Align.FILL) }.resizableRow()
        row { cell(comment); cell(approve); cell(requestChanges) }
    }

    override fun createLeftSideActions(): Array<Action> =
        if (drafts.isEmpty()) emptyArray() else arrayOf(object : DialogWrapperAction(msg("review.discard")) {
            override fun doAction(e: ActionEvent) {
                if (Messages.showYesNoDialog(contentPanel, msg("review.discardConfirm", drafts.size), msg("review.discard"), null) != Messages.YES) return
                drafts.forEach { service.removeDraft(session, it.id) }
                close(CANCEL_EXIT_CODE)
            }
        })

    override fun doValidate(): ValidationInfo? =
        if (verdict == Verdict.REQUEST_CHANGES && summary.isEmpty()) ValidationInfo(msg("review.summaryRequired"), summaryArea) else null

    override fun getPreferredFocusedComponent(): JComponent = summaryArea
}
