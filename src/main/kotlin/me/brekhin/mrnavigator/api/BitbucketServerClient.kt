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
import java.time.Instant

/** Bitbucket Data Center (Server) REST 1.0. With [username] the HTTP access token goes with Basic auth, otherwise as Bearer. */
class BitbucketServerClient(serverUrl: String, token: String, username: String?) : HostingClient {
    private val api = serverUrl.trimEnd('/') + "/rest/api/latest"
    private val http = Http("Bitbucket") { it.setRequestProperty("Authorization", basicOrBearer(token, username)) }

    private fun get(path: String): Map<String, Any?> = http.call("GET", api + path).json().obj()
    private fun send(method: String, path: String, body: Any): Any? = http.call(method, api + path, body).json()

    /** Follows isLastPage / nextPageStart until exhausted or [limit] items. */
    private fun paged(path: String, limit: Int = 2000): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        val sep = if ('?' in path) '&' else '?'
        var start: Long? = 0
        while (start != null && out.size < limit) {
            val page = get("$path${sep}limit=100&start=$start")
            out += page.a("values").map { it.obj() }
            start = if (page.bool("isLastPage")) null else page.long("nextPageStart")
        }
        return out
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
    /** "PROJ/repo" (or "~user/repo" for a personal repository) → /projects/PROJ/repos/repo */
    private fun repo(p: ProjectRef) = "/projects/${enc(p.path.substringBefore('/'))}/repos/${enc(p.path.substringAfter('/'))}"
    private fun pr(p: ProjectRef, mr: MergeRequest) = "${repo(p)}/pull-requests/${mr.iid}"

    /** There is no "current user" endpoint: any response names the user in X-AUSERNAME, the rest comes from /users. */
    override fun currentUser(): User {
        val name = http.call("GET", "$api/application-properties").header("X-AUSERNAME")
            ?: throw ApiException(msg("api.emptyUser", "Bitbucket"))
        val found = get("/users?filter=${enc(name)}").a("values").map { it.obj() }.firstOrNull { it.str("name") == name }
        return user(found) ?: User(0, name, name)
    }

    override fun mergeRequests(project: ProjectRef, filter: MrFilter, me: User?, search: String?): List<MergeRequest> {
        val params = StringBuilder("state=${if (filter == MrFilter.MERGED) "MERGED" else "OPEN"}&order=NEWEST")
        when (filter) {
            MrFilter.REVIEW_REQUESTED -> me?.let { params.append("&role.1=REVIEWER&username.1=${enc(it.username)}") }
            MrFilter.MINE -> me?.let { params.append("&role.1=AUTHOR&username.1=${enc(it.username)}") }
            else -> Unit
        }
        if (!search.isNullOrBlank()) params.append("&filterText=${enc(search.trim())}")
        return paged("${repo(project)}/pull-requests?$params", limit = 200).map { parsePull(it, null) }
    }

    override fun mergeRequest(project: ProjectRef, iid: Long): MergeRequest {
        val m = get("${repo(project)}/pull-requests/$iid")
        // 204 without a body when the branches have no common ancestor.
        val base = get("${repo(project)}/pull-requests/$iid/merge-base").str("id")
        return parsePull(m, base)
    }

    /** Raw git diff against the merge base. */
    override fun changes(project: ProjectRef, mr: MergeRequest): List<FileChange> =
        UnifiedDiff.split(http.call("GET", api + pr(project, mr) + ".diff", accept = "text/plain").body)

    override fun discussions(project: ProjectRef, mr: MergeRequest): List<Discussion> =
        threads(paged("${pr(project, mr)}/activities")).map { it.copy(webUrl = "${mr.webUrl}?commentId=${it.id}") }

    override fun createDiscussion(project: ProjectRef, mr: MergeRequest, body: String, position: Position?) {
        send("POST", "${pr(project, mr)}/comments", commentPayload(body, position, mr.diffRefs))
    }

    override fun reply(project: ProjectRef, mr: MergeRequest, d: Discussion, body: String) {
        send("POST", "${pr(project, mr)}/comments", mapOf("text" to body, "parent" to mapOf("id" to d.id.toLong())))
    }

    /** Best effort: the REST reference doesn't describe thread resolution; Bitbucket 8+ takes threadResolved on update. */
    override fun resolve(project: ProjectRef, mr: MergeRequest, d: Discussion, resolved: Boolean) {
        val path = "${pr(project, mr)}/comments/${d.id}"
        send("PUT", path, mapOf("version" to (get(path).int("version") ?: 0), "threadResolved" to resolved))
    }

    override fun approve(project: ProjectRef, mr: MergeRequest) = setStatus(project, mr, "APPROVED")

    override fun unapprove(project: ProjectRef, mr: MergeRequest) = setStatus(project, mr, "UNAPPROVED")

    private fun setStatus(project: ProjectRef, mr: MergeRequest, status: String) {
        send("PUT", "${pr(project, mr)}/participants/${enc(currentUser().username)}", mapOf("status" to status))
    }

    override fun approvedBy(project: ProjectRef, mr: MergeRequest): List<String> =
        get(pr(project, mr)).a("reviewers").map { it.obj() }.filter { it.bool("approved") }.mapNotNull { it.o("user")?.str("slug") }

