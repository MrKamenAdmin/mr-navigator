package me.brekhin.mrnavigator.api

import me.brekhin.mrnavigator.util.a
import me.brekhin.mrnavigator.util.arr
import me.brekhin.mrnavigator.util.long
import me.brekhin.mrnavigator.util.msg
import me.brekhin.mrnavigator.util.o
import me.brekhin.mrnavigator.util.obj
import me.brekhin.mrnavigator.util.str
import java.net.HttpURLConnection
import java.net.URLEncoder

/** GitLab REST API v4. */
class GitLabClient(serverUrl: String, token: String) : HostingClient {
    private val api = serverUrl.trimEnd('/') + "/api/v4"
    private val http = Http("GitLab") { it.setRequestProperty("PRIVATE-TOKEN", token) }
    private val graphqlUrl = serverUrl.trimEnd('/') + "/api/graphql"
    private val gql = Http("GitLab") { it.setRequestProperty("Authorization", "Bearer $token") }

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

    override fun reviews(project: ProjectRef, mr: MergeRequest): Reviews = Reviews(approvedBy(project, mr), requestedChanges(project, mr))

    /** Empty if approvals are not available on this instance. */
    private fun approvedBy(project: ProjectRef, mr: MergeRequest): List<String> = try {
        json("GET", "${mrPath(project, mr)}/approvals").obj()["approved_by"].arr()
            .mapNotNull { it.obj()["user"].obj().str("username") }
    } catch (e: ApiException) {
        emptyList()
    }

    private fun requestedChanges(project: ProjectRef, mr: MergeRequest): List<String> = try {
        withChangesRequested(paged("${mrPath(project, mr)}/reviewers"))
    } catch (e: ApiException) {
        emptyList()
    }

    /** Draft notes published at once — one notification, like "Submit review" on the web. */
    override fun submitReview(
        project: ProjectRef, mr: MergeRequest, drafts: List<Draft>, verdict: Verdict, summary: String, published: (List<Draft>) -> Unit,
    ) {
        publishDrafts(mrPath(project, mr), drafts, summary, published) { method, path, body -> json(method, path, body) }
        when (verdict) {
            Verdict.APPROVE -> approve(project, mr)
            Verdict.REQUEST_CHANGES -> mutate("mergeRequestRequestChanges", project, mr)
            Verdict.COMMENT -> Unit
        }
    }

    override fun withdrawChanges(project: ProjectRef, mr: MergeRequest) = mutate("mergeRequestDestroyRequestedChanges", project, mr)

    /** Request changes is in REST only since GitLab 19.2, in GraphQL long before. */
    private fun mutate(name: String, project: ProjectRef, mr: MergeRequest) {
        val query = "mutation(\$p: ID!, \$iid: String!) { $name(input: {projectPath: \$p, iid: \$iid}) { errors } }"
        val variables = mapOf("p" to project.path, "iid" to mr.iid.toString())
        val r = gql.call("POST", graphqlUrl, mapOf("query" to query, "variables" to variables)).json().obj()
        val errors = r.a("errors").mapNotNull { it.obj().str("message") } +
            r.o("data")?.o(name)?.a("errors").orEmpty().mapNotNull { it as? String }
        if (errors.isNotEmpty()) throw ApiException("GitLab: " + errors.joinToString("; "))
    }

    override fun checks(project: ProjectRef, mr: MergeRequest): Checks =
        json("GET", "${proj(project)}/merge_requests/${mr.iid}").obj().o("head_pipeline")?.let { pipeline(it) } ?: Checks.NONE

    override fun mergeOptions(project: ProjectRef, mr: MergeRequest): MergeOptions =
        mergeOptions(json("GET", "${proj(project)}/merge_requests/${mr.iid}").obj(), json("GET", proj(project)).obj())

    override fun merge(project: ProjectRef, mr: MergeRequest, strategy: String?, deleteBranch: Boolean) {
        val payload = linkedMapOf<String, Any?>("squash" to (strategy == "squash"), "should_remove_source_branch" to deleteBranch)
        mr.diffRefs?.headSha?.let { payload["sha"] = it }
        json("PUT", "${mrPath(project, mr)}/merge", payload)
    }

    companion object {
        internal fun ciState(status: String?): CiState = when (status) {
            "success" -> CiState.SUCCESS
            "failed", "canceled", "canceling" -> CiState.FAILED
            null, "skipped" -> CiState.NONE
            else -> CiState.RUNNING
        }

        internal fun pipeline(p: Map<String, Any?>): Checks {
            val url = p.str("web_url")
            return Checks.of(listOf(Check("Pipeline #${p.long("id") ?: ""}", ciState(p.str("status")), url)), url)
        }

        /** Squash per the project's setting; the merge method itself (merge / rebase / ff) is the project's too. */
        internal fun mergeOptions(mr: Map<String, Any?>, project: Map<String, Any?>): MergeOptions {
            val squash = project.str("squash_option") ?: "default_off"
            val strategies = when (squash) {
                "never" -> listOf("merge")
                "always" -> listOf("squash")
                else -> listOf("merge", "squash")
            }.map { MergeStrategy.of(it) }
            val default = if (squash == "always" || squash == "default_on") "squash" else "merge"
            val blocker = mr.str("detailed_merge_status")?.takeIf { it != "mergeable" }?.replace('_', ' ')
            return MergeOptions(strategies, default, canDeleteBranch = true, blocker = blocker)
        }

        /**
         * Draft notes published at once. If anything fails before publishing, this attempt's draft notes are
         * deleted — the next bulk_publish would send them along with a retry's.
         */
        internal fun publishDrafts(
            mrPath: String, drafts: List<Draft>, summary: String, published: (List<Draft>) -> Unit,
            call: (method: String, path: String, body: Any?) -> Any?,
        ) {
            if (drafts.isEmpty() && summary.isBlank()) return
            val path = "$mrPath/draft_notes"
            val created = ArrayList<Long>()
            try {
                drafts.forEach { d -> call("POST", path, draftNote(d)).obj().long("id")?.let { created += it } }
                if (summary.isNotBlank()) call("POST", path, mapOf("note" to summary)).obj().long("id")?.let { created += it }
                call("POST", "$path/bulk_publish", emptyMap<String, Any?>())
            } catch (e: ApiException) {
                for (id in created) {
                    try {
                        call("DELETE", "$path/$id", null)
                    } catch (ignored: ApiException) {
                    }
                }
                throw e
            }
            published(drafts)
        }

        internal fun draftNote(d: Draft): Map<String, Any?> = mapOf("note" to d.body, "position" to d.position.toJson())

        internal fun withChangesRequested(reviewers: List<Map<String, Any?>>): List<String> =
            reviewers.filter { it.str("state") == "requested_changes" }.mapNotNull { it.o("user")?.str("username") }
    }
}
