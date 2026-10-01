package me.brekhin.mrnavigator.settings

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import me.brekhin.mrnavigator.core.HiddenFiles

/** Application-level settings. The token itself lives in the IDE password storage. */
@Service(Service.Level.APP)
// Storage and password-safe names keep the plugin's original name on purpose: renaming them would
// silently drop the settings and token of existing users.
@State(name = "GitLabMrReviewSettings", storages = [Storage("gitlab-mr-review.xml")])
class MrReviewSettings : PersistentStateComponent<MrReviewSettings.State> {
    class State {
        var serverUrl: String = "https://gitlab.com"
        var gitExecutable: String = "git"
        // Nothing is hidden until the user lists suffixes and turns hiding on.
        var hideEnabled: Boolean = false
        var hiddenSuffixes: String = ""
        var autoStash: Boolean = true
    }

    private var state = State()

    override fun getState(): State = state
    override fun loadState(state: State) {
        this.state = state
    }

    var serverUrl: String
        get() = state.serverUrl.trim().trimEnd('/')
        set(value) { state.serverUrl = value.trim().trimEnd('/') }

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

    fun hiddenFiles(): HiddenFiles = HiddenFiles(hiddenSuffixes, hideEnabled)

    // ---- token (PasswordSafe; call off the EDT when possible)

    private fun credentials(server: String) =
        CredentialAttributes(generateServiceName("GitLab MR Review", server.ifBlank { "default" }))

    fun getToken(server: String = serverUrl): String? =
        PasswordSafe.instance.getPassword(credentials(server))?.takeIf { it.isNotBlank() }

    fun setToken(token: String?, server: String = serverUrl) {
        PasswordSafe.instance.setPassword(credentials(server), token?.trim()?.takeIf { it.isNotEmpty() })
    }

    companion object {
        fun getInstance(): MrReviewSettings = service()
    }
}
