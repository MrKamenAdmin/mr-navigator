package me.brekhin.mrnavigator.api

import me.brekhin.mrnavigator.core.UnifiedDiff
import me.brekhin.mrnavigator.util.a
import me.brekhin.mrnavigator.util.bool
import me.brekhin.mrnavigator.util.int
import me.brekhin.mrnavigator.util.long
import me.brekhin.mrnavigator.util.msg
import me.brekhin.mrnavigator.util.o
import me.brekhin.mrnavigator.util.obj
import me.brekhin.mrnavigator.util.str
import java.net.URLEncoder

/**
 * Bitbucket Cloud REST 2.0. With [username] (the Atlassian account e-mail) the token is an API token
 * sent with Basic auth; without it — a repository, project or workspace access token sent as Bearer.
 */
class BitbucketCloudClient(token: String, username: String?) : HostingClient {
    private val api = "https://api.bitbucket.org/2.0"
    private val http = Http("Bitbucket") { it.setRequestProperty("Authorization", basicOrBearer(token, username)) }

    private fun get(url: String): Map<String, Any?> = http.call("GET", if (url.startsWith("http")) url else api + url).json().obj()
    private fun send(method: String, path: String, body: Any?): Any? = http.call(method, api + path, body).json()

    /** Follows the `next` links until exhausted or [limit] items. */
    private fun paged(path: String, limit: Int = 2000): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        var url: String? = api + path
        while (url != null && out.size < limit) {
            val page = get(url)
            out += page.a("values").map { it.obj() }
            url = page.str("next")
        }
        return out
    }

    private fun repo(path: String) = "/repositories/$path"
    private fun pr(p: ProjectRef, mr: MergeRequest) = "${repo(p.path)}/pullrequests/${mr.iid}"

    override fun currentUser(): User = user(get("/user")) ?: throw ApiException(msg("api.emptyUser", "Bitbucket"))

    override fun mergeRequests(project: ProjectRef, filter: MrFilter, me: User?, search: String?): List<MergeRequest> {
        val q = query(filter, me, search)
        val state = if (filter == MrFilter.MERGED) "MERGED" else "OPEN"
        val params = "state=$state&sort=-updated_on&pagelen=50" + (if (q.isNotEmpty()) "&q=" + URLEncoder.encode(q, Charsets.UTF_8) else "")
        return paged("${repo(project.path)}/pullrequests?$params", limit = 200).map { parsePull(it, null, project.path) }
    }

    override fun mergeRequest(project: ProjectRef, iid: Long): MergeRequest {
        val path = "${repo(project.path)}/pullrequests/$iid"
        val m = get(path)
        return parsePull(m, diffRefs(m, project.path, path) { get(it) }, project.path)
    }

    /** Redirects to /diff/…, a raw git diff against the merge base. */
    override fun changes(project: ProjectRef, mr: MergeRequest): List<FileChange> =
        UnifiedDiff.split(http.call("GET", api + pr(project, mr) + "/diff", accept = "text/plain").body)

    override fun discussions(project: ProjectRef, mr: MergeRequest): List<Discussion> =
        threads(paged("${pr(project, mr)}/comments?pagelen=100"))

    override fun createDiscussion(project: ProjectRef, mr: MergeRequest, body: String, position: Position?) {
        send("POST", "${pr(project, mr)}/comments", commentPayload(body, position))
    }

    override fun reply(project: ProjectRef, mr: MergeRequest, d: Discussion, body: String) {
        send("POST", "${pr(project, mr)}/comments", replyPayload(body, d))
    }

    override fun resolve(project: ProjectRef, mr: MergeRequest, d: Discussion, resolved: Boolean) {
        val path = "${pr(project, mr)}/comments/${d.id}/resolve"
        if (resolved) send("POST", path, emptyMap<String, Any?>()) else send("DELETE", path, null)
    }

    override fun approve(project: ProjectRef, mr: MergeRequest) {
        send("POST", "${pr(project, mr)}/approve", emptyMap<String, Any?>())
    }

    override fun unapprove(project: ProjectRef, mr: MergeRequest) {
        send("DELETE", "${pr(project, mr)}/approve", null)
    }

    override fun approvedBy(project: ProjectRef, mr: MergeRequest): List<String> =
        get(pr(project, mr)).a("participants").map { it.obj() }.filter { it.bool("approved") }.mapNotNull { it.o("user")?.str("nickname") }

    companion object {
        internal fun user(m: Map<String, Any?>?): User? = m?.let {
            val nickname = it.str("nickname") ?: it.str("account_id") ?: ""
            User(0, nickname, it.str("display_name") ?: nickname, it.str("uuid"))
        }

        /** The `q` filter of the pull request list: by account uuid (nicknames aren't unique), and the title. */
        internal fun query(filter: MrFilter, me: User?, search: String?): String {
            fun who(field: String) = me?.let { u -> u.accountId?.let { "$field.uuid=${quote(it)}" } ?: "$field.nickname=${quote(u.username)}" }
            return listOfNotNull(
                when (filter) {
                    MrFilter.REVIEW_REQUESTED -> who("reviewers")
                    MrFilter.MINE -> who("author")
                    else -> null
                },
                search?.trim()?.takeIf { it.isNotEmpty() }?.let { "title ~ ${quote(it)}" },
            ).joinToString(" AND ")
        }

        /**
         * Full hashes for the diff — the pull request carries 12-character ones. The head comes from the PR's own
         * commit list, readable with access to the target repository alone (unlike a fork); the merge base is
         * computed by git after fetching. A failed lookup leaves the refs unknown instead of failing the whole
         * pull request: the card, comments and approve still work.
         */
        internal fun diffRefs(m: Map<String, Any?>, repoPath: String, prPath: String, get: (String) -> Map<String, Any?>): DiffRefs? {
            val src = m.o("source")?.o("commit")?.str("hash") ?: return null
            val dst = m.o("destination")?.o("commit")?.str("hash") ?: return null
            fun lookup(path: String) = try {
                get(path)
            } catch (e: ApiException) {
                null
            }
            val head = lookup("$prPath/commits?pagelen=50")?.a("values")
                ?.mapNotNull { it.obj().str("hash") }?.firstOrNull { it.startsWith(src) } ?: return null
            val start = lookup("/repositories/$repoPath/commit/$dst")?.str("hash") ?: return null
            return DiffRefs(null, start, head)
        }

        /** [repoPath] — the repository the PR is in; a source in another one (a fork) is fetched by URL. */
        internal fun parsePull(m: Map<String, Any?>, refs: DiffRefs?, repoPath: String?): MergeRequest {
            val source = m.o("source")
            val sourceRepo = source?.o("repository")?.str("full_name")
            val branch = source?.o("branch")?.str("name") ?: ""
            return MergeRequest(
                iid = m.long("id") ?: 0,
                title = m.str("title") ?: "",
                description = m.o("summary")?.str("raw") ?: m.str("description") ?: "",
                state = m.str("state")?.lowercase() ?: "",
                draft = m.bool("draft"),
                author = user(m.o("author")),
                sourceBranch = branch,
                targetBranch = m.o("destination")?.o("branch")?.str("name") ?: "",
                webUrl = m.o("links")?.o("html")?.str("href") ?: "",
                sha = refs?.headSha,
                diffRefs = refs,
                updatedAt = m.str("updated_on"),
                userNotesCount = m.int("comment_count") ?: 0,
                hasConflicts = false,
                fetchRef = "refs/heads/$branch",
                fetchUrl = sourceRepo?.takeIf { repoPath != null && !it.equals(repoPath, ignoreCase = true) }?.let { "https://bitbucket.org/$it.git" },
            )
        }

        /** Comments come flat; replies point at their parent. A deleted root keeps its live replies together. */
        internal fun threads(comments: List<Map<String, Any?>>): List<Discussion> {
            val byId = comments.associateBy { it.long("id") }
            fun root(c: Map<String, Any?>): Map<String, Any?> {
                var cur = c
                while (true) cur = cur.o("parent")?.long("id")?.let { byId[it] } ?: return cur
            }
            return comments.filterNot { it.bool("deleted") }.groupBy { root(it) }.map { (r, notes) ->
                val position = position(r.o("inline"))
                val resolution = r.o("resolution")
                // Only a live top-level comment can be resolved.
                val resolvable = !r.bool("deleted")
                Discussion(
                    id = r.long("id").toString(),
                    notes = notes.sortedBy { it.str("created_on").orEmpty() }.map { c ->
                        Note(
                            id = c.long("id") ?: 0, body = c.o("content")?.str("raw") ?: "", author = user(c.o("user")),
                            createdAt = c.str("created_on"), system = false, resolvable = resolvable, resolved = resolution != null,
                            position = position, resolvedBy = user(resolution?.o("user")),
                        )
                    },
                    webUrl = r.o("links")?.o("html")?.str("href"),
                )
            }
        }

        /** `to` — a line of the new version, `from` — of the old one; `start_*` — the first line of a range. */
        internal fun position(inline: Map<String, Any?>?): Position? {
            inline ?: return null
            val path = inline.str("path") ?: return null
            val to = inline.int("to")
            val from = inline.int("from")
            val startTo = inline.int("start_to")
            val startFrom = inline.int("start_from")
            fun point(new: Int?, old: Int?) = LinePoint("", if (new != null) "new" else "old", if (new != null) null else old, new)
            val range = if (startTo != null || startFrom != null) LineRange(point(startTo, startFrom), point(to, from)) else null
            return Position(
                null, null, null, path, path,
                oldLine = if (to == null) from else null, newLine = to,
                lineRange = range, outdated = inline.bool("outdated"),
            )
        }

        internal fun commentPayload(body: String, p: Position?): Map<String, Any?> {
            val payload = linkedMapOf<String, Any?>("content" to mapOf("raw" to body))
            if (p != null) {
                val inline = linkedMapOf<String, Any?>("path" to (p.newPath ?: p.oldPath))
                if (p.newLine != null) inline["to"] = p.newLine else inline["from"] = p.oldLine
                p.lineRange?.takeIf { p.isMultiLine }?.start?.let { s ->
                    if (s.newLine != null) inline["start_to"] = s.newLine else inline["start_from"] = s.oldLine
                }
                payload["inline"] = inline
            }
            return payload
        }

        /** A reply goes under the root, or under the first live comment when the root is deleted. */
        internal fun replyPayload(body: String, d: Discussion): Map<String, Any?> =
            mapOf("content" to mapOf("raw" to body), "parent" to mapOf("id" to (d.first?.id ?: d.id.toLong())))

        /** A string literal of Bitbucket's query language. */
        internal fun quote(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
    }
}
