package me.brekhin.mrnavigator.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.dsl.builder.rows
import com.intellij.ui.dsl.builder.toNullableProperty
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import me.brekhin.mrnavigator.api.GitLabClient
import me.brekhin.mrnavigator.util.msg

/** Settings → Tools → MR Navigator */
class MrReviewConfigurable : BoundConfigurable("MR Navigator") {
    private val settings = MrReviewSettings.getInstance()

    private var token: String = ""
    private var suffixesText: String = ""
    private lateinit var urlField: JBTextField
    private lateinit var tokenField: JBPasswordField

    override fun createPanel(): DialogPanel {
        suffixesText = settings.hiddenSuffixes.joinToString("\n")
        // The password storage may be slow (OS keychain) — read it off the EDT.
        val server = settings.serverUrl
        ApplicationManager.getApplication().executeOnPooledThread {
            val stored = settings.getToken(server).orEmpty()
            ApplicationManager.getApplication().invokeLater({
                if (::tokenField.isInitialized && String(tokenField.password).isEmpty()) {
                    token = stored
                    tokenField.text = stored
                }
            }, ModalityState.any())
        }

        return panel {
            group("GitLab") {
                row(msg("settings.server")) {
                    urlField = textField().bindText(settings::serverUrl).columns(COLUMNS_LARGE)
                        .comment(msg("settings.server.comment")).component
                }
                row(msg("settings.token")) {
                    tokenField = passwordField().bindText(::token).columns(COLUMNS_LARGE)
                        .comment(msg("settings.token.comment")).component
                }
                row {
                    button(msg("settings.check")) { testConnection() }
                }
            }
            group(msg("settings.hiding")) {
                row {
                    checkBox(msg("settings.hide")).bindSelected(settings::hideEnabled)
                }
                row(msg("settings.suffixes")) {}
                row {
                    textArea().bindText(::suffixesText).rows(4).align(AlignX.FILL)
                        .applyToComponent { emptyText.text = ".pb.go" }
                        .comment(msg("settings.suffixes.comment"))
                }
            }
            group(msg("settings.git")) {
                row(msg("settings.gitPath")) {
                    textField().bindText(settings::gitExecutable).columns(COLUMNS_LARGE).comment(msg("settings.gitPath.comment"))
                }
                row {
                    checkBox(msg("settings.autoStash")).bindSelected(settings::autoStash)
                }
            }
            row(msg("settings.language")) {
                comboBox(listOf("auto", "en", "ru"), textListCellRenderer { msg("settings.language.$it") })
                    .bindItem(settings::language.toNullableProperty())
                    .comment(msg("settings.language.comment"))
            }
        }
    }

    override fun apply() {
        super.apply()
        settings.hiddenSuffixes = suffixesText.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val value = token
        val server = settings.serverUrl
        // Synchronously (under a progress) so that the tool window reloading right after sees the new token.
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            Runnable { settings.setToken(value, server) }, msg("settings.savingToken"), false, null,
        )
    }

    private fun testConnection() {
        val url = urlField.text.trim().trimEnd('/')
        val tok = String(tokenField.password).trim()
        if (url.isEmpty() || tok.isEmpty()) {
            Messages.showWarningDialog(urlField, msg("settings.fillIn"), "GitLab")
            return
        }
        try {
            val user = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable { GitLabClient(url, tok).currentUser() }, msg("settings.checking"), true, null,
            )
            Messages.showInfoMessage(urlField, msg("settings.connected", user.name, user.username), "GitLab")
        } catch (e: Exception) {
            Messages.showErrorDialog(urlField, e.message ?: e.toString(), "GitLab")
        }
    }
}
