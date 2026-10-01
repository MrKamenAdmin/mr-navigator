package me.brekhin.mrnavigator.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.content.ContentFactory
import com.intellij.util.ui.JBUI
import me.brekhin.mrnavigator.api.MergeRequest
import me.brekhin.mrnavigator.api.MrFilter
import me.brekhin.mrnavigator.api.ApiException
import me.brekhin.mrnavigator.core.MrReviewService
import me.brekhin.mrnavigator.core.Repo
import me.brekhin.mrnavigator.core.SetupNeeded
import me.brekhin.mrnavigator.settings.MrReviewConfigurable
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import me.brekhin.mrnavigator.util.msg
import me.brekhin.mrnavigator.util.MrBundle
import me.brekhin.mrnavigator.api.HostingType

class MrToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val disposable = Disposer.newDisposable("MR Navigator tool window")
        val panel = MrToolWindowPanel(project, disposable)
        val content = ContentFactory.getInstance().createContent(panel, null, false)
        content.setDisposer(disposable)
        toolWindow.contentManager.addContent(content)
    }
}

/**
 * List of merge requests on top, details of the selected one below.
 * Until the plugin is connected, a setup form ([SetupPanel]) is shown instead.
 */
class MrToolWindowPanel(private val project: Project, parent: Disposable) : JPanel(CardLayout()) {
    private val service = MrReviewService.getInstance(project)
    private val setup = SetupPanel(project) { reload(refreshRepos = true) }

