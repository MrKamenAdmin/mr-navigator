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
    GITLAB("GitLab", '!', "MR", MrFilter.entries, canSuggest = true, commentsOutsideHunks = true, usernameLabel = null, fixedUrl = null),
    GITHUB("GitHub", '#', "PR", MrFilter.entries, canSuggest = true, commentsOutsideHunks = false, usernameLabel = null, fixedUrl = null),
    BITBUCKET_CLOUD("Bitbucket Cloud", '#', "PR", MrFilter.entries - MrFilter.ASSIGNED, canSuggest = false, commentsOutsideHunks = false,
        usernameLabel = "connection.email", fixedUrl = "https://bitbucket.org"),
    BITBUCKET_SERVER("Bitbucket Data Center", '#', "PR", MrFilter.entries - MrFilter.ASSIGNED, canSuggest = false, commentsOutsideHunks = false,
        usernameLabel = "connection.username", fixedUrl = null);

    fun client(c: Connection, token: String): HostingClient = when (this) {
        GITLAB -> GitLabClient(c.url, token)
        GITHUB -> GitHubClient(c.url, token)
        BITBUCKET_CLOUD -> BitbucketCloudClient(token, c.username)
        BITBUCKET_SERVER -> BitbucketServerClient(c.url, token, c.username)
    }

    fun tokenPageUrl(url: String): String = when (this) {
        GITLAB -> "$url/-/user_settings/personal_access_tokens?name=MR+Navigator&scopes=api"
        GITHUB -> "$url/settings/tokens/new?description=MR%20Navigator&scopes=repo"
        BITBUCKET_CLOUD -> "https://id.atlassian.com/manage-profile/security/api-tokens"
        BITBUCKET_SERVER -> "$url/account"
    }

    override fun toString() = title

    companion object {
        /** Type of a server by its host name: the form's first guess for a new connection. */
        fun guess(host: String): HostingType = when {
            host == "bitbucket.org" -> BITBUCKET_CLOUD
            "github" in host -> GITHUB
            "bitbucket" in host -> BITBUCKET_SERVER
            else -> GITLAB
        }
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
    fun editNote(project: ProjectRef, mr: MergeRequest, d: Discussion, note: Note, body: String)
    fun deleteNote(project: ProjectRef, mr: MergeRequest, d: Discussion, note: Note)
    fun approve(project: ProjectRef, mr: MergeRequest)
    fun unapprove(project: ProjectRef, mr: MergeRequest)
    /** Who approved and who requested changes. */
    fun reviews(project: ProjectRef, mr: MergeRequest): Reviews
    /** Publishes [drafts] and [summary] with [verdict] — with one notification where the server can. */
    fun submitReview(project: ProjectRef, mr: MergeRequest, drafts: List<Draft>, verdict: Verdict, summary: String)
    fun withdrawChanges(project: ProjectRef, mr: MergeRequest)
    /** GitLab only — the others have no API for it, and their notes carry no suggestions. */
    fun applySuggestions(ids: List<Long>): Unit = throw UnsupportedOperationException()
}
