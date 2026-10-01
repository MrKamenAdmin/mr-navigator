package me.brekhin.mrnavigator.api

import me.brekhin.mrnavigator.util.msg

enum class MrFilter(private val key: String) {
    OPENED("filter.opened"),
    REVIEW_REQUESTED("filter.review"),
    ASSIGNED("filter.assigned"),
    MINE("filter.mine"),
    MERGED("filter.merged");

    override fun toString() = msg(key)
}

/** A server the plugin is connected to; [username] — for Bitbucket's Basic auth, null otherwise. */
data class Connection(val type: HostingType, val url: String, val username: String? = null)

enum class HostingType(
    val title: String,
    /** Before the number: "!12" on GitLab, "#12" elsewhere. */
    val prefix: Char,
    /** "MR" or "PR" — not translated. */
    val term: String,
    val filters: List<MrFilter>,
    /** "Suggest a change" button: the server renders ```suggestion blocks. */
    val canSuggest: Boolean,
    /** GitLab accepts comments on any line of the file, the others only on lines of the diff hunks. */
    val commentsOutsideHunks: Boolean,
    /** Bundle key of the user name field of the connection form; null — no such field. */
    val usernameLabel: String?,
    /** The only server of a cloud service; null — the user enters the address. */
    val fixedUrl: String?,
) {
    GITLAB("GitLab", '!', "MR", MrFilter.entries, canSuggest = true, commentsOutsideHunks = true, usernameLabel = null, fixedUrl = null);

    fun client(c: Connection, token: String): HostingClient = when (this) {
        GITLAB -> GitLabClient(c.url, token)
    }

    fun tokenPageUrl(url: String): String = when (this) {
        GITLAB -> "$url/-/user_settings/personal_access_tokens?name=MR+Navigator&scopes=api"
    }

    override fun toString() = title

    companion object {
        /** Type of a server by its host name: the form's first guess for a new connection. */
        fun guess(host: String): HostingType = GITLAB
    }
}

/** One code hosting API. All methods block — call them from a background thread. */
interface HostingClient {
    fun currentUser(): User
    fun mergeRequests(project: ProjectRef, filter: MrFilter, me: User?, search: String?): List<MergeRequest>
    fun mergeRequest(project: ProjectRef, iid: Long): MergeRequest
    fun changes(project: ProjectRef, mr: MergeRequest): List<FileChange>
    fun discussions(project: ProjectRef, mr: MergeRequest): List<Discussion>
    /** [position] == null — a general comment. */
    fun createDiscussion(project: ProjectRef, mr: MergeRequest, body: String, position: Position?)
    fun reply(project: ProjectRef, mr: MergeRequest, d: Discussion, body: String)
    fun resolve(project: ProjectRef, mr: MergeRequest, d: Discussion, resolved: Boolean)
    fun approve(project: ProjectRef, mr: MergeRequest)
    fun unapprove(project: ProjectRef, mr: MergeRequest)
    /** Usernames of those who approved. */
    fun approvedBy(project: ProjectRef, mr: MergeRequest): List<String>
    /** GitLab only — the others have no API for it, and their notes carry no suggestions. */
    fun applySuggestions(ids: List<Long>): Unit = throw UnsupportedOperationException()
}
