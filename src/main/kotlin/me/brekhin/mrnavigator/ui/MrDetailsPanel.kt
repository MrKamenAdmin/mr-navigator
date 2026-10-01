package me.brekhin.mrnavigator.ui

import com.intellij.icons.AllIcons
import javax.swing.ListCellRenderer
import java.awt.event.KeyEvent
import java.awt.event.KeyAdapter
import java.awt.Component
import me.brekhin.mrnavigator.util.TimeAgo
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.JBColor
import com.intellij.ui.ColorUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.diff.util.Side
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.HTMLEditorKitBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.intellij.util.ui.WrapLayout
import me.brekhin.mrnavigator.api.Discussion
import me.brekhin.mrnavigator.api.FileChange
import me.brekhin.mrnavigator.api.MergeRequest
import me.brekhin.mrnavigator.core.CheckoutState
import me.brekhin.mrnavigator.core.MrReviewService
import me.brekhin.mrnavigator.core.MrSession
import me.brekhin.mrnavigator.diff.MrDiffOpener
import me.brekhin.mrnavigator.settings.MrReviewSettings
import me.brekhin.mrnavigator.util.Markdown
import me.brekhin.mrnavigator.util.MrBundle
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JEditorPane
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.event.HyperlinkEvent
import me.brekhin.mrnavigator.util.msg
import me.brekhin.mrnavigator.api.HostingType

/** Details of the selected MR: actions, files, discussion, description. */
class MrDetailsPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {
    private val service = MrReviewService.getInstance(project)

    private val title = JBLabel().apply { font = JBUI.Fonts.label().biggerOn(2f).asBold() }
    private val meta = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val checkoutState = JBLabel()

    private val checkoutButton = JButton(msg("details.checkout"), AllIcons.Actions.CheckOut)
    private val backButton = JButton(msg("details.back"), AllIcons.Actions.Back)
    private val approveButton = JButton("Approve")
    private val browserButton = JButton(AllIcons.General.Web).apply { toolTipText = msg("openInBrowser") }
    private val refreshButton = JButton(AllIcons.Actions.Refresh)

