package me.brekhin.mrnavigator.ui

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.COLUMNS_LARGE
import com.intellij.ui.dsl.builder.Panel
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.columns
import com.intellij.util.ui.UIUtil
import me.brekhin.mrnavigator.api.Connection
import me.brekhin.mrnavigator.api.HostingType
import me.brekhin.mrnavigator.api.User
import me.brekhin.mrnavigator.settings.MrReviewSettings
import me.brekhin.mrnavigator.util.msg

/**
 * Fields of one connection — hosting, address, user name (Bitbucket) and token — with a link to create
 * the token. Used by the tool window's setup form and by "Add connection" in the settings.
 */
class ConnectionForm {
    private val typeCombo = ComboBox(HostingType.entries.toTypedArray())
    private val urlField = JBTextField()
    private val usernameField = JBTextField()
    private val tokenField = JBPasswordField()
    private val usernameLabel = JBLabel()
    private val hint = JBLabel().apply { foreground = UIUtil.getContextHelpForeground() }
    private var urlRow: Row? = null
    private var usernameRow: Row? = null

    private val type: HostingType get() = typeCombo.selectedItem as HostingType

    /** Adds the rows to [p]; [onSubmit] runs on Enter in the token field. */
    fun addTo(p: Panel, onSubmit: () -> Unit = {}) {
        with(p) {
            row(msg("connection.type")) { cell(typeCombo) }
            urlRow = row(msg("connection.url")) { cell(urlField).columns(COLUMNS_LARGE) }
            usernameRow = row(usernameLabel) { cell(usernameField).columns(COLUMNS_LARGE).comment(msg("connection.username.comment")) }
            row(msg("connection.token")) { cell(tokenField).columns(COLUMNS_LARGE) }
            row("") { cell(hint) }
            row("") { link(msg("connection.createToken")) { BrowserUtil.browse(type.tokenPageUrl(url())) } }
        }
        typeCombo.addActionListener { updateType() }
        tokenField.addActionListener { onSubmit() }
        updateType()
    }

    /** Pre-fills the form with an existing connection, or guesses it from a git remote [host]. */
    fun fill(c: Connection?, host: String? = null) {
        typeCombo.selectedItem = c?.type ?: host?.let { HostingType.guess(it) } ?: HostingType.GITLAB
        urlField.text = c?.url ?: host?.let { "https://$it" }.orEmpty()
        usernameField.text = c?.username.orEmpty()
        tokenField.text = ""
        updateType()
    }

    private fun url(): String =
        type.fixedUrl ?: urlField.text.trim().trimEnd('/').let { if (it.isEmpty() || "://" in it) it else "https://$it" }

    fun connection() = Connection(type, url(), usernameField.text.trim().takeIf { it.isNotEmpty() && type.usernameLabel != null })

    fun token(): String = String(tokenField.password).trim()

    fun clearToken() {
        tokenField.text = ""
    }

    /** What is missing, or null when the form can be submitted. */
    fun validate(): String? = if (url().isEmpty() || token().isEmpty()) msg("connection.fillIn") else null

    private fun updateType() {
        val t = type
        urlRow?.visible(t.fixedUrl == null)
        usernameRow?.visible(t.usernameLabel != null)
        t.usernameLabel?.let { usernameLabel.text = msg(it) }
        hint.text = "<html>${msg("connection.hint.${t.name}")}</html>"
    }

    companion object {
        /** Checks the connection and saves it with the token. Blocking: network and password storage. */
        fun verifyAndSave(c: Connection, token: String): User {
            val user = c.type.client(c, token).currentUser()
            MrReviewSettings.getInstance().saveConnection(c, token)
            return user
        }
    }
}
