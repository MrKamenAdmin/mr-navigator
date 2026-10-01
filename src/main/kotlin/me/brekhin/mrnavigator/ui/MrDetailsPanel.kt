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

/** Details of the selected MR: actions, files, discussion, description. */
class MrDetailsPanel(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {
    private val service = MrReviewService.getInstance(project)

    private val title = JBLabel().apply { font = JBUI.Fonts.label().biggerOn(2f).asBold() }
    private val meta = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val checkoutState = JBLabel()

    private val checkoutButton = JButton("Checkout и ревью", AllIcons.Actions.CheckOut)
    private val backButton = JButton("Вернуться", AllIcons.Actions.Back)
    private val approveButton = JButton("Approve")
    private val browserButton = JButton(AllIcons.General.Web).apply { toolTipText = "Открыть в браузере" }
    private val refreshButton = JButton(AllIcons.Actions.Refresh).apply { toolTipText = "Обновить MR" }

    private val tree = ChangesTree(
        onOpen = { change, files -> openDiff(change, files) },
        onToggleViewed = { change -> session?.let { s -> toggleViewed(s, change) } },
    )
    private val showHidden = JBCheckBox("Показать скрытые")
    private val filesSummary = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }

    private val threadsModel = DefaultListModel<Discussion>()
    private val threads = JBList(threadsModel)
    private val newCommentButton = JButton("Новый комментарий", AllIcons.General.Add)
    private val hideResolved = JBCheckBox("Скрыть решённые")

    private val description = JEditorPane().apply {
        editorKit = HTMLEditorKitBuilder.simple()
        isEditable = false
        addHyperlinkListener { if (it.eventType == HyperlinkEvent.EventType.ACTIVATED) it.url?.let { url -> BrowserUtil.browse(url) } }
    }
    private val tabs = JBTabbedPane()

    private val placeholder = JBLabel("Выберите merge request", SwingConstants.CENTER).apply {
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
            add(hint("Двойной клик или Enter — открыть diff · Пробел — отметить просмотренным"), BorderLayout.SOUTH)
        }

        threads.setCellRenderer(ThreadCellRenderer())
        threads.emptyText.text = "Комментариев нет"
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
            add(hint("Двойной клик — открыть тред: комментарий к строке откроется в diff на этой строке"), BorderLayout.SOUTH)
        }
        tabs.addTab("Файлы", filesTab)
        tabs.addTab("Обсуждение", threadsTab)
        tabs.addTab("Описание", JBScrollPane(description))

        content.add(header, BorderLayout.NORTH)
        content.add(tabs, BorderLayout.CENTER)
        add(placeholder, BorderLayout.CENTER)

        showHidden.addActionListener { renderFiles() }
        hideResolved.addActionListener { renderThreads() }
        checkoutButton.addActionListener { checkout() }
        backButton.addActionListener { goBack() }
        approveButton.addActionListener { toggleApprove() }
        browserButton.addActionListener { session?.let { BrowserUtil.browse(it.mr.webUrl) } }
        refreshButton.addActionListener { session?.let { load(it.mr) } }
        newCommentButton.addActionListener {
            val s = session ?: return@addActionListener
            ThreadPopup.showNew(project, s, null, RelativePoint(newCommentButton, Point(0, newCommentButton.height)))
        }

        service.addListener(parent) { onServiceChanged() }
    }

    fun load(mr: MergeRequest) {
        loadingIid = mr.iid
        removeAll()
        add(JBLabel("Загрузка !${mr.iid}…", SwingConstants.CENTER), BorderLayout.CENTER)
        revalidate(); repaint()
        Bg.run(project, "Загрузка !${mr.iid}", work = {
            val s = service.loadSession(mr)
            s to service.checkoutState(s)
        }, onError = {
            if (loadingIid == mr.iid) showMessage("Не удалось загрузить !${mr.iid}: ${it.message}")
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
        title.text = "<html>${if (mr.draft) "<span style='color:gray'>Draft:</span> " else ""}!${mr.iid} ${Markdown.escape(mr.title)}</html>"
        meta.text = "${mr.author?.name ?: "?"} · ${mr.sourceBranch} → ${mr.targetBranch} · ${mr.state}" +
            (if (mr.hasConflicts) " · конфликты" else "") +
            (if (s.approvedBy.isNotEmpty()) " · approved: ${s.approvedBy.joinToString()}" else "")
        checkoutState.text = stateText(s, state)
        checkoutState.foreground = if (checkedOut) UIUtil.getLabelForeground() else UIUtil.getErrorForeground()
        checkoutButton.isEnabled = !checkedOut
        backButton.isVisible = service.returnPointFor(s) != null
        approveButton.text = if (isApprovedByMe(s)) "Отозвать approve" else "Approve"
        updateFilesSummary()
        tree.repaint()
        renderThreads()
        // Relative links in GitLab descriptions (uploads) are relative to the project.
        description.text = "<html>${Markdown.gfmToHtml(mr.description.ifBlank { "_Нет описания_" }, mr.webUrl.substringBefore("/-/"))}</html>"
        description.caretPosition = 0
        revalidate(); repaint()
    }

    /** Explains where the working copy is, so it is clear why navigation does or doesn't work. */
    private fun stateText(s: MrSession, st: CheckoutState?): String {
        val branch = "mr/${s.mr.iid}"
        val head = st?.mrHeadSha?.take(8) ?: "?"
        return when {
            st == null -> ""
            st.error != null -> "Не удалось проверить git: ${st.error}"
            st.onMr -> "✓ Рабочая копия на коде MR (${st.current}, $head) — в diff работают переходы"
            st.mrBranchSha != null && st.mrBranchSha == st.mrHeadSha ->
                "Сейчас вы на ${st.current}. Ветка $branch уже выкачана — «Checkout и ревью» переключит на неё"
            st.mrBranchSha != null ->
                "Сейчас вы на ${st.current}. В MR новые коммиты — «Checkout и ревью» обновит $branch до $head"
            else -> "Сейчас вы на ${st.current}, а не на коде MR ($head) — переходы в diff не работают. Нажмите «Checkout и ревью»"
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
        showHidden.text = "Показать скрытые ($hiddenCount)"
        tree.setChanges(s.changes, hidden, showHidden.isSelected)
        updateFilesSummary()
        tabs.setTitleAt(0, "Файлы (${tree.shownFiles.size})")
    }

    /** "7 файлов · +120 −34 · просмотрено 3 из 7 · скрыто 115 сгенерированных" */
    private fun updateFilesSummary() {
        val s = session ?: return
        val hidden = MrReviewSettings.getInstance().hiddenFiles()
        val visible = s.changes.filterNot { hidden.isHidden(it) }
        val hiddenCount = s.changes.size - visible.size
        val added = visible.sumOf { it.stats.first }
        val removed = visible.sumOf { it.stats.second }
        val viewed = visible.count { it.displayPath in s.viewed }
        val n = visible.size.toLong()
        filesSummary.text = "<html>$n ${TimeAgo.plural(n, "файл", "файла", "файлов")} · " +
            "<font color='${ColorUtil.toHtmlColor(PLUS)}'>+$added</font> <font color='${ColorUtil.toHtmlColor(MINUS)}'>−$removed</font>" +
            " · просмотрено $viewed из $n" +
            (if (hiddenCount > 0) " · скрыто $hiddenCount сгенерированных" else "") + "</html>"
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
        tabs.setTitleAt(1, "Обсуждение (${all.size}" + (if (open > 0) ", открыто $open" else "") + ")")
    }

    private fun isOutdated(s: MrSession, d: Discussion): Boolean {
        val head = s.mr.diffRefs?.headSha
        val p = d.position ?: return false
        return p.headSha != null && head != null && p.headSha != head
    }

    /** A line thread opens the diff at its line; a general or outdated one opens as a popup. */
    private fun openSelectedThread() {
        val d = threads.selectedValue ?: return
        val s = session ?: return
        val p = d.position
        val change = p?.let { pos ->
            s.changes.firstOrNull { it.newPath == pos.newPath || (pos.newPath == null && it.oldPath == pos.oldPath) }
        }
        if (p != null && change != null && !isOutdated(s, d)) {
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
        Bg.run(project, "Checkout !${s.mr.iid}", work = { service.checkout(s) to service.checkoutState(s) }, onError = {
            checkoutButton.isEnabled = true
            Notify.error(project, "Checkout не удался", it)
        }) { (message, newState) ->
            state = newState
            render()
            Notify.info(project, "!${s.mr.iid}: $message", "Вернуться" to { goBack() })
            tree.shownFiles.firstOrNull()?.let { openDiff(it, tree.shownFiles) }
        }
    }

    private fun goBack() {
        val s = session
        Bg.run(project, "Возврат на прежнюю ветку", work = {
            val msg = service.goBack(s ?: throw IllegalStateException("MR не выбран"))
            msg to service.checkoutState(s)
        }) { (message, co) ->
            state = co
            render()
            Notify.info(project, message)
        }
    }

    private fun toggleApprove() {
        val s = session ?: return
        val approve = !isApprovedByMe(s)
        Bg.run(project, if (approve) "Approve" else "Отзыв approve", work = {
            val c = service.client()
            if (approve) c.approve(s.project, s.mr.iid, s.mr.sha) else c.unapprove(s.project, s.mr.iid)
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
            if (s != null && isOutdated(s, value)) top.append("  устарел", SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
            when {
                value.resolved -> top.append("  решён", SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, if (selected) fg else PLUS))
                value.resolvable -> top.append("  открыт", SimpleTextAttributes(SimpleTextAttributes.STYLE_SMALLER, if (selected) fg else OPEN))
            }

            val preview = first?.body?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() && !it.startsWith("```") }.orEmpty()
            bottom.append(preview.take(160), SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, fg))
            val replies = value.notes.count { !it.system } - 1
            if (replies > 0) bottom.append("   ${replies} ${TimeAgo.plural(replies.toLong(), "ответ", "ответа", "ответов")}", grey)
            return panel
        }
    }

    companion object {
        private val PLUS = JBColor(0x2E7D32, 0x6AAB73)
        private val MINUS = JBColor(0xC62828, 0xE06C75)
        private val OPEN = JBColor(0xB26A00, 0xD9A343)
    }
}