    companion object {
        internal fun user(m: Map<String, Any?>?): User? = m?.let {
            val slug = it.str("slug") ?: it.str("name") ?: ""
            User(it.long("id") ?: 0, slug, it.str("displayName") ?: slug)
        }

        /** Bitbucket DC gives epoch milliseconds; the UI expects ISO timestamps. */
        internal fun iso(ms: Long?): String? = ms?.let { Instant.ofEpochMilli(it).toString() }

        internal fun parsePull(m: Map<String, Any?>, mergeBase: String?): MergeRequest {
            val from = m.o("fromRef")
            val to = m.o("toRef")
            val head = from?.str("latestCommit")
            val start = to?.str("latestCommit")
            val id = m.long("id") ?: 0
            return MergeRequest(
                iid = id,
                title = m.str("title") ?: "",
                description = m.str("description") ?: "",
                state = m.str("state")?.lowercase() ?: "",
                draft = m.bool("draft"),
                author = user(m.o("author")?.o("user")),
                sourceBranch = from?.str("displayId") ?: "",
                targetBranch = to?.str("displayId") ?: "",
                webUrl = m.o("links")?.a("self")?.firstOrNull()?.obj()?.str("href") ?: "",
                sha = head,
                diffRefs = if (mergeBase != null && start != null && head != null) DiffRefs(mergeBase, start, head) else null,
                updatedAt = iso(m.long("updatedDate")),
                userNotesCount = m.o("properties")?.int("commentCount") ?: 0,
                hasConflicts = false,
                fetchRef = "refs/pull-requests/$id/from",
            )
        }

        /** Threads from the activity feed: each added root comment carries its replies, nested. */
        internal fun threads(activities: List<Map<String, Any?>>): List<Discussion> {
            val deleted = activities.filter { it.str("commentAction") == "DELETED" }.mapNotNull { it.o("comment")?.long("id") }.toSet()
            return activities
                .filter { it.str("action") == "COMMENTED" && it.str("commentAction") == "ADDED" }
                .mapNotNull { a ->
                    val c = a.o("comment") ?: return@mapNotNull null
                    val id = c.long("id") ?: return@mapNotNull null
                    if (id in deleted) return@mapNotNull null
                    val position = position(a.o("commentAnchor") ?: c.o("anchor"))
                    val resolved = c.bool("threadResolved") || c.str("state") == "RESOLVED"
                    val resolver = user(c.o("threadResolver") ?: c.o("resolver"))
                    val notes = ArrayList<Note>()
                    fun add(n: Map<String, Any?>) {
                        notes += Note(
                            id = n.long("id") ?: 0, body = n.str("text") ?: "", author = user(n.o("author")),
                            createdAt = iso(n.long("createdDate")), system = false, resolvable = true, resolved = resolved,
                            position = position, resolvedBy = resolver,
                        )
                        n.a("comments").forEach { add(it.obj()) }
                    }
                    add(c)
                    Discussion(id.toString(), notes.sortedBy { it.createdAt.orEmpty() })
                }
        }

        /** fileType FROM — a line of the old version, TO — of the new one; an orphaned anchor lost its code. */
        internal fun position(anchor: Map<String, Any?>?): Position? {
            anchor ?: return null
            val path = anchor.str("path") ?: return null
            val line = anchor.int("line")
            val old = anchor.str("fileType") == "FROM"
            val marker = anchor.o("multilineMarker")
            val start = marker?.int("startLine")
            val startOld = marker?.str("startLineType") == "REMOVED"
            fun point(l: Int, onOld: Boolean) = LinePoint("", if (onOld) "old" else "new", if (onOld) l else null, if (onOld) null else l)
            return Position(
                null, null, null, anchor.str("srcPath") ?: path, path,
                oldLine = if (old) line else null, newLine = if (old) null else line,
                lineRange = if (start != null && line != null) LineRange(point(start, startOld), point(line, old)) else null,
                outdated = anchor.bool("orphaned"),
            )
        }

        internal fun commentPayload(body: String, p: Position?, refs: DiffRefs?): Map<String, Any?> {
            val payload = linkedMapOf<String, Any?>("text" to body)
            p ?: return payload
            val path = p.newPath ?: p.oldPath
            val (line, lineType, fileType) = when {
                p.newLine == null -> Triple(p.oldLine, "REMOVED", "FROM")
                p.oldLine == null -> Triple(p.newLine, "ADDED", "TO")
                else -> Triple(p.newLine, "CONTEXT", "TO")
            }
            val anchor = linkedMapOf<String, Any?>(
                "diffType" to "EFFECTIVE", "path" to path, "srcPath" to (p.oldPath ?: path),
                "line" to line, "lineType" to lineType, "fileType" to fileType,
                "fromHash" to refs?.baseSha, "toHash" to refs?.headSha,
            )
            p.lineRange?.takeIf { p.isMultiLine }?.start?.let { s ->
                anchor["multilineMarker"] = mapOf(
                    "startLine" to (s.newLine ?: s.oldLine),
                    "startLineType" to when {
                        s.newLine == null -> "REMOVED"
                        s.type == "new" -> "ADDED"
                        else -> "CONTEXT"
                    },
                )
            }
            payload["anchor"] = anchor
            return payload
        }
    }
}
