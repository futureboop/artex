package io.artex.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
    val detail: String = "",
)

data class MainSession(val seq: Int, val createdAt: String)

data class TaskHistoryPage(
    val items: List<Activity>,
    val hasMore: Boolean,
    val earliestCursor: Long,
    val mainSessions: List<MainSession>,
    val currentMainSeg: Int?,
)

class ApiException(val status: Int, message: String) : IOException(message)

/** A small native client for ARTEX's existing JSON API. No WebView or API changes. */
class ArtexApi(baseUrl: String, @Volatile var token: String? = null) {
    val baseUrl: String = normalizeBaseUrl(baseUrl)
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

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

    /** One reverse-paginated main-agent segment; bare `main` selects the current segment. */
    suspend fun taskHistory(
        taskId: String, session: String = "main", before: Long = 0, limit: Int = 60,
    ): TaskHistoryPage {
        require(session == "main" || Regex("main:[0-9]+").matches(session)) { "主会话标识无效" }
        val path = "/api/exploration/activity/history?task=${encode(taskId)}&session=${encode(session)}" +
            "&limit=${limit.coerceIn(1, 500)}" + (if (before > 0) "&before=$before" else "")
        val response = request("GET", path)
        val items = response.optJSONArray("items").toActivities()
        return TaskHistoryPage(
            items = items,
            hasMore = response.optBoolean("has_more"),
            earliestCursor = response.optLong("earliest_cursor", items.firstOrNull()?.seq ?: 0),
            mainSessions = response.optJSONArray("main_sessions").toObjects().map { obj ->
                MainSession(obj.optInt("seq"), obj.optString("created_at"))
            },
            currentMainSeg = if (response.has("current_main_seg")) response.optInt("current_main_seg") else null,
        )
    }

    suspend fun taskActivityDetail(taskId: String, seq: Long): String =
        requestActivityDetail("/api/exploration/activity/$seq?task=${encode(taskId)}")

    suspend fun conversationMessages(id: Long, limit: Int = 80): List<Activity> =
        request("GET", "/api/conversations/$id/messages?limit=${limit.coerceIn(1, 1000)}")
            .optJSONArray("items").toActivities()

    suspend fun conversationActivityDetail(id: Long, seq: Long): String =
        requestActivityDetail("/api/conversations/$id/messages/$seq")

    /** Scrolling away from a row cancels its network call, including while the body is being read. */
    private suspend fun requestActivityDetail(path: String): String = suspendCancellableCoroutine { continuation ->
        val currentToken = token?.takeIf { it.isNotBlank() }
        if (currentToken == null) {
            continuation.resumeWithException(ApiException(401, "请先登录"))
            return@suspendCancellableCoroutine
        }
        val request = Request.Builder().url(baseUrl + path)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .header("Authorization", "Bearer $currentToken")
            .get().build()
        val call = http.newBuilder().readTimeout(60, TimeUnit.SECONDS).build().newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val detail = response.use {
                        val body = it.body?.string().orEmpty()
                        if (!it.isSuccessful) {
                            val error = runCatching { JSONObject(body).optString("error") }.getOrNull()
                            throw ApiException(it.code, error?.takeIf(String::isNotBlank) ?: "服务器返回 ${it.code}")
                        }
                        JSONObject(body).optString("detail")
                    }
                    if (continuation.isActive) continuation.resume(detail)
                } catch (e: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }

    suspend fun sendTaskMessage(taskId: String, message: String, attachments: List<ChatAttachment> = emptyList()) {
        val body = JSONObject().put("message", message)
        if (attachments.isNotEmpty()) body.put("attachments", JSONArray(attachments.map { it.toJson() }))
        request(
            "POST", "/api/chat?task=${encode(taskId)}",
            body, readTimeoutMs = 90_000,
        )
    }

    suspend fun sendConversationMessage(id: Long, message: String, attachments: List<ChatAttachment> = emptyList()) {
        val body = JSONObject().put("message", message)
        if (attachments.isNotEmpty()) body.put("attachments", JSONArray(attachments.map { it.toJson() }))
        request(
            "POST", "/api/conversations/$id/messages",
            body, readTimeoutMs = 90_000,
        )
    }

    suspend fun stopTaskChat(taskId: String) {
        request("POST", "/api/tasks/${encode(taskId)}/chat/stop", JSONObject(), readTimeoutMs = 30_000)
    }

    suspend fun stopConversation(id: Long) {
        request("POST", "/api/conversations/$id/stop", JSONObject(), readTimeoutMs = 30_000)
    }

    internal suspend fun request(
        method: String,
        path: String,
        body: JSONObject? = null,
        authenticated: Boolean = true,
        readTimeoutMs: Int = 20_000,
    ): JSONObject = withContext(Dispatchers.IO) {
        val payload = body?.toString()?.toRequestBody("application/json; charset=utf-8".toMediaType())
            ?: if (method == "POST" || method == "PUT" || method == "PATCH")
                "{}".toRequestBody("application/json; charset=utf-8".toMediaType()) else null
        val builder = Request.Builder().url(baseUrl + path)
            .header("Accept", "application/json")
            .header("Cache-Control", "no-store")
            .method(method, payload)
        if (authenticated) {
            val currentToken = token?.takeIf { it.isNotBlank() } ?: throw ApiException(401, "请先登录")
            builder.header("Authorization", "Bearer $currentToken")
        }
        http.newBuilder().readTimeout(readTimeoutMs.toLong(), TimeUnit.MILLISECONDS).build()
            .newCall(builder.build()).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val error = runCatching { JSONObject(text).optString("error") }.getOrNull()
                    throw ApiException(response.code, error?.takeIf { it.isNotBlank() } ?: "服务器返回 ${response.code}")
                }
                if (text.isBlank()) JSONObject() else JSONObject(text)
            }
    }

    internal suspend fun requestText(path: String, accept: String = "text/plain, text/markdown"): String = withContext(Dispatchers.IO) {
        val currentToken = token?.takeIf { it.isNotBlank() } ?: throw ApiException(401, "请先登录")
        val request = Request.Builder().url(baseUrl + path)
            .header("Authorization", "Bearer $currentToken")
            .header("Accept", accept)
            .header("Cache-Control", "no-store")
            .get().build()
        http.newCall(request).execute().use { response ->
            val result = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw ApiException(response.code, "服务器返回 ${response.code}")
            result
        }
    }

    internal suspend fun requestArray(path: String): JSONArray {
        val raw = requestText(path, "application/json").trim()
        return if (raw.isBlank() || raw == "null") JSONArray() else JSONArray(raw)
    }

    private fun JSONArray?.toActivities(): List<Activity> = toObjects().map { obj ->
        Activity(
            seq = obj.optLong("seq"),
            kind = obj.optString("kind"),
            summary = obj.optString("summary"),
            ts = obj.optString("ts"),
            worker = obj.optString("worker"),
            isError = obj.optBoolean("is_error"),
            detail = obj.optString("detail"),
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
