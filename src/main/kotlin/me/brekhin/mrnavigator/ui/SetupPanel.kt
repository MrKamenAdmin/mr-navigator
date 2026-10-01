package me.brekhin.mrnavigator.ui

import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import me.brekhin.mrnavigator.api.Connection
import me.brekhin.mrnavigator.core.MrReviewService
import me.brekhin.mrnavigator.settings.MrReviewConfigurable
import me.brekhin.mrnavigator.util.Markdown
import me.brekhin.mrnavigator.util.msg
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.Scrollable

/**
 * Shown instead of the list until the project's repository has a connection: hosting and address
 * (guessed from the git remotes), a token with a link to create one, "Connect".
 */
class SetupPanel(private val project: Project, private val onConnected: () -> Unit) : JPanel(BorderLayout()), Scrollable {
    private val form = ConnectionForm()
    private lateinit var connectButton: JButton
    // HTML labels wrap to the width they get (the panel follows the tool window width, see Scrollable below).
    private val status = JBLabel()
    private val reason = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val detected = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }

    init {
        val content = panel {
            row { label(msg("setup.title")).applyToComponent { font = JBFont.h3().asBold() } }
            row { text(msg("setup.intro")) }
            row { cell(reason).align(AlignX.FILL) }
            row { cell(detected) }
            form.addTo(this) { connect() }
            row {
                connectButton = button(msg("setup.connect")) { connect() }
                    .applyToComponent { putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true) }
                    .component
            }
            row { cell(status).align(AlignX.FILL) }
            row {
                link(msg("setup.allSettings")) {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, MrReviewConfigurable::class.java)
                    onConnected()
                }
            }
        }
        content.border = JBUI.Borders.empty(16)
        add(content, BorderLayout.NORTH)
    }

    /** [why] explains what is missing; [connection] — the one to fix, otherwise the form is guessed from git remotes. */
    fun prepare(why: String?, connection: Connection?) {
        reason.text = why?.let { "<html>${Markdown.escape(it)}</html>" }.orEmpty()
        reason.isVisible = !why.isNullOrBlank()
        status.text = ""
        detected.text = ""
        form.fill(connection)
        if (connection != null) return
        ApplicationManager.getApplication().executeOnPooledThread {
            val hosts = MrReviewService.getInstance(project).detectedHosts()
            ApplicationManager.getApplication().invokeLater({
                if (hosts.isEmpty()) return@invokeLater
                detected.text = msg("setup.detected", hosts.joinToString())
                form.fill(null, hosts.first())
            }, { project.isDisposed })
        }
    }

    private fun connect() {
        form.validate()?.let { showError(it); return }
        val c = form.connection()
        val token = form.token()
        connectButton.isEnabled = false
        status.foreground = UIUtil.getContextHelpForeground()
        status.text = msg("setup.checking")
        Bg.run(project, msg("setup.task", c.type.title), work = { ConnectionForm.verifyAndSave(c, token) }, onError = {
            connectButton.isEnabled = true
            showError(it.message ?: it.toString())
        }) { user ->
            connectButton.isEnabled = true
            form.clearToken()
            Notify.info(project, msg("setup.connected", c.type.title, user.name, user.username))
            onConnected()
        }
    }

    private fun showError(text: String) {
        status.foreground = UIUtil.getErrorForeground()
        status.text = "<html>${Markdown.escape(text)}</html>"
    }

    // Track the viewport width so that long messages wrap instead of scrolling sideways.
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize
    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = JBUI.scale(16)
    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int) = visibleRect.height
    override fun getScrollableTracksViewportWidth() = true
    override fun getScrollableTracksViewportHeight() = false
}