    private val tree = ChangesTree(
        onOpen = { change, files -> openDiff(change, files) },
        onToggleViewed = { change -> session?.let { s -> toggleViewed(s, change) } },
    )
    private val showHidden = JBCheckBox()
    private val filesSummary = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }

    private val threadsModel = DefaultListModel<Discussion>()
    private val threads = JBList(threadsModel)
    private val newCommentButton = JButton(msg("details.newComment"), AllIcons.General.Add)
    private val hideResolved = JBCheckBox(msg("details.hideResolved"))

    private val description = JEditorPane().apply {
        editorKit = HTMLEditorKitBuilder.simple()
        isEditable = false
        addHyperlinkListener { if (it.eventType == HyperlinkEvent.EventType.ACTIVATED) it.url?.let { url -> BrowserUtil.browse(url) } }
    }
    private val tabs = JBTabbedPane()

    private val placeholder = JBLabel(msg("details.placeholder"), SwingConstants.CENTER).apply {
        foreground = UIUtil.getContextHelpForeground()
    }
    private val content = JPanel(BorderLayout())

    private var session: MrSession? = null
    private var state: CheckoutState? = null
    private val checkedOut: Boolean get() = state?.onMr == true
    /** iid being loaded right now; results of older loads are dropped. */
    private var loadingIid: Long? = null

    init {
        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8, 8, 4, 8)
            add(title); add(meta); add(checkoutState)
            // WrapLayout moves buttons to the next row when the tool window is narrow (FlowLayout would clip them).
            add(JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(4), JBUI.scale(4))).apply {
                add(checkoutButton); add(backButton); add(approveButton); add(browserButton); add(refreshButton)
                alignmentX = LEFT_ALIGNMENT
            })
            listOf(title, meta, checkoutState).forEach { it.alignmentX = LEFT_ALIGNMENT }
        }

        tree.isViewed = { change -> session?.viewed?.contains(change.displayPath) == true }
        val filesTab = JPanel(BorderLayout()).apply {
            add(JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(2))).apply {
                add(showHidden); add(filesSummary)
            }, BorderLayout.NORTH)
            add(JBScrollPane(tree).apply { border = JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0) }, BorderLayout.CENTER)
            add(hint(msg("details.filesHint")), BorderLayout.SOUTH)
        }

        threads.setCellRenderer(ThreadCellRenderer())
        threads.emptyText.text = msg("details.threadsEmpty")
        threads.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1) openSelectedThread()
            }
        })
        threads.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) openSelectedThread()
            }
        })
        val threadsTab = JPanel(BorderLayout()).apply {
            add(JPanel(WrapLayout(FlowLayout.LEFT, JBUI.scale(8), JBUI.scale(2))).apply {
                add(newCommentButton); add(hideResolved)
            }, BorderLayout.NORTH)
            add(JBScrollPane(threads).apply { border = JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0) }, BorderLayout.CENTER)
            add(hint(msg("details.threadsHint")), BorderLayout.SOUTH)
        }
        tabs.addTab(msg("details.tab.files", 0), filesTab)
        tabs.addTab(msg("details.tab.discussion", 0), threadsTab)
        tabs.addTab(msg("details.tab.description"), JBScrollPane(description))

        content.add(header, BorderLayout.NORTH)
        content.add(tabs, BorderLayout.CENTER)
        add(placeholder, BorderLayout.CENTER)

        showHidden.addActionListener { renderFiles() }
        hideResolved.addActionListener { renderThreads() }
        checkoutButton.addActionListener { checkout() }
        backButton.addActionListener { goBack() }
        approveButton.addActionListener { toggleApprove() }
        browserButton.addActionListener { session?.let { BrowserUtil.browse(it.mr.webUrl) } }
        refreshButton.addActionListener { session?.let { load(it.mr, it.type) } }
        newCommentButton.addActionListener {
            val s = session ?: return@addActionListener
            ThreadPopup.showNew(project, s, null, RelativePoint(newCommentButton, Point(0, newCommentButton.height)))
        }

        service.addListener(parent) { onServiceChanged() }
    }

    fun load(mr: MergeRequest, type: HostingType) {
        loadingIid = mr.iid
        val ref = "${type.prefix}${mr.iid}"
        removeAll()
        add(JBLabel(msg("details.loading", ref), SwingConstants.CENTER), BorderLayout.CENTER)
        revalidate(); repaint()
        Bg.run(project, msg("details.loading", ref), work = {
            val s = service.loadSession(mr)
            s to service.checkoutState(s)
        }, onError = {
            if (loadingIid == mr.iid) showMessage(msg("details.loadFailed", ref, it.message))
        }) { (s, co) ->
            if (loadingIid != mr.iid) return@run
            loadingIid = null
            session = s
            state = co
            removeAll()
            add(content, BorderLayout.CENTER)
            renderFiles()
            render()
        }
    }

    fun clear() {
        session = null
        loadingIid = null
        removeAll(); add(placeholder, BorderLayout.CENTER); revalidate(); repaint()
    }

    private fun showMessage(text: String) {
        removeAll()
        add(JBLabel("<html><div style='padding:8px'>${Markdown.escape(text)}</div></html>", SwingConstants.CENTER), BorderLayout.CENTER)
        revalidate(); repaint()
    }

    private fun onServiceChanged() {
        val s = session ?: return
        if (service.session !== s && service.session?.mr?.iid == s.mr.iid) session = service.session
        render()
    }

    private fun render() {
        val s = session ?: return
        val mr = s.mr
        title.text = "<html>${if (mr.draft) "<span style='color:gray'>Draft:</span> " else ""}${s.ref} ${Markdown.escape(mr.title)}</html>"
        meta.text = "${mr.author?.name ?: "?"} · ${mr.sourceBranch} → ${mr.targetBranch} · ${mr.state}" +
            (if (mr.hasConflicts) " · " + msg("details.conflicts") else "") +
            (if (s.approvedBy.isNotEmpty()) " · " + msg("details.approvedBy", s.approvedBy.joinToString()) else "")
        refreshButton.toolTipText = msg("details.refresh", s.type.term)
        checkoutState.text = stateText(s, state)
        checkoutState.foreground = if (checkedOut) UIUtil.getLabelForeground() else UIUtil.getErrorForeground()
        checkoutButton.isEnabled = !checkedOut
        backButton.isVisible = service.returnPointFor(s) != null
        approveButton.text = msg(if (isApprovedByMe(s)) "details.revokeApprove" else "details.approve")
        updateFilesSummary()
        tree.repaint()
        renderThreads()
        // Relative links in GitLab descriptions (uploads) are relative to the project.
        description.text = "<html>${Markdown.gfmToHtml(mr.description.ifBlank { msg("details.noDescription") }, mr.webUrl.substringBefore("/-/"))}</html>"
        description.caretPosition = 0
        revalidate(); repaint()
    }

    /** Explains where the working copy is, so it is clear why navigation does or doesn't work. */
    private fun stateText(s: MrSession, st: CheckoutState?): String {
        val branch = "mr/${s.mr.iid}"
        val head = st?.mrHeadSha?.take(8) ?: "?"
        return when {
            st == null -> ""
            st.error != null -> msg("details.state.gitError", st.error)
            st.onMr -> msg("details.state.onMr", s.type.term, st.current, head)
            st.mrBranchSha != null && st.mrBranchSha == st.mrHeadSha -> msg("details.state.branchReady", st.current, branch)
            st.mrBranchSha != null -> msg("details.state.newCommits", st.current, s.type.term, branch, head)
            else -> msg("details.state.notOnMr", st.current, s.type.term, head)
        }
    }

    private fun isApprovedByMe(s: MrSession): Boolean {
        val me = runCatching { service.currentUserCached() }.getOrNull() ?: return false
        return me.username in s.approvedBy
    }

    private fun renderFiles() {
        val s = session ?: return
        val hidden = MrReviewSettings.getInstance().hiddenFiles()
        val hiddenCount = s.changes.count { hidden.isHidden(it) }
        showHidden.isVisible = hiddenCount > 0
        showHidden.text = msg("details.showHidden", hiddenCount)
        tree.setChanges(s.changes, hidden, showHidden.isSelected)
        updateFilesSummary()
        tabs.setTitleAt(0, msg("details.tab.files", tree.shownFiles.size))
    }

    /** "7 files · +120 −34 · 3 of 7 viewed · 115 generated hidden" */
    private fun updateFilesSummary() {
        val s = session ?: return
        val hidden = MrReviewSettings.getInstance().hiddenFiles()
        val visible = s.changes.filterNot { hidden.isHidden(it) }
        val hiddenCount = s.changes.size - visible.size
        val added = visible.sumOf { it.stats.first }
        val removed = visible.sumOf { it.stats.second }
        val viewed = visible.count { it.displayPath in s.viewed }
        val n = visible.size.toLong()
        filesSummary.text = "<html>" + msg("details.summary.files", n, MrBundle.plural(n, "files")) + " · " +
            "<font color='${ColorUtil.toHtmlColor(PLUS)}'>+$added</font> <font color='${ColorUtil.toHtmlColor(MINUS)}'>−$removed</font>" +
            " · " + msg("details.summary.viewed", viewed, n) +
            (if (hiddenCount > 0) " · " + msg("details.summary.hidden", hiddenCount) else "") + "</html>"
    }

    private fun toggleViewed(s: MrSession, change: FileChange) {
        val path = change.displayPath
        val nowViewed = path !in s.viewed
        ApplicationManager.getApplication().executeOnPooledThread { service.setViewed(s, path, nowViewed) }
    }

    private fun renderThreads() {
        val s = session ?: return
        val selected = threads.selectedValue?.id
        threadsModel.clear()
        val all = s.generalThreads + s.lineThreads
        val open = all.count { it.resolvable && !it.resolved }
        all.filter { !(hideResolved.isSelected && it.resolved) }
            // Open threads first, then the most recently active.
            .sortedWith(compareBy<Discussion> { it.resolved }.thenByDescending { it.lastActivity ?: "" })
            .forEach { threadsModel.addElement(it) }
        (0 until threadsModel.size()).firstOrNull { threadsModel[it].id == selected }?.let { threads.selectedIndex = it }
        hideResolved.isVisible = all.any { it.resolved }
        tabs.setTitleAt(1, if (open > 0) msg("details.tab.discussionOpen", all.size, open) else msg("details.tab.discussion", all.size))
    }

    /** A line thread opens the diff at its line; a general or outdated one opens as a popup. */
    private fun openSelectedThread() {
        val d = threads.selectedValue ?: return
        val s = session ?: return
        val p = d.position
        val change = p?.let { pos ->
            s.changes.firstOrNull { it.newPath == pos.newPath || (pos.newPath == null && it.oldPath == pos.oldPath) }
        }
        if (p != null && change != null && !s.isOutdated(d)) {
            val newLine = p.newLine
            val oldLine = p.oldLine
            val scrollTo = when {
                newLine != null -> Side.RIGHT to newLine - 1
                oldLine != null -> Side.LEFT to oldLine - 1
                else -> null
            }
            val files = tree.shownFiles.takeIf { change in it } ?: (tree.shownFiles + change)
            MrDiffOpener.open(project, s, files, change, scrollTo)
            return
        }
        val index = threads.selectedIndex
        val cell = threads.getCellBounds(index, index) ?: return
        ThreadPopup.showThread(project, s, d, RelativePoint(threads, Point(cell.x + JBUI.scale(20), cell.y + cell.height)))
    }

    private fun hint(text: String) = JBLabel(text).apply {
        foreground = UIUtil.getContextHelpForeground()
        font = JBUI.Fonts.smallFont()
        border = JBUI.Borders.empty(3, 8)
    }

    private fun openDiff(change: FileChange, files: List<FileChange>) {
        val s = session ?: return
        MrDiffOpener.open(project, s, files, change)
    }

    private fun checkout() {
        val s = session ?: return
        checkoutButton.isEnabled = false
        Bg.run(project, msg("details.checkoutTask", s.ref), work = { service.checkout(s) to service.checkoutState(s) }, onError = {
            checkoutButton.isEnabled = true
            Notify.error(project, msg("details.checkoutFailed"), it)
        }) { (message, newState) ->
            state = newState
            render()
            Notify.info(project, "${s.ref}: $message", msg("details.back") to { goBack() })
            tree.shownFiles.firstOrNull()?.let { openDiff(it, tree.shownFiles) }
        }
    }

    private fun goBack() {
        val s = session
        Bg.run(project, msg("details.goBackTask"), work = {
            val text = service.goBack(s ?: throw IllegalStateException(msg("details.nothingSelected")))
            text to service.checkoutState(s)
        }) { (message, co) ->
            state = co
            render()
            Notify.info(project, message)
        }
    }

    private fun toggleApprove() {
        val s = session ?: return
        val approve = !isApprovedByMe(s)
        Bg.run(project, if (approve) msg("details.approve") else msg("details.revokeTask"), work = {
            val c = service.client()
            if (approve) c.approve(s.project, s.mr) else c.unapprove(s.project, s.mr)
            service.refreshApprovals(s)
        }) { }
    }

    /** Two lines: who / when / where on top, the start of the comment below. */
    private inner class ThreadCellRenderer : ListCellRenderer<Discussion> {
        private val top = SimpleColoredComponent().apply { isOpaque = false; ipad = JBUI.emptyInsets() }
        private val bottom = SimpleColoredComponent().apply { isOpaque = false; ipad = JBUI.insetsLeft(20) }
        private val panel = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                JBUI.Borders.empty(6, 8),
            )
            add(top, BorderLayout.NORTH)
            add(bottom, BorderLayout.CENTER)
        }

        override fun getListCellRendererComponent(
            list: JList<out Discussion>, value: Discussion, index: Int, selected: Boolean, focused: Boolean,
        ): Component {
            val listFocused = list.hasFocus()
            val fg = UIUtil.getListForeground(selected, listFocused)
            panel.background = UIUtil.getListBackground(selected, listFocused)
            val grey = if (selected) SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, fg) else SimpleTextAttributes.GRAYED_ATTRIBUTES
            val s = session

            top.clear()
            bottom.clear()
            top.icon = if (value.resolved) AllIcons.General.InspectionsOK else AllIcons.General.Balloon
            val first = value.first
            top.append(first?.author?.name ?: "?", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, fg))
            top.append("  " + TimeAgo.format(value.lastActivity), grey)
            value.position?.let { p ->
                top.append("  ·  ${(p.newPath ?: p.oldPath)?.substringAfterLast('/')}:${p.lineLabel()}", grey)
            }
            if (s != null && s.isOutdated(value)) top.append("  " + msg("thread.outdated"), SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
            when {
                value.resolved -> top.append("  " + msg("thread.resolved"), SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, if (selected) fg else PLUS))
                value.resolvable -> top.append("  " + msg("thread.open"), SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, if (selected) fg else OPEN))
            }

            val preview = first?.body?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() && !it.startsWith("```") }.orEmpty()
            bottom.append(preview.take(160), SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, fg))
            val replies = value.notes.count { !it.system } - 1
            if (replies > 0) bottom.append("   ${replies} ${MrBundle.plural(replies.toLong(), "replies")}", grey)
            return panel
        }
    }

    companion object {
        private val PLUS = JBColor(0x2E7D32, 0x6AAB73)
        private val MINUS = JBColor(0xC62828, 0xE06C75)
        private val OPEN = JBColor(0xB26A00, 0xD9A343)
    }
}
