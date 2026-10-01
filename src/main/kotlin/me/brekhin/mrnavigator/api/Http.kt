package me.brekhin.mrnavigator.api

import com.intellij.util.io.HttpRequests
import me.brekhin.mrnavigator.util.Json
import me.brekhin.mrnavigator.util.a
import me.brekhin.mrnavigator.util.msg
import me.brekhin.mrnavigator.util.obj
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLConnection
import java.util.Base64
import java.util.TreeMap

class ApiException(message: String, val status: Int = 0, cause: Throwable? = null) : IOException(message, cause)

/** Body and headers of a response. */
class Response(val body: String, private val headers: Map<String, String>) {
    fun header(name: String): String? = headers[name]
    fun json(): Any? = if (body.isBlank()) null else Json.parse(body)
}

/** Basic auth when a user name is given (API token + e-mail, DC user + token), Bearer otherwise. */
fun basicOrBearer(token: String, username: String?): String =
    if (username.isNullOrBlank()) "Bearer $token"
    else "Basic " + Base64.getEncoder().encodeToString("$username:$token".toByteArray(Charsets.UTF_8))

/**
 * Blocking HTTP through the IDE's HttpRequests, so the IDE proxy and certificate settings apply.
 * [provider] names the server in error messages; [auth] adds the credentials to every request.
 */
class Http(private val provider: String, private val auth: (URLConnection) -> Unit) {
    fun call(method: String, url: String, body: Any? = null, accept: String = "application/json"): Response {
        val builder = when (method) {
            "GET" -> HttpRequests.request(url)
            "POST" -> HttpRequests.post(url, "application/json")
            "PUT" -> HttpRequests.put(url, "application/json")
            "DELETE" -> HttpRequests.delete(url)
            else -> error("Unsupported method $method")
        }
        return try {
            builder
                .accept(accept)
                .productNameAsUserAgent()
                .connectTimeout(15_000)
                .readTimeout(60_000)
                .tuner { auth(it) }
                .isReadResponseOnError(true)
                .connect { request ->
                    if (body != null) request.write(Json.write(body))
                    val connection = request.connection as HttpURLConnection
                    val code = connection.responseCode
                    if (code >= 400) throw ApiException(describe(code, errorMessage(request.readError())), code)
                    val headers = TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER)
                    connection.headerFields.forEach { (k, v) -> if (k != null && v.isNotEmpty()) headers[k] = v.first() }
                    Response(request.readString(), headers)
                }
        } catch (e: ApiException) {
            throw e
        } catch (e: HttpRequests.HttpStatusException) {
            throw ApiException(describe(e.statusCode, errorMessage(e.message)), e.statusCode, e)
        } catch (e: IOException) {
            throw ApiException(e.message ?: e.javaClass.simpleName, 0, e)
        }
    }

    private fun describe(status: Int, raw: String?): String = when (status) {
        HttpURLConnection.HTTP_UNAUTHORIZED -> msg("api.unauthorized", provider)
        HttpURLConnection.HTTP_FORBIDDEN -> msg("api.forbidden", provider, raw.orEmpty())
        HttpURLConnection.HTTP_NOT_FOUND -> msg("api.notFound", provider, raw.orEmpty())
        HttpURLConnection.HTTP_BAD_REQUEST -> msg("api.badRequest", provider, raw.orEmpty())
        else -> msg("api.other", provider, status, raw.orEmpty())
    }

    companion object {
        /**
         * The server's own explanation: GitLab and GitHub {message}, {error}, Bitbucket Cloud {error:{message}},
         * DC {errors:[{message}]}; GitHub puts the reason of a 422 into errors[] next to a generic message.
         */
        internal fun errorMessage(body: String?): String? {
            if (body.isNullOrBlank()) return null
            return try {
                val m = Json.parse(body).obj()
                val error = m["error"]
                val text = (m["message"] ?: (error as? Map<*, *>)?.get("message") ?: error)?.let { if (it is String) it else Json.write(it) }
                val details = m.a("errors").mapNotNull { e -> (e as? String) ?: e.obj()["message"] as? String }.joinToString("; ")
                when {
                    text != null && details.isNotEmpty() -> "$text: $details"
                    else -> text ?: details.ifEmpty { body.take(300) }
                }
            } catch (e: Exception) {
                body.take(300)
            }
        }
    }
}
