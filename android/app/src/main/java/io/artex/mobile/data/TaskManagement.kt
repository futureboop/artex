package io.artex.mobile.data

import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

data class TaskCategory(val id: Long, val name: String)
data class TaskProfile(val id: Long, val name: String, val model: String)

data class NewTaskInput(
    val name: String,
    val description: String,
    val goal: String,
    val categoryId: Long? = null,
    val llmProfileIds: List<Long> = emptyList(),
    val timeoutSeconds: Int = 0,
    val planHeartbeatSeconds: Int = 0,
    val seedFirstIntent: Boolean = false,
    val coverageEnabled: Boolean = true,
)

data class DeleteTaskOptions(
    val deleteAssets: Boolean = false,
    val deleteTraffic: Boolean = false,
    val deleteFiles: Boolean = false,
    val deleteFindings: Boolean = false,
    val deleteLLMRecords: Boolean = false,
)

data class TaskArchiveSummary(
    val id: Long,
    val taskId: Long,
    val name: String,
    val description: String,
    val state: String,
    val progress: Int,
    val error: String,
    val archivedAt: String,
)

data class TaskArchivePage(val items: List<TaskArchiveSummary>, val total: Int, val page: Int)

private fun pathSegment(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

private fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

suspend fun ArtexApi.taskCategories(): List<TaskCategory> =
    request("GET", "/api/task-categories").optJSONArray("categories").objects().map {
        TaskCategory(it.optLong("id"), it.optString("name"))
    }

suspend fun ArtexApi.taskProfiles(): List<TaskProfile> =
    request("GET", "/api/llm/profiles").optJSONArray("profiles").objects().map {
        TaskProfile(it.optLong("id"), it.optString("name"), it.optString("model"))
    }

suspend fun ArtexApi.createTask(input: NewTaskInput): Task {
    val body = JSONObject()
        .put("name", input.name.trim())
        .put("description", input.description.trim())
        .put("goal", input.goal.trim())
        .put("category_id", input.categoryId ?: JSONObject.NULL)
        .put("llm_profile_ids", JSONArray(input.llmProfileIds))
        .put("timeout_seconds", input.timeoutSeconds)
        .put("plan_heartbeat_seconds", input.planHeartbeatSeconds)
        .put("seed_first_intent", input.seedFirstIntent)
        .put("coverage_enabled", input.coverageEnabled)
    val result = request("POST", "/api/tasks", body, readTimeoutMs = 45_000)
    val id = result.optString("id")
    val description = result.optString("description", input.description.trim())
    val name = result.optString("name", input.name.trim())
    val counts = result.optJSONObject("findings")
    return Task(
        id = id,
        title = name.ifBlank { description.ifBlank { "任务 #$id" } },
        description = description,
        status = result.optString("status"),
        findings = (counts?.optInt("critical") ?: 0) + (counts?.optInt("high") ?: 0) +
            (counts?.optInt("medium") ?: 0) + (counts?.optInt("low") ?: 0),
        lastActivityUnix = result.optLong("last_activity_unix"),
        pinned = result.optBoolean("pinned"),
        goal = result.optString("goal", input.goal.trim()),
    )
}

suspend fun ArtexApi.renameTask(taskId: String, name: String) {
    request("PATCH", "/api/tasks/${pathSegment(taskId)}", JSONObject().put("name", name.trim()))
}

suspend fun ArtexApi.pinTask(taskId: String, pinned: Boolean) {
    request("PATCH", "/api/tasks/${pathSegment(taskId)}", JSONObject().put("pinned", pinned))
}

suspend fun ArtexApi.controlTask(taskId: String, action: String) {
    require(action == "pause" || action == "resume")
    request("POST", "/api/tasks/${pathSegment(taskId)}/control", JSONObject().put("action", action), readTimeoutMs = 45_000)
}

suspend fun ArtexApi.archiveTask(taskId: String) {
    request("POST", "/api/tasks/${pathSegment(taskId)}/archive", JSONObject(), readTimeoutMs = 45_000)
}

suspend fun ArtexApi.deleteTask(taskId: String, options: DeleteTaskOptions) {
    val body = JSONObject()
        .put("delete_assets", options.deleteAssets)
        .put("delete_traffic", options.deleteTraffic)
        .put("delete_files", options.deleteFiles)
        .put("delete_findings", options.deleteFindings)
        .put("delete_llm_records", options.deleteLLMRecords)
    request("DELETE", "/api/tasks/${pathSegment(taskId)}", body, readTimeoutMs = 60_000)
}

suspend fun ArtexApi.taskArchives(page: Int = 1, query: String = ""): TaskArchivePage {
    val path = "/api/task-archives?page=${page.coerceAtLeast(1)}&size=20" +
        query.takeIf { it.isNotBlank() }?.let { "&q=${pathSegment(it.trim())}" }.orEmpty()
    val result = request("GET", path)
    return TaskArchivePage(
        items = result.optJSONArray("items").objects().map {
            TaskArchiveSummary(
                id = it.optLong("id"), taskId = it.optLong("task_id"),
                name = it.optString("task_name"), description = it.optString("task_description"),
                state = it.optString("state"), progress = it.optInt("progress"),
                error = it.optString("error"), archivedAt = it.optString("archived_at"),
            )
        },
        total = result.optInt("total"), page = result.optInt("page", page),
    )
}

suspend fun ArtexApi.restoreTaskArchive(archiveId: Long) {
    request("POST", "/api/task-archives/$archiveId/restore", JSONObject(), readTimeoutMs = 45_000)
}

suspend fun ArtexApi.deleteTaskArchive(archiveId: Long) {
    request("DELETE", "/api/task-archives/$archiveId", JSONObject(), readTimeoutMs = 45_000)
}
