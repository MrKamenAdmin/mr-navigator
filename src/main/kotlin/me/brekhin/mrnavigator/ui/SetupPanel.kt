package me.brekhin.mrnavigator.ui

import com.intellij.ide.BrowserUtil
import com.intellij.ide.ui.laf.darcula.ui.DarculaButtonUI
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import me.brekhin.mrnavigator.api.GitLabClient
import me.brekhin.mrnavigator.core.MrReviewService
import me.brekhin.mrnavigator.settings.MrReviewConfigurable
import me.brekhin.mrnavigator.settings.MrReviewSettings
import me.brekhin.mrnavigator.util.Markdown
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Rectangle
import java.net.URLEncoder
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.Scrollable

/**
 * Shown instead of the MR list until the plugin is connected: GitLab address (suggested from
 * the project's git remotes), a personal access token with a link to create one, "Connect".
 */
class SetupPanel(private val project: Project, private val onConnected: () -> Unit) : JPanel(BorderLayout()), Scrollable {
    private val settings = MrReviewSettings.getInstance()

    private lateinit var urlField: JBTextField
    private lateinit var tokenField: JBPasswordField
    private lateinit var connectButton: JButton
    // HTML labels wrap to the width they get (the panel follows the tool window width, see Scrollable below).
    private val status = JBLabel()
    private val reason = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private val detected = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }

    init {
        val form = panel {
            row {
                label("Подключение к GitLab").applyToComponent { font = JBFont.h3().asBold() }
            }
            row {
                text("Плагину нужен адрес вашего GitLab и личный токен доступа со scope <b>api</b>. " +
                    "Токен хранится в хранилище паролей IDE.")
            }
            row { cell(reason).align(AlignX.FILL) }
            row("Адрес GitLab:") {
                urlField = textField().columns(COLUMNS_LARGE).component
            }
            row("") { cell(detected) }
            row("Токен:") {
                tokenField = passwordField().columns(COLUMNS_LARGE).component
            }
            row("") {
                link("Создать токен в GitLab →") { BrowserUtil.browse(tokenPageUrl()) }
            }
            row {
                connectButton = button("Подключить") { connect() }
                    .applyToComponent { putClientProperty(DarculaButtonUI.DEFAULT_STYLE_KEY, true) }
                    .component
            }
            row { cell(status).align(AlignX.FILL) }
            row {
                link("Все настройки плагина") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(project, MrReviewConfigurable::class.java)
                    onConnected()
                }
            }
        }
        form.border = JBUI.Borders.empty(16)
        add(form, BorderLayout.NORTH)
        tokenField.addActionListener { connect() }
    }

    /** Fills the form: [why] explains what is missing; the address is guessed from git remotes. */
    fun prepare(why: String?) {
        reason.text = why?.let { "<html>${Markdown.escape(it)}</html>" }.orEmpty()
        reason.isVisible = !why.isNullOrBlank()
        status.text = ""
        urlField.text = settings.serverUrl
        detected.text = ""
        ApplicationManager.getApplication().executeOnPooledThread {
            val servers = MrReviewService.getInstance(project).detectedServers()
            ApplicationManager.getApplication().invokeLater({
                if (servers.isEmpty()) return@invokeLater
                detected.text = "Найдено в git remote: " + servers.joinToString()
                // The default gitlab.com is almost certainly wrong for a company repo — suggest the remote's host.
                if (settings.serverUrl == "https://gitlab.com" && servers.first() != "https://gitlab.com") {
                    urlField.text = servers.first()
                }
            }, { project.isDisposed })
        }
    }

    private fun serverUrl() = urlField.text.trim().trimEnd('/').let { if (it.contains("://")) it else "https://$it" }

    private fun tokenPageUrl(): String {
        val name = URLEncoder.encode("MR Navigator", Charsets.UTF_8)
        return "${serverUrl()}/-/user_settings/personal_access_tokens?name=$name&scopes=api"
    }

    private fun connect() {
        val url = serverUrl()
        val token = String(tokenField.password).trim()
        if (token.isEmpty()) {
            showError("Вставьте токен")
            return
        }
        connectButton.isEnabled = false
        status.foreground = UIUtil.getContextHelpForeground()
        status.text = "Проверяю…"
        Bg.run(project, "Подключение к GitLab", work = {
            val user = GitLabClient(url, token).currentUser()
            settings.serverUrl = url
            settings.setToken(token, url)
            user
        }, onError = {
            connectButton.isEnabled = true
            showError(it.message ?: it.toString())
        }) { user ->
            connectButton.isEnabled = true
            tokenField.text = ""
            Notify.info(project, "GitLab: подключено как ${user.name} (@${user.username})")
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
