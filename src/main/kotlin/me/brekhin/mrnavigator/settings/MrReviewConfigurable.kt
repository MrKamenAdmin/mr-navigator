package me.brekhin.mrnavigator.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.ui.SimpleListCellRenderer
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
                row("Адрес сервера:") {
                    urlField = textField().bindText(settings::serverUrl).columns(COLUMNS_LARGE)
                        .comment("Например https://gitlab.com или https://git.company.ru").component
                }
                row("Personal access token:") {
                    tokenField = passwordField().bindText(::token).columns(COLUMNS_LARGE)
                        .comment("Scope <b>api</b>: GitLab → Preferences → Access Tokens. Хранится в хранилище паролей IDE.").component
                }
                row {
                    button("Проверить подключение") { testConnection() }
                }
            }
            group("Скрытие файлов") {
                row {
                    checkBox("Скрывать файлы с этими суффиксами в дереве и diff").bindSelected(settings::hideEnabled)
                }
                row("Суффиксы (по одному в строке):") {}
                row {
                    textArea().bindText(::suffixesText).rows(4).align(AlignX.FILL)
                        .applyToComponent { emptyText.text = ".pb.go" }
                        .comment("Путь файла оканчивается на суффикс → файл скрыт. Например .pb.go, .pb.gw.go, _mock.go")
                }
            }
            group("Git") {
                row("Путь к git:") {
                    textField().bindText(settings::gitExecutable).columns(COLUMNS_LARGE).comment("Обычно достаточно git")
                }
                row {
                    checkBox("При checkout MR прятать незакоммиченные изменения в stash").bindSelected(settings::autoStash)
                }
            }
            row(msg("settings.language")) {
                comboBox(listOf("auto", "en", "ru"), SimpleListCellRenderer.create("") { msg("settings.language.$it") })
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
            Runnable { settings.setToken(value, server) }, "Сохранение токена", false, null,
        )
    }

    private fun testConnection() {
        val url = urlField.text.trim().trimEnd('/')
        val tok = String(tokenField.password).trim()
        if (url.isEmpty() || tok.isEmpty()) {
            Messages.showWarningDialog(urlField, "Заполните адрес и токен", "GitLab")
            return
        }
        try {
            val user = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable { GitLabClient(url, tok).currentUser() }, "Проверка подключения…", true, null,
            )
            Messages.showInfoMessage(urlField, "Подключено как ${user.name} (@${user.username})", "GitLab")
        } catch (e: Exception) {
            Messages.showErrorDialog(urlField, e.message ?: e.toString(), "GitLab")
        }
    }
}
