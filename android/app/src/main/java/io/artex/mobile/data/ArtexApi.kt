package io.artex.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.net.URL

data class Task(
    val id: String,
    val title: String,
    val description: String,
    val status: String,
    val findings: Int,
    val lastActivityUnix: Long,
    val pinned: Boolean = false,
    val goal: String = "",
)

data class Finding(
    val id: String,
    val title: String,
    val severity: String,
    val status: String,
    val summary: String,
    val taskId: String,
    val ts: String,
)

data class FindingsPage(val items: List<Finding>, val total: Int, val page: Int)

data class Conversation(
    val id: Long,
    val title: String,
    val agentKey: String,
    val running: Boolean,
)

data class Activity(
    val seq: Long,
    val kind: String,
    val summary: String,
    val ts: String,
    val worker: String,
    val isError: Boolean,
)

class ApiException(val status: Int, message: String) : IOException(message)

/** A small native client for ARTEX's existing JSON API. No WebView or API changes. */
class ArtexApi(baseUrl: String, @Volatile var token: String? = null) {
    val baseUrl: String = normalizeBaseUrl(baseUrl)

    suspend fun authStatus(): Boolean =
        request("GET", "/api/auth/status", authenticated = false).optBoolean("initialized")

    suspend fun login(password: String): String {
        val body = JSONObject().put("username", "ARTEX").put("password", password)
        val received = request("POST", "/api/auth/login", body, authenticated = false)
            .getString("token")
        token = received
        return received
    }

    suspend fun tasks(): List<Task> =
        request("GET", "/api/tasks").optJSONArray("tasks").toObjects().map { obj ->
            val id = obj.optString("id")
            val name = obj.optString("name")
            val description = obj.optString("description")
            val counts = obj.optJSONObject("findings")
            Task(
                id = id,
                title = name.ifBlank { description.ifBlank { "任务 #$id" } },
                description = description,
                status = obj.optString("status"),
                findings = (counts?.optInt("critical") ?: 0) +
                    (counts?.optInt("high") ?: 0) +
                    (counts?.optInt("medium") ?: 0) +
                    (counts?.optInt("low") ?: 0),
                lastActivityUnix = obj.optLong("last_activity_unix"),
                pinned = obj.optBoolean("pinned"),
                goal = obj.optString("goal"),
            )
        }

    suspend fun findings(page: Int = 1, limit: Int = 20, severity: String? = null): FindingsPage {
        val path = "/api/exploration/findings?page=${page.coerceAtLeast(1)}&limit=${limit.coerceIn(1, 200)}" +
            (severity?.let { "&severity=${encode(it)}" } ?: "")
        val response = request("GET", path)
        return FindingsPage(
            items = response.optJSONArray("items").toObjects().map { obj ->
                Finding(
                    id = obj.optString("finding_id").ifBlank { obj.optString("id") },
                    title = obj.optString("name").ifBlank { obj.optString("vulnclass") },
                    severity = obj.optString("severity"),
                    status = obj.optString("status"),
                    summary = obj.optString("summary"),
                    taskId = obj.optString("task_id"),
                    ts = obj.optString("ts"),
                )
            },
            total = response.optInt("total"),
            page = response.optInt("page", page),
        )
    }

    suspend fun conversations(): List<Conversation> =
        request("GET", "/api/conversations").optJSONArray("conversations").toObjects().map { obj ->
            Conversation(
                id = obj.optLong("id"),
                title = obj.optString("title"),
                agentKey = obj.optString("agent_key"),
                running = obj.optBoolean("running"),
            )
        }

    /** Latest main-agent session page. Older pages use the API's `before` cursor. */
    suspend fun taskHistory(taskId: String, limit: Int = 80): List<Activity> {
        val path = "/api/exploration/activity/history?task=${encode(taskId)}&session=main&limit=${limit.coerceIn(1, 500)}"
        return request("GET", path).optJSONArray("items").toActivities()
    }

    suspend fun taskActivityDetail(taskId: String, seq: Long): String =
        request("GET", "/api/exploration/activity/$seq?task=${encode(taskId)}").optString("detail")

    suspend fun conversationMessages(id: Long, limit: Int = 80): List<Activity> =
        request("GET", "/api/conversations/$id/messages?limit=${limit.coerceIn(1, 1000)}")
            .optJSONArray("items").toActivities()

    suspend fun conversationActivityDetail(id: Long, seq: Long): String =
        request("GET", "/api/conversations/$id/messages/$seq").optString("detail")

    suspend fun sendTaskMessage(taskId: String, message: String) {
        request(
            "POST", "/api/chat?task=${encode(taskId)}",
            JSONObject().put("message", message), readTimeoutMs = 90_000,
        )
    }

    suspend fun sendConversationMessage(id: Long, message: String) {
        request(
            "POST", "/api/conversations/$id/messages",
            JSONObject().put("message", message), readTimeoutMs = 90_000,
        )
    }

    suspend fun stopTaskChat(taskId: String) {
        request("POST", "/api/tasks/${encode(taskId)}/chat/stop", JSONObject(), readTimeoutMs = 30_000)
    }

    suspend fun stopConversation(id: Long) {
        request("POST", "/api/conversations/$id/stop", JSONObject(), readTimeoutMs = 30_000)
    }

    private suspend fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        authenticated: Boolean = true,
        readTimeoutMs: Int = 20_000,
    ): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 8_000
            readTimeout = readTimeoutMs
            instanceFollowRedirects = false // Never forward a bearer token to another origin.
            useCaches = false
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cache-Control", "no-store")
            if (authenticated) {
                val currentToken = token?.takeIf { it.isNotBlank() }
                    ?: throw ApiException(401, "请先登录")
                setRequestProperty("Authorization", "Bearer $currentToken")
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) {
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val bytes = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes() } ?: ByteArray(0)
            val text = bytes.toString(Charsets.UTF_8)
            if (status !in 200..299) {
                val error = runCatching { JSONObject(text).optString("error") }.getOrNull()
                throw ApiException(status, error?.takeIf { it.isNotBlank() } ?: "服务器返回 $status")
            }
            if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONArray?.toActivities(): List<Activity> = toObjects().map { obj ->
        Activity(
            seq = obj.optLong("seq"),
            kind = obj.optString("kind"),
            summary = obj.optString("summary"),
            ts = obj.optString("ts"),
            worker = obj.optString("worker"),
            isError = obj.optBoolean("is_error"),
        )
    }

    private fun JSONArray?.toObjects(): List<JSONObject> =
        if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    companion object {
        private fun normalizeBaseUrl(input: String): String {
            val raw = input.trim().trimEnd('/')
            require(raw.isNotEmpty()) { "请输入服务器地址" }
            val withScheme = if ("://" in raw) raw else "http://$raw"
            val uri = URI(withScheme)
            require(uri.scheme == "http" || uri.scheme == "https") { "仅支持 HTTP 或 HTTPS 地址" }
            require(!uri.host.isNullOrBlank() && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
                "服务器地址格式不正确"
            }
            return withScheme
        }
    }
}