    /** Repository switcher — shown only when the project folder contains several GitLab repositories. */
    private val repoCombo = ComboBox<Repo>().apply {
        setRenderer(RepoRenderer())
        toolTipText = msg("list.repo.tooltip")
    }
    private val repoRow = JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
        border = JBUI.Borders.empty(4, 4, 0, 4)
        add(JBLabel(msg("list.repo")), BorderLayout.WEST)
        add(repoCombo, BorderLayout.CENTER)
        isVisible = false
    }
    private var updatingRepos = false
    /** Hosting of the listed repository: numbers and terms in the list and the details follow it. */
    private var currentType = HostingType.GITLAB

    private val filter = ComboBox(MrFilter.entries.toTypedArray()).apply { selectedItem = MrFilter.OPENED }
    private val search = SearchTextField(false).apply { textEditor.emptyText.text = msg("list.search") }
    private val refresh = JButton(AllIcons.Actions.Refresh).apply { toolTipText = msg("list.refresh") }
    private val settings = JButton(AllIcons.General.Settings).apply { toolTipText = msg("list.settings") }

    private val model = DefaultListModel<MergeRequest>()
    private val list = JBList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        setCellRenderer(Renderer())
    }
    private val details = MrDetailsPanel(project, parent)

    init {
        val toolbar = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            border = JBUI.Borders.empty(4)
            add(filter, BorderLayout.WEST)
            add(search, BorderLayout.CENTER)
            add(JPanel(BorderLayout()).apply { add(refresh, BorderLayout.WEST); add(settings, BorderLayout.EAST) }, BorderLayout.EAST)
        }
        val header = JPanel(BorderLayout()).apply {
            add(repoRow, BorderLayout.NORTH)
            add(toolbar, BorderLayout.CENTER)
        }
        val top = JPanel(BorderLayout()).apply {
            add(header, BorderLayout.NORTH)
            add(JBScrollPane(list), BorderLayout.CENTER)
        }
        val splitter = OnePixelSplitter(true, "me.brekhin.mrnavigator.splitter", 0.35f).apply {
            firstComponent = top
            secondComponent = details
        }
        add(splitter, CARD_MAIN)
        add(JBScrollPane(setup).apply { border = JBUI.Borders.empty() }, CARD_SETUP)

        filter.addActionListener { reload() }
        refresh.addActionListener { reload(refreshRepos = true) }
        repoCombo.addActionListener {
            if (updatingRepos) return@addActionListener
            val repo = repoCombo.selectedItem as? Repo ?: return@addActionListener
            service.selectRepo(repo)
            details.clear()
            reload()
        }
        settings.addActionListener {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, MrReviewConfigurable::class.java)
            reload(refreshRepos = true)
        }
        search.textEditor.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) reload()
            }
        })
        list.addListSelectionListener {
            if (!it.valueIsAdjusting) list.selectedValue?.let { mr -> details.load(mr, currentType) }
        }
        reload()
    }

    private class Loaded(val repos: List<Repo>, val repo: Repo, val mrs: List<MergeRequest>)

    /** [refreshRepos] — look for repositories again (after "Refresh", setup or settings changes). */
    private fun reload(refreshRepos: Boolean = false) {
        val f = filter.selectedItem as? MrFilter ?: MrFilter.OPENED
        val query = search.text
        list.emptyText.text = msg("list.loading")
        model.clear()
        Bg.run(project, msg("list.loadingTask", currentType.term), work = {
            val repos = service.repositories(refreshRepos)
            val repo = service.selectedRepo(repos)
            val client = service.client()
            val me = if (f == MrFilter.OPENED || f == MrFilter.MERGED) runCatching { service.currentUser(client) }.getOrNull()
            else service.currentUser(client)
            Loaded(repos, repo, client.mergeRequests(repo.project, f, me, query))
        }, onError = { e ->
            val unauthorized = e is ApiException && e.status == 401
            if (e is SetupNeeded || unauthorized) {
                setup.prepare(if (unauthorized) msg("list.unauthorized", currentType.title) else e.message)
                showCard(CARD_SETUP)
                return@run
            }
            showCard(CARD_MAIN)
            list.emptyText.clear()
            list.emptyText.appendLine(e.message ?: msg("error"))
            list.emptyText.appendLine(msg("list.openSettings"), SimpleTextAttributes.LINK_PLAIN_ATTRIBUTES) {
                ShowSettingsUtil.getInstance().showSettingsDialog(project, MrReviewConfigurable::class.java)
                reload(refreshRepos = true)
            }
        }) { loaded ->
            showCard(CARD_MAIN)
            showRepos(loaded.repos, loaded.repo)
            list.emptyText.text = msg("list.empty", currentType.term, loaded.repo.project.path)
            loaded.mrs.forEach { model.addElement(it) }
            // Keep the opened MR selected — but only if it is from this repository.
            val current = service.session?.takeIf { it.project == loaded.repo.project }?.mr?.iid
            val index = loaded.mrs.indexOfFirst { it.iid == current }
            if (index >= 0) list.selectedIndex = index else if (loaded.mrs.isEmpty()) details.clear()
        }
    }

    private fun showRepos(repos: List<Repo>, selected: Repo) {
        updatingRepos = true
        try {
            repoCombo.model = DefaultComboBoxModel(repos.toTypedArray())
            repoCombo.selectedItem = selected
        } finally {
            updatingRepos = false
        }
        repoRow.isVisible = repos.size > 1
    }

    private class RepoRenderer : ColoredListCellRenderer<Repo>() {
        override fun customizeCellRenderer(list: JList<out Repo>, value: Repo?, index: Int, selected: Boolean, hasFocus: Boolean) {
            value ?: return
            icon = AllIcons.Nodes.Folder
            append(value.name)
            if (value.name != value.project.path) append("   ${value.project.path}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    private fun showCard(card: String) = (layout as CardLayout).show(this, card)

    private inner class Renderer : ColoredListCellRenderer<MergeRequest>() {
        override fun customizeCellRenderer(list: JList<out MergeRequest>, value: MergeRequest, index: Int, selected: Boolean, hasFocus: Boolean) {
            if (value.draft) append("Draft ", SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
            append("${currentType.prefix}${value.iid} ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            append(value.title, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            append("   ${value.author?.name ?: ""} · ${value.sourceBranch} → ${value.targetBranch}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            if (value.userNotesCount > 0) append("  · ${value.userNotesCount} ${MrBundle.plural(value.userNotesCount.toLong(), "comments")}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        }
    }

    companion object {
        private const val CARD_MAIN = "main"
        private const val CARD_SETUP = "setup"
    }
}
