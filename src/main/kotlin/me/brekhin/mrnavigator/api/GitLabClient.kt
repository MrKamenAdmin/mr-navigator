package me.brekhin.mrnavigator.api

import com.intellij.util.io.HttpRequests
import me.brekhin.mrnavigator.util.Json
import me.brekhin.mrnavigator.util.arr
import me.brekhin.mrnavigator.util.obj
import me.brekhin.mrnavigator.util.str
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder

class GitLabException(message: String, val status: Int = 0, cause: Throwable? = null) : IOException(message, cause)

/**
 * GitLab REST API v4 client. All methods block — call them from a background thread.
 * Uses the IDE's HttpRequests, so the IDE proxy and certificate settings apply.
 */
class GitLabClient(serverUrl: String, private val token: String) {
    private val api = serverUrl.trimEnd('/') + "/api/v4"

    private class Page(val body: String, val nextPage: String?)

    private fun call(method: String, path: String, body: Any? = null): Page {
        val url = if (path.startsWith("http")) path else api + path
        val builder = when (method) {
            "GET" -> HttpRequests.request(url)
            "POST" -> HttpRequests.post(url, "application/json")
            "PUT" -> HttpRequests.put(url, "application/json")
            "DELETE" -> HttpRequests.delete(url)
            else -> error("Unsupported method $method")
        }
        return try {
            builder
                .accept("application/json")
                .productNameAsUserAgent()
                .connectTimeout(15_000)
                .readTimeout(60_000)
                .tuner { it.setRequestProperty("PRIVATE-TOKEN", token) }
                .isReadResponseOnError(true)
                .connect { request ->
                    if (body != null) request.write(Json.write(body))
                    val code = (request.connection as HttpURLConnection).responseCode
                    if (code >= 400) throw GitLabException(describe(code, errorMessage(request.readError())), code)
                    val text = request.readString()
                    Page(text, request.connection.getHeaderField("X-Next-Page")?.takeIf { it.isNotBlank() })
                }
        } catch (e: GitLabException) {
            throw e
        } catch (e: HttpRequests.HttpStatusException) {
            throw GitLabException(describe(e.statusCode, errorMessage(e.message)), e.statusCode, e)
        } catch (e: IOException) {
            throw GitLabException(e.message ?: e.javaClass.simpleName, 0, e)
        }
    }

    /** GitLab puts details into {"message": ...} or {"error": ...}. */
    private fun errorMessage(body: String?): String? {
        if (body.isNullOrBlank()) return null
        return try {
            val m = Json.parse(body).obj()
            (m["message"] ?: m["error"])?.let { if (it is String) it else Json.write(it) } ?: body.take(300)
        } catch (e: Exception) {
            body.take(300)
        }
    }

    private fun describe(status: Int, raw: String?): String = when (status) {
        HttpURLConnection.HTTP_UNAUTHORIZED -> "GitLab: токен не принят (401). Проверьте токен в настройках."
        HttpURLConnection.HTTP_FORBIDDEN -> "GitLab: недостаточно прав (403). Токену нужен scope api."
        HttpURLConnection.HTTP_NOT_FOUND -> "GitLab: не найдено (404). ${raw.orEmpty()}"
        HttpURLConnection.HTTP_BAD_REQUEST -> "GitLab отклонил запрос (400): ${raw.orEmpty()}"
        else -> "GitLab: ошибка $status. ${raw.orEmpty()}"
    }

    private fun json(method: String, path: String, body: Any? = null): Any? =
        call(method, path, body).body.let { if (it.isBlank()) null else Json.parse(it) }

    /** Follows X-Next-Page until exhausted (or [limit] items). */
    private fun paged(path: String, limit: Int = 2000): List<Map<String, Any?>> {
        val out = ArrayList<Map<String, Any?>>()
        val sep = if (path.contains('?')) '&' else '?'
        var page: String? = "1"
        while (page != null && out.size < limit) {
            val p = call("GET", "$path${sep}per_page=100&page=$page")
            out += Json.parse(p.body).arr().map { it.obj() }
            page = p.nextPage
        }
        return out
    }

