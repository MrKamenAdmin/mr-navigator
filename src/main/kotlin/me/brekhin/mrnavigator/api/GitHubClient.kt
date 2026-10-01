package me.brekhin.mrnavigator.api

import me.brekhin.mrnavigator.util.a
import me.brekhin.mrnavigator.util.arr
import me.brekhin.mrnavigator.util.bool
import me.brekhin.mrnavigator.util.int
import me.brekhin.mrnavigator.util.long
import me.brekhin.mrnavigator.util.msg
import me.brekhin.mrnavigator.util.o
import me.brekhin.mrnavigator.util.obj
import me.brekhin.mrnavigator.util.str

/** GitHub REST v3 plus GraphQL for review threads (REST can't resolve them); github.com or Enterprise Server. */
class GitHubClient(serverUrl: String, token: String) : HostingClient {
    private val api = apiUrl(serverUrl)
    private val graphqlUrl = if (api == GITHUB_API) "$GITHUB_API/graphql" else serverUrl.trimEnd('/') + "/api/graphql"
    private val http = Http("GitHub") { it.setRequestProperty("Authorization", "Bearer $token") }

    private fun get(path: String): Any? = http.call("GET", api + path).json()
    private fun send(method: String, path: String, body: Any): Any? = http.call(method, api + path, body).json()

