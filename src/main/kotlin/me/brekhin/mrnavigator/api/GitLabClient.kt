package me.brekhin.mrnavigator.api

import me.brekhin.mrnavigator.util.arr
import me.brekhin.mrnavigator.util.msg
import me.brekhin.mrnavigator.util.obj
import me.brekhin.mrnavigator.util.str
import java.net.HttpURLConnection
import java.net.URLEncoder

/** GitLab REST API v4. */
class GitLabClient(serverUrl: String, token: String) : HostingClient {
    private val api = serverUrl.trimEnd('/') + "/api/v4"
    private val http = Http("GitLab") { it.setRequestProperty("PRIVATE-TOKEN", token) }

    private fun json(method: String, path: String, body: Any? = null): Any? = http.call(method, api + path, body).json()

    /** Follows X-Next-Page until exhausted (or [limit] items). */
    private fun paged(path: String, limit: Int = 2000): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        val sep = if (path.contains('?')) '&' else '?'
        var page: String? = "1"
        while (page != null && out.size < limit) {
            val r = http.call("GET", "$api$path${sep}per_page=100&page=$page")
            out += r.json().arr().map { it.obj() }
            page = r.header("X-Next-Page")?.takeIf { it.isNotBlank() }
        }
        return out
    }

    private fun proj(project: ProjectRef) = "/projects/${project.encodedPath}"
    private fun mrPath(project: ProjectRef, mr: MergeRequest) = "${proj(project)}/merge_requests/${mr.iid}"
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    override fun currentUser(): User = User.from(json("GET", "/user").obj()) ?: throw ApiException(msg("api.emptyUser", "GitLab"))

    override fun mergeRequests(project: ProjectRef, filter: MrFilter, me: User?, search: String?): List<MergeRequest> {
        val state = if (filter == MrFilter.MERGED) "merged" else "opened"
        val params = StringBuilder("state=$state&order_by=updated_at&sort=desc")
        when (filter) {
            MrFilter.REVIEW_REQUESTED -> me?.let { params.append("&reviewer_username=${enc(it.username)}") }
            MrFilter.MINE -> me?.let { params.append("&author_username=${enc(it.username)}") }
            MrFilter.ASSIGNED -> me?.let { params.append("&assignee_username=${enc(it.username)}") }
            else -> Unit
        }
        if (!search.isNullOrBlank()) params.append("&search=${enc(search.trim())}")
        return paged("${proj(project)}/merge_requests?$params", limit = 200).map { MergeRequest.from(it) }
    }

    override fun mergeRequest(project: ProjectRef, iid: Long): MergeRequest =
        MergeRequest.from(json("GET", "${proj(project)}/merge_requests/$iid").obj())

    /** Uses /diffs (GitLab 15.7+), falls back to the older /changes. */
    override fun changes(project: ProjectRef, mr: MergeRequest): List<FileChange> = try {
        paged("${mrPath(project, mr)}/diffs").map { FileChange.from(it) }
    } catch (e: ApiException) {
        if (e.status != HttpURLConnection.HTTP_NOT_FOUND) throw e
        json("GET", "${mrPath(project, mr)}/changes").obj()["changes"].arr().map { FileChange.from(it.obj()) }
    }

    override fun discussions(project: ProjectRef, mr: MergeRequest): List<Discussion> =
        paged("${mrPath(project, mr)}/discussions").map { m ->
            Discussion.from(m).let { it.copy(webUrl = "${mr.webUrl}#note_${it.first?.id ?: ""}") }
        }

    override fun createDiscussion(project: ProjectRef, mr: MergeRequest, body: String, position: Position?) {
        val payload = linkedMapOf<String, Any?>("body" to body)
        if (position != null) payload["position"] = position.toJson()
        json("POST", "${mrPath(project, mr)}/discussions", payload)
    }

    override fun reply(project: ProjectRef, mr: MergeRequest, d: Discussion, body: String) {
        json("POST", "${mrPath(project, mr)}/discussions/${d.id}/notes", mapOf("body" to body))
    }

    override fun resolve(project: ProjectRef, mr: MergeRequest, d: Discussion, resolved: Boolean) {
        json("PUT", "${mrPath(project, mr)}/discussions/${d.id}?resolved=$resolved", emptyMap<String, Any?>())
    }

    override fun editNote(project: ProjectRef, mr: MergeRequest, d: Discussion, note: Note, body: String) {
        json("PUT", "${mrPath(project, mr)}/discussions/${d.id}/notes/${note.id}", mapOf("body" to body))
    }

    override fun deleteNote(project: ProjectRef, mr: MergeRequest, d: Discussion, note: Note) {
        json("DELETE", "${mrPath(project, mr)}/discussions/${d.id}/notes/${note.id}")
    }

    /** GitLab commits the suggestions to the source branch, like "Apply suggestion" on the web. */
    override fun applySuggestions(ids: List<Long>) {
        if (ids.size == 1) json("PUT", "/suggestions/${ids[0]}/apply", emptyMap<String, Any?>())
        else json("PUT", "/suggestions/batch_apply", mapOf("ids" to ids))
    }

    override fun approve(project: ProjectRef, mr: MergeRequest) {
        json("POST", "${mrPath(project, mr)}/approve", mr.sha?.let { mapOf("sha" to it) } ?: emptyMap<String, Any?>())
    }

    override fun unapprove(project: ProjectRef, mr: MergeRequest) {
        json("POST", "${mrPath(project, mr)}/unapprove", emptyMap<String, Any?>())
    }

    /** Empty if approvals are not available on this instance. */
    override fun approvedBy(project: ProjectRef, mr: MergeRequest): List<String> = try {
        json("GET", "${mrPath(project, mr)}/approvals").obj()["approved_by"].arr()
            .mapNotNull { it.obj()["user"].obj().str("username") }
    } catch (e: ApiException) {
        emptyList()
    }
}
