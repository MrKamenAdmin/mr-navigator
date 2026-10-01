package me.brekhin.mrnavigator.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.IconButton
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.panels.VerticalLayout
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import me.brekhin.mrnavigator.api.Discussion
import me.brekhin.mrnavigator.api.Note
import me.brekhin.mrnavigator.api.Position
import me.brekhin.mrnavigator.core.MrReviewService
import me.brekhin.mrnavigator.core.MrSession
import me.brekhin.mrnavigator.util.Markdown
import me.brekhin.mrnavigator.util.Suggestion
import me.brekhin.mrnavigator.util.TimeAgo
import java.awt.BorderLayout
import java.awt.Container
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Rectangle
import java.awt.event.InputEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.ScrollPaneConstants
import javax.swing.SwingConstants
import javax.swing.event.HyperlinkEvent
import kotlin.math.max
import kotlin.math.min

/** Popup with a comment thread (read, reply, resolve) or with a form for a new comment. */
object ThreadPopup {
    /** Only one comment popup at a time: opening another one closes the previous. */
    private var current: JBPopup? = null

    private val WIDTH get() = JBUI.scale(540)
    private val RESOLVED_BG = JBColor(0xE8F5E9, 0x2B3A2E)

    /**
     * Existing thread. [suggestionLines] — current text of the lines the thread is on (new version);
     * when given, the reply can contain a suggestion.
     */
    fun showThread(
        project: Project, session: MrSession, discussion: Discussion, at: RelativePoint,
        suggestionLines: List<String>? = null, onClosed: () -> Unit = {},
    ) {
        val service = MrReviewService.getInstance(project)
        lateinit var popup: JBPopup

        val input = inputArea("Ответить…")
        val reply = primary("Ответить")
        val resolve = JButton(if (discussion.resolved) "Переоткрыть" else "Resolve").apply {
            isVisible = discussion.resolvable
            toolTipText = if (discussion.resolved) "Снова открыть обсуждение" else "Отметить обсуждение решённым"
        }
        val openWeb = JButton(AllIcons.General.Web).apply {
            toolTipText = "Открыть в браузере"
            isVisible = discussion.webUrl != null
        }

        fun busy(b: Boolean) {
            reply.isEnabled = !b; resolve.isEnabled = !b; input.isEnabled = !b
        }

        val sendReply = {
            val text = input.text.trim()
            if (text.isNotEmpty()) {
                busy(true)
                Bg.run(project, "Отправка ответа", work = { service.reply(session, discussion, text) },
                    onError = { busy(false); Notify.error(project, "Ответ не отправлен", it) }) { popup.cancel() }
            }
        }
        reply.addActionListener { sendReply() }
        submitOnCtrlEnter(input, sendReply)
        resolve.addActionListener {
            busy(true)
            Bg.run(project, "Resolve", work = { service.setResolved(session, discussion, !discussion.resolved) },
                onError = { busy(false); Notify.error(project, "Не получилось", it) }) { popup.cancel() }
        }
        openWeb.addActionListener { discussion.webUrl?.let { BrowserUtil.browse(it) } }
        val applySuggestions = { ids: List<Long>, button: JButton ->
            busy(true); button.isEnabled = false
            Bg.run(project, "Применение suggestion", work = { service.applySuggestions(session, ids) },
                onError = { busy(false); button.isEnabled = true; Notify.error(project, "Suggestion не применён", it) }) {
                popup.cancel()
                Notify.info(project, "Suggestion применён: GitLab добавил коммит в ${session.mr.sourceBranch}. " +
                    "Чтобы получить его локально, обновите MR и сделайте «Checkout и ревью»")
            }
        }

        val panel = JPanel(BorderLayout(0, JBUI.scale(8))).apply {
            border = JBUI.Borders.empty(8, 10, 10, 10)
            if (discussion.resolved) add(resolvedBanner(discussion), BorderLayout.NORTH)
            add(notesView(discussion, applySuggestions), BorderLayout.CENTER)
            add(editor(input,
                left = listOf(reply, suggestionButton(input, suggestionLines)),
                right = listOf(resolve, openWeb)), BorderLayout.SOUTH)
        }
        popup = build(panel, input, title(discussion), onClosed)
        popup.show(at)
    }