    private fun proj(project: ProjectRef) = "/projects/${project.encodedPath}"
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    fun currentUser(): User = User.from(json("GET", "/user").obj()) ?: throw GitLabException("Пустой ответ /user")

    fun mergeRequests(project: ProjectRef, filter: MrFilter, me: User?, search: String?): List<MergeRequest> {
        val params = StringBuilder("state=${filter.state}&order_by=updated_at&sort=desc")
        when (filter) {
            MrFilter.REVIEW_REQUESTED -> me?.let { params.append("&reviewer_username=${enc(it.username)}") }
            MrFilter.MINE -> me?.let { params.append("&author_username=${enc(it.username)}") }
            MrFilter.ASSIGNED -> me?.let { params.append("&assignee_username=${enc(it.username)}") }
            else -> Unit
        }
        if (!search.isNullOrBlank()) params.append("&search=${enc(search.trim())}")
        return paged("${proj(project)}/merge_requests?$params", limit = 200).map { MergeRequest.from(it) }
    }

    fun mergeRequest(project: ProjectRef, iid: Long): MergeRequest =
        MergeRequest.from(json("GET", "${proj(project)}/merge_requests/$iid").obj())

    /** Files of the MR. Uses /diffs (GitLab 15.7+), falls back to the older /changes. */
    fun changes(project: ProjectRef, iid: Long): List<FileChange> = try {
        paged("${proj(project)}/merge_requests/$iid/diffs").map { FileChange.from(it) }
    } catch (e: GitLabException) {
        if (e.status != HttpURLConnection.HTTP_NOT_FOUND) throw e
        json("GET", "${proj(project)}/merge_requests/$iid/changes").obj()["changes"].arr().map { FileChange.from(it.obj()) }
    }

    fun discussions(project: ProjectRef, iid: Long): List<Discussion> =
        paged("${proj(project)}/merge_requests/$iid/discussions").map { Discussion.from(it) }

    fun createDiscussion(project: ProjectRef, iid: Long, body: String, position: Position?): Discussion {
        val payload = linkedMapOf<String, Any?>("body" to body)
        if (position != null) payload["position"] = position.toJson()
        return Discussion.from(json("POST", "${proj(project)}/merge_requests/$iid/discussions", payload).obj())
    }

    fun reply(project: ProjectRef, iid: Long, discussionId: String, body: String) {
        json("POST", "${proj(project)}/merge_requests/$iid/discussions/$discussionId/notes", mapOf("body" to body))
    }

    fun resolve(project: ProjectRef, iid: Long, discussionId: String, resolved: Boolean) {
        json("PUT", "${proj(project)}/merge_requests/$iid/discussions/$discussionId?resolved=$resolved", emptyMap<String, Any?>())
    }

    /** GitLab commits the suggestions to the source branch, like "Apply suggestion" on the web. */
    fun applySuggestions(ids: List<Long>) {
        if (ids.size == 1) json("PUT", "/suggestions/${ids[0]}/apply", emptyMap<String, Any?>())
        else json("PUT", "/suggestions/batch_apply", mapOf("ids" to ids))
    }

    fun approve(project: ProjectRef, iid: Long, sha: String?) {
        json("POST", "${proj(project)}/merge_requests/$iid/approve", if (sha != null) mapOf("sha" to sha) else emptyMap<String, Any?>())
    }

    fun unapprove(project: ProjectRef, iid: Long) {
        json("POST", "${proj(project)}/merge_requests/$iid/unapprove", emptyMap<String, Any?>())
    }

    /** Usernames of those who approved; empty if approvals are not available on this instance. */
    fun approvedBy(project: ProjectRef, iid: Long): List<String> = try {
        json("GET", "${proj(project)}/merge_requests/$iid/approvals").obj()["approved_by"].arr()
            .mapNotNull { it.obj()["user"].obj().str("username") }
    } catch (e: GitLabException) {
        emptyList()
    }
}

enum class MrFilter(val title: String, val state: String) {
    OPENED("Все открытые", "opened"),
    REVIEW_REQUESTED("Ждут моего ревью", "opened"),
    ASSIGNED("Назначены на меня", "opened"),
    MINE("Мои", "opened"),
    MERGED("Смёрженные", "merged");

    override fun toString() = title
}
