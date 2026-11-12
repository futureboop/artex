package io.artex.mobile.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

enum class ChatUploadScope(val wireValue: String) { TASK("task"), SESSION("session") }

data class ChatAttachment(
    val name: String,
    val path: String,
    val size: Long,
    val abs: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("path", path).put("size", size).apply {
        if (abs.isNotBlank()) put("abs", abs)
    }
}

// The server caps the complete multipart request at 128 MiB. Leave room for
// boundaries and headers, and stream provider data without buffering it in RAM.
private const val MAX_CHAT_UPLOAD_BYTES = 120L * 1024 * 1024
private val safeUploadId = Regex("^[A-Za-z0-9_-]+$")
private val uploadHttp = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(90, TimeUnit.SECONDS)
    .writeTimeout(90, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .build()

private data class LocalAttachment(val uri: Uri, val name: String, val size: Long?, val mime: MediaType)

private fun Context.localAttachment(uri: Uri): LocalAttachment {
    require(uri.scheme == "content") { "请从系统文件选择器选择附件" }
    val resolver = contentResolver
    var displayName: String? = null
    var size: Long? = null
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { index ->
                if (!cursor.isNull(index)) displayName = cursor.getString(index)
            }
            cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { index ->
                if (!cursor.isNull(index)) size = cursor.getLong(index).takeIf { it >= 0 }
            }
        }
    }
    val rawName = displayName?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment ?: "attachment"
    val safeName = rawName.substringAfterLast('/').substringAfterLast('\\')
        .map { if (Character.isISOControl(it) || it == '"') '_' else it }.joinToString("")
        .take(180).ifBlank { "attachment" }
    val mime = resolver.getType(uri)?.toMediaTypeOrNull() ?: "application/octet-stream".toMediaType()
    return LocalAttachment(uri, safeName, size, mime)
}

/** Uploads one user-selected document to an existing task or conversation. */
suspend fun ArtexApi.uploadChatAttachment(
    context: Context, uri: Uri, scope: ChatUploadScope, id: String,
): ChatAttachment = uploadChatAttachments(context, listOf(uri), scope, id).single()

/**
 * The session ID for a conversation is `conv-<conversation.id>`, matching the web UI.
 * The returned attachment objects can be sent unchanged in the chat JSON body.
 */
suspend fun ArtexApi.uploadChatAttachments(
    context: Context, uris: List<Uri>, scope: ChatUploadScope, id: String,
): List<ChatAttachment> = withContext(Dispatchers.IO) {
    require(uris.isNotEmpty()) { "请选择附件" }
    require(uris.size <= 20) { "一次最多上传 20 个附件" }
    require(safeUploadId.matches(id)) { "会话或任务 ID 无效" }
    val currentToken = token?.takeIf { it.isNotBlank() } ?: throw ApiException(401, "请先登录")
    val files = uris.map { context.localAttachment(it) }
    val knownTotal = files.mapNotNull { it.size }.sum()
    require(files.none { (it.size ?: 0) > MAX_CHAT_UPLOAD_BYTES } && knownTotal <= MAX_CHAT_UPLOAD_BYTES) {
        "附件总大小不能超过 120 MiB"
    }
    val transferred = AtomicLong(0)
    val multipart = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
        files.forEach { file ->
            val body = object : RequestBody() {
                override fun contentType(): MediaType = file.mime
                // Some document providers report stale sizes. Stream with an explicit
                // limit instead of trusting ContentResolver metadata as Content-Length.
                override fun contentLength(): Long = -1
                override fun writeTo(sink: BufferedSink) {
                    val input = context.contentResolver.openInputStream(file.uri)
                        ?: throw IOException("无法打开附件：${file.name}")
                    input.use { stream ->
                        val buffer = ByteArray(32 * 1024)
                        while (true) {
                            val count = stream.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            if (transferred.addAndGet(count.toLong()) > MAX_CHAT_UPLOAD_BYTES)
                                throw IOException("附件总大小不能超过 120 MiB")
                            sink.write(buffer, 0, count)
                        }
                    }
                }
            }
            addFormDataPart("file", file.name, body)
        }
    }.build()
    val encodedId = URLEncoder.encode(id, Charsets.UTF_8.name())
    val request = Request.Builder()
        .url("$baseUrl/api/chat/upload?scope=${scope.wireValue}&id=$encodedId")
        .header("Authorization", "Bearer $currentToken")
        .header("Accept", "application/json")
        .header("Cache-Control", "no-store")
        .post(multipart)
        .build()
    uploadHttp.newCall(request).execute().use { response ->
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val message = runCatching { JSONObject(text).optString("error") }.getOrNull()
            throw ApiException(response.code, message?.takeIf { it.isNotBlank() } ?: "上传失败：${response.code}")
        }
        val uploaded = JSONObject(text).optJSONArray("attachments")
        if (uploaded == null || uploaded.length() != files.size) throw IOException("服务器返回的附件数量不符")
        (0 until uploaded.length()).map { index ->
            val item = uploaded.optJSONObject(index) ?: throw IOException("服务器返回的附件格式不正确")
            val path = item.optString("path")
            if (item.optString("name").isBlank() || !path.startsWith("uploads/") || path.contains(".."))
                throw IOException("服务器返回的附件路径无效")
            ChatAttachment(item.optString("name"), path, item.optLong("size"), item.optString("abs"))
        }
    }
}