    /** Follows `Link: <…>; rel="next"` until exhausted or [limit] items. */
    private fun paged(path: String, limit: Int = 3000): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        var url: String? = api + path + (if ('?' in path) '&' else '?') + "per_page=100"
        while (url != null && out.size < limit) {
            val r = http.call("GET", url)
            out += r.json().arr().map { it.obj() }
            url = nextLink(r.header("Link"))
        }
        return out
    }

    private fun graphql(query: String, variables: Map<String, Any?>): Map<String, Any?> {
        val r = http.call("POST", graphqlUrl, mapOf("query" to query, "variables" to variables)).json().obj()
        r.a("errors").firstOrNull()?.obj()?.str("message")?.let { throw ApiException("GitHub: $it") }
        return r.o("data") ?: emptyMap()
    }

    private fun repo(p: ProjectRef) = "/repos/${p.path}"
    private fun pull(p: ProjectRef, mr: MergeRequest) = "${repo(p)}/pulls/${mr.iid}"

    override fun currentUser(): User = user(get("/user").obj()) ?: throw ApiException(msg("api.emptyUser", "GitHub"))

    /** The pulls API filters only by state, so the other filters and the search apply to its result. */
    override fun mergeRequests(project: ProjectRef, filter: MrFilter, me: User?, search: String?): List<MergeRequest> {
        val state = if (filter == MrFilter.MERGED) "closed" else "open"
        val login = me?.username
        return paged("${repo(project)}/pulls?state=$state&sort=updated&direction=desc", limit = 200)
            .filter { m ->
                when (filter) {
                    MrFilter.OPENED -> true
                    MrFilter.REVIEW_REQUESTED -> m.a("requested_reviewers").any { it.obj().str("login") == login }
                    MrFilter.ASSIGNED -> m.a("assignees").any { it.obj().str("login") == login }
                    MrFilter.MINE -> m.o("user")?.str("login") == login
                    MrFilter.MERGED -> m.str("merged_at") != null
                }
            }
            .filter { search.isNullOrBlank() || it.str("title").orEmpty().contains(search.trim(), ignoreCase = true) }
            .map { parsePull(it) }
    }

    override fun mergeRequest(project: ProjectRef, iid: Long): MergeRequest {
        val m = get("${repo(project)}/pulls/$iid").obj()
        return parsePull(m)
    }

    override fun changes(project: ProjectRef, mr: MergeRequest): List<FileChange> =
        paged("${pull(project, mr)}/files").map { parseFile(it) }

    override fun discussions(project: ProjectRef, mr: MergeRequest): List<Discussion> {
        val owner = project.path.substringBefore('/')
        val name = project.path.substringAfter('/')
        val threads = ArrayList<Discussion>()
        var cursor: String? = null
        do {
            val page = graphql(THREADS, mapOf("owner" to owner, "name" to name, "number" to mr.iid, "cursor" to cursor))
                .o("repository")?.o("pullRequest")?.o("reviewThreads") ?: break
            page.a("nodes").mapTo(threads) { parseThread(it.obj(), mr.diffRefs?.headSha) }
            cursor = page.o("pageInfo")?.takeIf { it.bool("hasNextPage") }?.str("endCursor")
        } while (cursor != null)
        return paged("${repo(project)}/issues/${mr.iid}/comments").map { parseIssueComment(it) } + threads
    }

    override fun createDiscussion(project: ProjectRef, mr: MergeRequest, body: String, position: Position?) {
        if (position == null) send("POST", "${repo(project)}/issues/${mr.iid}/comments", mapOf("body" to body))
        else send("POST", "${pull(project, mr)}/comments", commentPayload(body, position, mr.diffRefs?.headSha ?: mr.sha))
    }

    /** General comments have no threads on GitHub: a reply to one is a new comment. */
    override fun reply(project: ProjectRef, mr: MergeRequest, d: Discussion, body: String) {
        val first = d.first ?: return
        if (d.id.startsWith(ISSUE)) send("POST", "${repo(project)}/issues/${mr.iid}/comments", mapOf("body" to body))
        else send("POST", "${pull(project, mr)}/comments/${first.id}/replies", mapOf("body" to body))
    }

    override fun resolve(project: ProjectRef, mr: MergeRequest, d: Discussion, resolved: Boolean) {
        val mutation = if (resolved) "resolveReviewThread" else "unresolveReviewThread"
        graphql("mutation(\$id: ID!) { $mutation(input: {threadId: \$id}) { thread { id } } }", mapOf("id" to d.id))
    }

    override fun approve(project: ProjectRef, mr: MergeRequest) {
        send("POST", "${pull(project, mr)}/reviews", mapOf("event" to "APPROVE", "commit_id" to mr.sha))
    }

    /** A reviewer can't withdraw an approval on GitHub, only dismiss it — that needs write access to the repository. */
    override fun unapprove(project: ProjectRef, mr: MergeRequest) {
        val me = currentUser().username
        val review = paged("${pull(project, mr)}/reviews")
            .lastOrNull { it.o("user")?.str("login") == me && it.str("state") == "APPROVED" } ?: return
        send("PUT", "${pull(project, mr)}/reviews/${review.long("id")}/dismissals",
            mapOf("message" to "Approval withdrawn", "event" to "DISMISS"))
    }

    override fun approvedBy(project: ProjectRef, mr: MergeRequest): List<String> = approvers(paged("${pull(project, mr)}/reviews"))

    companion object {
        private const val GITHUB_API = "https://api.github.com"
        /** Prefix of the ids of general (issue) comments — they are not review threads. */
        private const val ISSUE = "issue:"

        private val THREADS = """
            query(${'$'}owner: String!, ${'$'}name: String!, ${'$'}number: Int!, ${'$'}cursor: String) {
              repository(owner: ${'$'}owner, name: ${'$'}name) {
                pullRequest(number: ${'$'}number) {
                  reviewThreads(first: 100, after: ${'$'}cursor) {
                    pageInfo { hasNextPage endCursor }
                    nodes {
                      id isResolved isOutdated path line startLine originalLine originalStartLine diffSide startDiffSide
                      resolvedBy { login }
                      comments(first: 100) { nodes { databaseId body createdAt url author { login ... on User { name } } } }
                    }
                  }
                }
              }
            }
        """.trimIndent()

        internal fun apiUrl(serverUrl: String): String =
            serverUrl.trimEnd('/').let { if (it.substringAfter("://") == "github.com") GITHUB_API else "$it/api/v3" }

        internal fun nextLink(link: String?): String? =
            link?.split(',')?.firstOrNull { it.contains("rel=\"next\"") }?.substringAfter('<')?.substringBefore('>')

        internal fun user(m: Map<String, Any?>?): User? = m?.let {
            val login = it.str("login") ?: ""
            User(it.long("id") ?: 0, login, it.str("name")?.takeIf { n -> n.isNotBlank() } ?: login)
        }

        /** The merge base is computed by git after fetching (see MrReviewService.ensureCommits). */
        internal fun parsePull(m: Map<String, Any?>): MergeRequest {
            val base = m.o("base")
            val head = m.o("head")
            val baseSha = base?.str("sha")
            val headSha = head?.str("sha")
            val number = m.long("number") ?: 0
            return MergeRequest(
                iid = number,
                title = m.str("title") ?: "",
                description = m.str("body") ?: "",
                state = if (m.str("merged_at") != null) "merged" else m.str("state") ?: "",
                draft = m.bool("draft"),
                author = user(m.o("user")),
                sourceBranch = head?.str("ref") ?: "",
                targetBranch = base?.str("ref") ?: "",
                webUrl = m.str("html_url") ?: "",
                sha = headSha,
                diffRefs = if (baseSha != null && headSha != null) DiffRefs(null, baseSha, headSha) else null,
                updatedAt = m.str("updated_at"),
                userNotesCount = (m.int("comments") ?: 0) + (m.int("review_comments") ?: 0),
                hasConflicts = m["mergeable"] == false,
                fetchRef = "refs/pull/$number/head",
            )
        }

        /** [FileChange.diff] is GitHub's `patch` — hunks only, like GitLab's; none for binary and huge files. */
        internal fun parseFile(m: Map<String, Any?>): FileChange {
            val path = m.str("filename") ?: ""
            val status = m.str("status")
            val patch = m.str("patch")
            return FileChange(
                oldPath = m.str("previous_filename") ?: path,
                newPath = path,
                newFile = status == "added",
                deletedFile = status == "removed",
                renamedFile = status == "renamed",
                diff = patch ?: "",
                tooLarge = patch == null && (m.int("changes") ?: 0) > 0,
            )
        }

        /** A review thread; an outdated one keeps its original lines and is drawn only on the Discussion tab. */
        internal fun parseThread(t: Map<String, Any?>, headSha: String?): Discussion {
            val outdated = t.bool("isOutdated")
            val line = (if (outdated) null else t.int("line")) ?: t.int("originalLine")
            val start = (if (outdated) null else t.int("startLine")) ?: t.int("originalStartLine")
            val right = t.str("diffSide") != "LEFT"
            val startRight = (t.str("startDiffSide") ?: t.str("diffSide")) != "LEFT"
            fun point(l: Int, onRight: Boolean) = LinePoint("", if (onRight) "new" else "old", if (onRight) null else l, if (onRight) l else null)
            val path = t.str("path")
            val position = Position(
                null, null, headSha, path, path,
                oldLine = if (right) null else line,
                newLine = if (right) line else null,
                lineRange = if (start != null && line != null) LineRange(point(start, startRight), point(line, right)) else null,
                outdated = outdated,
            )
            val resolved = t.bool("isResolved")
            val resolvedBy = user(t.o("resolvedBy"))
            val comments = t.o("comments")?.a("nodes").orEmpty().map { it.obj() }
            val notes = comments.map { c ->
                Note(
                    id = c.long("databaseId") ?: 0, body = c.str("body") ?: "", author = user(c.o("author")),
                    createdAt = c.str("createdAt"), system = false, resolvable = true, resolved = resolved,
                    position = position, resolvedBy = resolvedBy,
                )
            }
            return Discussion(t.str("id") ?: "", notes, comments.firstOrNull()?.str("url"))
        }

        internal fun parseIssueComment(m: Map<String, Any?>): Discussion {
            val note = Note(
                id = m.long("id") ?: 0, body = m.str("body") ?: "", author = user(m.o("user")),
                createdAt = m.str("created_at"), system = false, resolvable = false, resolved = false, position = null,
            )
            return Discussion(ISSUE + note.id, listOf(note), m.str("html_url"))
        }

        /** Body of a line comment: RIGHT side when the point has a line of the new version, LEFT otherwise. */
        internal fun commentPayload(body: String, p: Position, headSha: String?): Map<String, Any?> {
            val payload = linkedMapOf<String, Any?>(
                "body" to body,
                "commit_id" to headSha,
                "path" to (p.newPath ?: p.oldPath),
                "line" to (p.newLine ?: p.oldLine),
                "side" to if (p.newLine != null) "RIGHT" else "LEFT",
            )
            p.lineRange?.takeIf { p.isMultiLine }?.start?.let { s ->
                payload["start_line"] = s.newLine ?: s.oldLine
                payload["start_side"] = if (s.newLine != null) "RIGHT" else "LEFT"
            }
            return payload
        }

        /** Users whose latest decisive review is an approval (comment-only reviews don't change the state). */
        internal fun approvers(reviews: List<Map<String, Any?>>): List<String> {
            val last = LinkedHashMap<String, String>()
            for (r in reviews) {
                val login = r.o("user")?.str("login") ?: continue
                val state = r.str("state") ?: continue
                if (state != "COMMENTED" && state != "PENDING") last[login] = state
            }
            return last.filterValues { it == "APPROVED" }.keys.toList()
        }
    }
}