    /**
     * New thread; [position] == null means a general MR comment.
     * [suggestionLines] — current text of the commented lines (new version); null if a suggestion
     * is impossible there (removed lines, general comment).
     */
    fun showNew(
        project: Project, session: MrSession, position: Position?, at: RelativePoint,
        suggestionLines: List<String>? = null, onClosed: () -> Unit = {},
    ) {
        val service = MrReviewService.getInstance(project)
        lateinit var popup: JBPopup
        val input = inputArea(if (position == null) "Комментарий к merge request…" else "Комментарий…")
        val send = primary("Комментировать")
        val submit = {
            val text = input.text.trim()
            if (text.isNotEmpty()) {
                send.isEnabled = false; input.isEnabled = false
                Bg.run(project, "Отправка комментария", work = { service.postComment(session, text, position) },
                    onError = {
                        send.isEnabled = true; input.isEnabled = true
                        Notify.error(project, "Комментарий не отправлен", it)
                    }) { popup.cancel() }
            }
        }
        send.addActionListener { submit() }
        submitOnCtrlEnter(input, submit)

        val where = position?.let { p ->
            val file = (p.newPath ?: p.oldPath)?.substringAfterLast('/')
            "Новый комментарий · $file, " + if (p.isMultiLine) "строки ${p.lineLabel()}" else "строка ${p.lineLabel()}"
        } ?: "Комментарий к !${session.mr.iid}"

        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 10, 10, 10)
            add(editor(input, left = listOf(send, suggestionButton(input, suggestionLines)), right = emptyList()), BorderLayout.CENTER)
        }
        popup = build(panel, input, where, onClosed)
        popup.show(at)
    }

    // ------------------------------------------------------------------ parts

    private fun title(d: Discussion): String {
        val p = d.position ?: return "Обсуждение"
        val file = (p.newPath ?: p.oldPath)?.substringAfterLast('/')
        return "$file:${p.lineLabel()}"
    }

    /** "✓ Решено · Name" strip on top of a resolved thread. */
    private fun resolvedBanner(d: Discussion): JComponent = JPanel(BorderLayout()).apply {
        background = RESOLVED_BG
        border = JBUI.Borders.empty(4, 8)
        val who = d.resolvedBy?.name?.let { " · $it" }.orEmpty()
        add(JBLabel("Решено$who", AllIcons.General.InspectionsOK, SwingConstants.LEFT))
    }

    /** Notes one under another, separated by thin lines; scrolls when the thread is long. */
    private fun notesView(d: Discussion, onApply: (List<Long>, JButton) -> Unit): JComponent {
        val notes = WidthTrackingPanel()
        d.notes.filter { !it.system }.forEachIndexed { i, n -> notes.add(noteView(n, separator = i > 0, onApply)) }

        // Height of the content at the popup width, capped — longer threads scroll.
        // Lay out twice: the first pass gives the HTML panes their width, the second their wrapped height.
        notes.size = Dimension(WIDTH, Short.MAX_VALUE.toInt())
        repeat(2) { layoutAll(notes) }
        val height = min(max(notes.preferredSize.height, JBUI.scale(40)), JBUI.scale(320))
        return JBScrollPane(notes).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            preferredSize = Dimension(WIDTH, height)
        }
    }

    private fun layoutAll(c: Container) {
        c.doLayout()
        for (child in c.components) if (child is Container) layoutAll(child)
    }

    private fun noteView(n: Note, separator: Boolean, onApply: (List<Long>, JButton) -> Unit): JComponent = JPanel(BorderLayout(0, JBUI.scale(2))).apply {
        isOpaque = false
        border = if (separator) {
            JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 1, 0, 0, 0), JBUI.Borders.emptyTop(8))
        } else {
            JBUI.Borders.empty()
        }
        val header = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(JBLabel(n.author?.name ?: "?").apply { font = JBUI.Fonts.label().asBold() })
            val meta = listOfNotNull(n.author?.username?.let { "@$it" }, TimeAgo.format(n.createdAt).ifEmpty { null })
            add(JBLabel("  " + meta.joinToString(" · ")).apply { foreground = UIUtil.getContextHelpForeground() })
        }
        add(header, BorderLayout.NORTH)
        add(htmlBody(n.body), BorderLayout.CENTER)
        suggestionState(n, onApply)?.let { add(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { isOpaque = false; add(it) }, BorderLayout.SOUTH) }
    }

    /** Like GitLab's "Apply suggestion": a button for the note's suggestions, or a mark that they are applied. */
    private fun suggestionState(n: Note, onApply: (List<Long>, JButton) -> Unit): JComponent? {
        if (n.suggestions.isEmpty()) return null
        val ids = n.suggestions.filter { it.appliable }.map { it.id }
        if (ids.isEmpty() && n.suggestions.all { it.applied }) {
            return JBLabel("Suggestion применён", AllIcons.General.InspectionsOK, SwingConstants.LEFT)
        }
        return JButton("Применить suggestion", AllIcons.Actions.IntentionBulb).apply {
            isEnabled = ids.isNotEmpty()
            toolTipText = if (isEnabled) "GitLab закоммитит изменение в ветку MR" else
                "GitLab не даёт применить: suggestion устарел, MR закрыт или изменение уже в коде"
            addActionListener { onApply(ids, this) }
        }
    }

    private fun htmlBody(markdown: String): JComponent = JEditorPane(UIUtil.HTML_MIME, "<html><body>${Markdown.toHtml(markdown)}</body></html>").apply {
        isEditable = false
        isOpaque = false
        border = JBUI.Borders.empty()
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        font = UIUtil.getLabelFont()
        addHyperlinkListener { if (it.eventType == HyperlinkEvent.EventType.ACTIVATED) BrowserUtil.browse(it.url) }
    }

    /** Text area with buttons: [left] — main actions, [right] — secondary; a hint about the shortcut. */
    private fun editor(input: JBTextArea, left: List<JButton>, right: List<JButton>): JComponent {
        val send = if (SystemInfo.isMac) "⌘↩" else "Ctrl+Enter"
        val hint = JBLabel("Markdown · $send — отправить").apply {
            foreground = UIUtil.getContextHelpForeground()
            font = JBUI.Fonts.smallFont()
        }
        val buttons = JPanel(BorderLayout()).apply {
            add(row(left), BorderLayout.WEST)
            add(row(right), BorderLayout.EAST)
        }
        return JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            add(JBScrollPane(input).apply { preferredSize = Dimension(WIDTH, JBUI.scale(90)) }, BorderLayout.NORTH)
            add(hint, BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }
    }

    private fun row(buttons: List<JButton>) = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
        buttons.forEach { add(it) }
    }

    private fun primary(text: String) = JButton(text).apply { putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true) }

    /**
     * "Suggest a change" — like GitLab's "Insert suggestion": inserts a ```suggestion block with the
     * current lines at the caret and selects them for editing. Hidden when a suggestion is impossible.
     */
    private fun suggestionButton(input: JBTextArea, lines: List<String>?): JButton =
        JButton("Предложить изменение", AllIcons.Actions.IntentionBulb).apply {
            isVisible = !lines.isNullOrEmpty()
            toolTipText = "Вставить блок suggestion с текущими строками — автор MR сможет применить его в GitLab"
            addActionListener {
                val block = Suggestion.block(lines ?: return@addActionListener)
                val caret = input.caretPosition
                val before = input.text.substring(0, caret)
                val prefix = if (before.isEmpty() || before.endsWith("\n")) "" else "\n"
                input.insert(prefix + block.text + "\n", caret)
                val base = caret + prefix.length
                input.requestFocusInWindow()
                input.select(base + block.contentStart, base + block.contentEnd)
            }
        }

    private fun inputArea(placeholder: String) = JBTextArea(4, 60).apply {
        lineWrap = true
        wrapStyleWord = true
        emptyText.text = placeholder
        border = JBUI.Borders.empty(4, 6)
    }

    private fun submitOnCtrlEnter(area: JBTextArea, action: () -> Unit) {
        area.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                val mod = e.modifiersEx and (InputEvent.CTRL_DOWN_MASK or InputEvent.META_DOWN_MASK)
                if (e.keyCode == KeyEvent.VK_ENTER && mod != 0) {
                    e.consume()
                    action()
                }
            }
        })
    }

    private fun build(panel: JComponent, focus: JComponent, title: String, whenClosed: () -> Unit): JBPopup {
        current?.takeIf { !it.isDisposed }?.cancel()
        val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(panel, focus)
            .setTitle(title)
            .setResizable(true)
            .setMovable(true)
            .setRequestFocus(true)
            .setCancelOnClickOutside(false) // don't lose a half-written comment
            .setCancelOnOtherWindowOpen(false)
            .setCancelButton(IconButton("Закрыть (Esc)", AllIcons.Actions.Close, AllIcons.Actions.CloseHovered))
            .createPopup()
        popup.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) {
                if (current === popup) current = null
                whenClosed()
            }
        })
        current = popup
        return popup
    }

    /** Vertical stack that follows the viewport width, so HTML bodies wrap instead of scrolling sideways. */
    // VerticalLayout scales the gap itself, so pass unscaled 8.
    private class WidthTrackingPanel : JPanel(VerticalLayout(8)), Scrollable {
        init {
            isOpaque = false
        }

        override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
        override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
        override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = visibleRect.height
        override fun getScrollableTracksViewportWidth() = true
        override fun getScrollableTracksViewportHeight() = false
    }
}
