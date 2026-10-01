package me.brekhin.mrnavigator.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.ThrowableComputable
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
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
import me.brekhin.mrnavigator.api.ApiException
import me.brekhin.mrnavigator.api.Connection
import me.brekhin.mrnavigator.ui.ConnectionForm
import me.brekhin.mrnavigator.util.msg
import javax.swing.JComponent

/** Settings → Tools → MR Navigator */
class MrReviewConfigurable : BoundConfigurable("MR Navigator") {
    private val settings = MrReviewSettings.getInstance()

    private var suffixesText: String = ""
    // Connections are saved right away, with their tokens — not on Apply.
    private val connections = CollectionListModel<Connection>()
    private val list = JBList(connections).apply {
        emptyText.text = msg("settings.noConnections")
        cellRenderer = textListCellRenderer { c -> listOfNotNull(c.type.title, c.url, c.username).joinToString(" · ") }
    }

    override fun createPanel(): DialogPanel {
        suffixesText = settings.hiddenSuffixes.joinToString("\n")
        connections.replaceAll(settings.connections)
        // Migrating pre-0.3 settings reads the password storage (OS keychain) — off the EDT.
        ApplicationManager.getApplication().executeOnPooledThread {
            settings.migrateLegacy()
            ApplicationManager.getApplication().invokeLater({ connections.replaceAll(settings.connections) }, ModalityState.any())
        }

        return panel {
            group(msg("settings.connections")) {
                row {
                    cell(
                        ToolbarDecorator.createDecorator(list)
                            .setAddAction { addConnection() }
                            .setRemoveAction { removeConnection() }
                            .disableUpDownActions()
                            .createPanel(),
                    ).align(AlignX.FILL)
                }
                row { button(msg("settings.check")) { checkConnection() } }
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
    }

    private fun addConnection() {
        val form = ConnectionForm()
        val dialog = object : DialogWrapper(list, true) {
            init {
                title = msg("settings.addConnection")
                init()
            }

            override fun createCenterPanel(): JComponent = panel { form.addTo(this) }.also { form.fill(null) }

            override fun doOKAction() {
                form.validate()?.let { setErrorText(it); return }
                val c = form.connection()
                val token = form.token()
                try {
                    ProgressManager.getInstance().runProcessWithProgressSynchronously(
                        ThrowableComputable { ConnectionForm.verifyAndSave(c, token) }, msg("connection.checking"), true, null,
                    )
                    super.doOKAction()
                } catch (e: Exception) {
                    setErrorText(e.message ?: e.toString())
                }
            }
        }
        if (dialog.showAndGet()) connections.replaceAll(settings.connections)
    }

    private fun removeConnection() {
        val c = list.selectedValue ?: return
        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            Runnable { settings.removeConnection(c) }, msg("settings.removing"), false, null,
        )
        connections.replaceAll(settings.connections)
    }

    private fun checkConnection() {
        val c = list.selectedValue ?: return Messages.showInfoMessage(list, msg("settings.selectConnection"), "MR Navigator")
        try {
            val user = ProgressManager.getInstance().runProcessWithProgressSynchronously(
                ThrowableComputable {
                    val token = settings.getToken(c.url) ?: throw ApiException(msg("error.noToken", c.url))
                    c.type.client(c, token).currentUser()
                },
                msg("connection.checking"), true, null,
            )
            Messages.showInfoMessage(list, msg("settings.connected", user.name, user.username), c.type.title)
        } catch (e: Exception) {
            Messages.showErrorDialog(list, e.message ?: e.toString(), c.type.title)
        }
    }
}
