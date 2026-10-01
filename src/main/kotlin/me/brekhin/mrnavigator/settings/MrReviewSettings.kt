package me.brekhin.mrnavigator.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import me.brekhin.mrnavigator.api.Connection
import me.brekhin.mrnavigator.api.HostingType
import me.brekhin.mrnavigator.core.HiddenFiles

/** Application-level settings. Tokens live in the IDE password storage, one per server address. */
@Service(Service.Level.APP)
// Storage and password-safe names keep the plugin's original name on purpose: renaming them would
// silently drop the settings and token of existing users.
@State(name = "GitLabMrReviewSettings", storages = [Storage("gitlab-mr-review.xml")])
class MrReviewSettings : PersistentStateComponent<MrReviewSettings.State> {
    class State {
        /** The only server before 0.3; read once by [migrateLegacy]. */
        var serverUrl: String = "https://gitlab.com"
        var connections: MutableList<ConnectionState> = ArrayList()
        var migrated: Boolean = false
        var gitExecutable: String = "git"
        // Nothing is hidden until the user lists suffixes and turns hiding on.
        var hideEnabled: Boolean = false
        var hiddenSuffixes: String = ""
        var autoStash: Boolean = true
        /** "auto" (the OS language), "en" or "ru". */
        var language: String = "auto"
    }

    class ConnectionState {
        var type: String = HostingType.GITLAB.name
        var url: String = ""
        var username: String = ""
    }

    private var state = State()

    override fun getState(): State = state
    override fun loadState(state: State) {
        this.state = state
    }

    var connections: List<Connection>
        get() = state.connections.mapNotNull { c ->
            val type = HostingType.entries.firstOrNull { it.name == c.type } ?: return@mapNotNull null
            Connection(type, c.url, c.username.ifBlank { null })
        }
        set(value) {
            state.connections = value.mapTo(ArrayList()) { c ->
                ConnectionState().apply { type = c.type.name; url = c.url; username = c.username.orEmpty() }
            }
        }

    /** Adds [c], or replaces the connection with the same address, and stores its token. Blocking (password storage). */
    fun saveConnection(c: Connection, token: String) {
        connections = connections.filter { it.url != c.url } + c
        setToken(token, c.url)
    }

    fun removeConnection(c: Connection) {
        connections = connections.filter { it.url != c.url }
        setToken(null, c.url)
    }

    /** Before 0.3 the plugin knew one GitLab server; it becomes a connection, once. Blocking (password storage). */
    fun migrateLegacy() {
        if (state.migrated) return
        state.migrated = true
        legacyConnection(state)?.takeIf { getToken(it.url) != null }?.let { connections = listOf(it) }
    }

    var gitExecutable: String
        get() = state.gitExecutable.ifBlank { "git" }
        set(value) { state.gitExecutable = value.trim() }

    var hideEnabled: Boolean
        get() = state.hideEnabled
        set(value) { state.hideEnabled = value }

    var hiddenSuffixes: List<String>
        get() = HiddenFiles.parse(state.hiddenSuffixes)
        set(value) { state.hiddenSuffixes = value.joinToString("\n") }

    var autoStash: Boolean
        get() = state.autoStash
        set(value) { state.autoStash = value }

    var language: String
        get() = state.language
        set(value) { state.language = value }

    fun hiddenFiles(): HiddenFiles = HiddenFiles(hiddenSuffixes, hideEnabled)

    // ---- tokens (PasswordSafe; call off the EDT when possible)

    private fun credentials(url: String) =
        CredentialAttributes(generateServiceName("GitLab MR Review", url.ifBlank { "default" }))

    fun getToken(url: String): String? =
        PasswordSafe.instance.getPassword(credentials(url))?.takeIf { it.isNotBlank() }

    fun setToken(token: String?, url: String) {
        PasswordSafe.instance.setPassword(credentials(url), token?.trim()?.takeIf { it.isNotEmpty() })
    }

    companion object {
        fun getInstance(): MrReviewSettings = service()

        /** The connection the pre-0.3 single-server settings describe, if there are no connections yet. */
        internal fun legacyConnection(state: State): Connection? {
            val url = state.serverUrl.trim().trimEnd('/')
            return if (state.connections.isEmpty() && url.isNotEmpty()) Connection(HostingType.GITLAB, url) else null
        }
    }
}
