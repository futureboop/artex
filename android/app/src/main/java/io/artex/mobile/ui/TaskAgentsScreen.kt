package io.artex.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.artex.mobile.data.ArtexApi
import io.artex.mobile.data.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val AgentsInk = Color(0xFF1C2E2A)
private val AgentsMuted = Color(0xFF73807A)
private val AgentsAccent = Color(0xFF3F6658)
private val AgentsLine = Color(0xFFE3E6DE)
private val AgentsDanger = Color(0xFFB56D55)
private const val AgentPageSize = 100
private const val MaxRunningPages = 10 // 1000 active workers; show an explicit partial-result label beyond that.

private data class AgentWorker(
    val id: String,
    val name: String,
    val summary: String,
    val state: String,
    val createdAt: String,
    val origin: String,
    val inherited: Boolean,
    val sourceTaskId: String,
)

private data class RunningWorkers(val items: List<AgentWorker>, val total: Int)
private data class WorkerPage(val items: List<AgentWorker>, val hasMore: Boolean)
private data class AgentRecent(val summary: String, val timestamp: String)

private fun JSONObject.agentWorker(): AgentWorker {
    val id = optString("id")
    val raw = optString("payload")
    val payload = runCatching { JSONObject(raw) }.getOrNull()
    val agent = listOf("agent_key", "agent_name", "worker_name", "agent")
        .firstNotNullOfOrNull { key ->
            val field = payload?.opt(key)
            when (field) {
                is String -> field.takeIf { it.isNotBlank() }
                is JSONObject -> field.optString("name").ifBlank { field.optString("key") }.takeIf { it.isNotBlank() }
                else -> null
            }
        }
    val summary = payload?.optString("summary").orEmpty().ifBlank {
        if (payload == null) raw else ""
    }.take(600)
    return AgentWorker(
        id = id,
        name = agent ?: "Worker #$id",
        summary = summary,
        state = optString("state"),
        createdAt = optString("ts"),
        origin = optString("origin"),
        inherited = optBoolean("inherited"),
        sourceTaskId = optString("source_task_id"),
    )
}

private fun JSONArray?.agentWorkers(): List<AgentWorker> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it)?.agentWorker() }

private fun mergeWorkers(current: List<AgentWorker>, fresh: List<AgentWorker>): List<AgentWorker> {
    val byId = LinkedHashMap<String, AgentWorker>(current.size + fresh.size)
    current.forEach { byId[it.id] = it }
    fresh.forEach { byId[it.id] = it }
    return byId.values.sortedByDescending { it.id.toLongOrNull() ?: 0L }
}

private fun readableAgentTime(raw: String): String {
    if (raw.isBlank()) return "暂无记录"
    val instant = runCatching { Instant.parse(raw) }.getOrElse {
        runCatching { OffsetDateTime.parse(raw).toInstant() }.getOrNull()
    } ?: return raw.take(19).replace('T', ' ')
    return DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault()).format(instant)
}

private fun workerState(state: String): Pair<String, Color> = when (state) {
    "running" -> "运行中" to AgentsAccent
    "open" -> "等待领取" to AgentsMuted
    "paused" -> "已暂停" to Color(0xFF9B743A)
    "done" -> "已完成" to AgentsAccent
    "blocked" -> "受阻" to AgentsDanger
    "exhausted" -> "步数耗尽" to AgentsDanger
    "stopped" -> "已停止" to AgentsMuted
    "deleted" -> "已移除" to AgentsMuted
    else -> (state.ifBlank { "状态未知" }) to AgentsMuted
}

private suspend fun ArtexApi.runningWorkers(taskId: String): RunningWorkers {
    val encoded = URLEncoder.encode(taskId, Charsets.UTF_8.name())
    val loaded = LinkedHashMap<String, AgentWorker>()
    var total = 0
    var page = 1
    while (page <= MaxRunningPages) {
        val response = request("GET", "/api/exploration/nodes?task=$encoded&kind=intent&state=running&page=$page&size=$AgentPageSize")
        total = response.optInt("total", 0)
        val items = response.optJSONArray("items").agentWorkers()
        items.forEach { loaded[it.id] = it }
        if (items.size < AgentPageSize || loaded.size >= total) break
        page++
    }
    return RunningWorkers(loaded.values.toList(), total)
}

private suspend fun ArtexApi.workerHistory(taskId: String, before: String = "0"): WorkerPage {
    val encoded = URLEncoder.encode(taskId, Charsets.UTF_8.name())
    val response = request("GET", "/api/exploration/intents?task=$encoded&page=1&before=$before&limit=$AgentPageSize")
    return WorkerPage(response.optJSONArray("items").agentWorkers(), response.optBoolean("has_more"))
}

private suspend fun ArtexApi.workerById(taskId: String, id: String): AgentWorker? {
    val before = id.toLongOrNull()?.takeIf { it < Long.MAX_VALUE }?.plus(1) ?: return null
    val encoded = URLEncoder.encode(taskId, Charsets.UTF_8.name())
    val response = request("GET", "/api/exploration/intents?task=$encoded&page=1&before=$before&limit=1")
    return response.optJSONArray("items").agentWorkers().firstOrNull { it.id == id }
}

private suspend fun ArtexApi.agentRecent(taskId: String, session: String): AgentRecent? {
    val encoded = URLEncoder.encode(taskId, Charsets.UTF_8.name())
    val response = request("GET", "/api/exploration/activity/history?task=$encoded&session=$session&limit=30")
    val items = response.optJSONArray("items") ?: return null
    val last = (items.length() - 1 downTo 0).firstNotNullOfOrNull { index ->
        items.optJSONObject(index)?.takeIf { it.optString("summary").isNotBlank() }
    } ?: return null
    return AgentRecent(last.optString("summary"), last.optString("ts"))
}

/** Current agent states plus incrementally paged worker history for one task. */
@Composable
fun TaskAgentsScreen(task: Task, api: ArtexApi) {
    var workers by remember(api, task.id) { mutableStateOf<List<AgentWorker>>(emptyList()) }
    var running by remember(api, task.id) { mutableStateOf<List<AgentWorker>>(emptyList()) }
    var recentlyExited by remember(api, task.id) { mutableStateOf<List<AgentWorker>>(emptyList()) }
    var recentlyStopped by remember(api, task.id) { mutableStateOf<List<AgentWorker>>(emptyList()) }
    var pendingTransitionIds by remember(api, task.id) { mutableStateOf<List<String>>(emptyList()) }
    var runningTotal by remember(api, task.id) { mutableIntStateOf(0) }
    var hasMore by remember(api, task.id) { mutableStateOf(false) }
    var historyLoaded by remember(api, task.id) { mutableStateOf(false) }
    var mainRunning by remember(api, task.id) { mutableStateOf<Boolean?>(null) }
    var mainRecent by remember(api, task.id) { mutableStateOf<AgentRecent?>(null) }
    var plannerRecent by remember(api, task.id) { mutableStateOf<AgentRecent?>(null) }
    var loading by remember(api, task.id) { mutableStateOf(false) }
    var loadingMore by remember(api, task.id) { mutableStateOf(false) }
    var historyError by remember(api, task.id) { mutableStateOf("") }
    var runningError by remember(api, task.id) { mutableStateOf("") }
    var moreError by remember(api, task.id) { mutableStateOf("") }
    var refreshSignal by remember(api, task.id) { mutableIntStateOf(0) }
    var expanded by remember(api, task.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(api, task.id, refreshSignal) {
        while (true) {
            loading = true
            supervisorScope {
                if (!historyLoaded) launch {
                    try {
                        val fresh = api.workerHistory(task.id)
                        workers = mergeWorkers(workers, fresh.items)
                        hasMore = fresh.hasMore
                        historyLoaded = true
                        historyError = ""
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) { historyError = failure.message ?: "Worker 列表读取失败" }
                }
                launch {
                    try {
                        val fresh = api.runningWorkers(task.id)
                        val activeIds = fresh.items.mapTo(HashSet()) { it.id }
                        val vanished = running.filterNot { it.id in activeIds }
                        if (vanished.isNotEmpty()) {
                            recentlyExited = mergeWorkers(recentlyExited, vanished)
                            val vanishedIds = vanished.mapTo(HashSet()) { it.id }
                            recentlyStopped = recentlyStopped.filterNot { it.id in vanishedIds }
                        }
                        val pending = (pendingTransitionIds + vanished.map { it.id }).distinct()
                            .filterNot { it in activeIds }
                        if (pending.isNotEmpty()) {
                            // Resolve a bounded number per poll so mass completions do not flood the API.
                            val resolved = pending.take(20).chunked(4).flatMap { batch ->
                                supervisorScope { batch.map { id -> async {
                                    try { api.workerById(task.id, id) }
                                    catch (cancel: CancellationException) { throw cancel }
                                    catch (_: Exception) { null }
                                } }.mapNotNull { it.await() } }
                            }
                            recentlyStopped = mergeWorkers(recentlyStopped, resolved)
                            pendingTransitionIds = pending.drop(20)
                        }
                        running = fresh.items
                        runningTotal = fresh.total
                        runningError = ""
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) { runningError = failure.message ?: "运行状态读取失败" }
                }
                launch {
                    try {
                        mainRunning = api.request("GET", "/api/tasks/${URLEncoder.encode(task.id, Charsets.UTF_8.name())}/chat/status")
                            .optBoolean("running")
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { mainRunning = null }
                }
                launch {
                    try { mainRecent = api.agentRecent(task.id, "main") }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { /* Keep authoritative mainRunning status. */ }
                }
                launch {
                    try { plannerRecent = api.agentRecent(task.id, "plan") }
                    catch (cancel: CancellationException) { throw cancel }
                    catch (_: Exception) { /* Planner has no authoritative busy flag. */ }
                }
            }
            loading = false
            delay(10_000)
        }
    }

    val runningIds = remember(running) { running.mapTo(HashSet()) { it.id } }
    val otherWorkers = remember(workers, recentlyExited, recentlyStopped, runningIds) {
        mergeWorkers(mergeWorkers(recentlyExited, workers), recentlyStopped).filterNot { it.id in runningIds }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Agent 运行状况", color = AgentsInk, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text("主 Agent · 规划 Agent · 全部 Worker", color = AgentsMuted, fontSize = 12.sp)
                }
                IconButton(onClick = {
                    historyLoaded = false
                    val resolvedIds = recentlyStopped.mapTo(HashSet()) { it.id }
                    pendingTransitionIds = recentlyExited.map { it.id }.filterNot { it in resolvedIds }
                    refreshSignal++
                }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新运行状况", tint = AgentsAccent)
                }
            }
        }
        if (loading && !historyLoaded) item { CircularProgressIndicator(Modifier.size(22.dp), color = AgentsAccent) }
        if (historyError.isNotBlank()) item { Text("历史列表：$historyError", color = AgentsDanger, fontSize = 12.sp) }
        if (runningError.isNotBlank()) item { Text("运行状态：$runningError", color = AgentsDanger, fontSize = 12.sp) }
        item {
            AgentStatusCard("主 Agent", when (mainRunning) {
                true -> "执行中" to AgentsAccent
                false -> "待命" to AgentsMuted
                null -> "状态未读取" to AgentsMuted
            }, mainRecent)
        }
        item {
            val plannerStatus = when (task.status) {
                "paused" -> "任务已暂停" to AgentsMuted
                "done", "failed", "timeout" -> "任务已结束" to AgentsMuted
                else -> "单次运行状态未提供" to AgentsMuted
            }
            AgentStatusCard("规划 Agent", plannerStatus, plannerRecent)
        }
        item {
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("执行中的 Worker", Modifier.weight(1f), color = AgentsInk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("$runningTotal", color = AgentsAccent, fontSize = 16.sp)
            }
            if (runningTotal > running.size) Text("已显示 ${running.size} / $runningTotal；运行项过多，请稍后刷新。",
                color = AgentsDanger, fontSize = 11.sp)
        }
        if (!loading && running.isEmpty() && runningError.isBlank()) item { AgentEmpty("当前没有执行中的 Worker") }
        items(running, key = { "active-${it.id}" }) { worker ->
            WorkerStatusCard(worker, expanded == worker.id) { expanded = if (expanded == worker.id) null else worker.id }
        }
        item {
            Spacer(Modifier.height(5.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("其他 Worker", Modifier.weight(1f), color = AgentsInk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("已加载 ${workers.size}", color = AgentsMuted, fontSize = 11.sp)
            }
            Text("按创建时间逐页加载；已运行结束的旧记录不会反复下载。", color = AgentsMuted, fontSize = 11.sp)
        }
        if (historyLoaded && otherWorkers.isEmpty() && !hasMore) item { AgentEmpty("暂无其他 Worker") }
        items(otherWorkers, key = { "history-${it.id}" }) { worker ->
            WorkerStatusCard(worker, expanded == worker.id, statusIsSnapshot = worker.state == "running") {
                expanded = if (expanded == worker.id) null else worker.id
            }
        }
        if (moreError.isNotBlank()) item { Text(moreError, color = AgentsDanger, fontSize = 12.sp) }
        if (hasMore) item {
            Button(onClick = {
                if (loadingMore) return@Button
                val before = workers.minByOrNull { it.id.toLongOrNull() ?: Long.MAX_VALUE }?.id ?: return@Button
                scope.launch {
                    loadingMore = true
                    moreError = ""
                    try {
                        val page = api.workerHistory(task.id, before)
                        workers = mergeWorkers(workers, page.items)
                        hasMore = page.hasMore
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (failure: Exception) { moreError = failure.message ?: "更多 Worker 加载失败" }
                    finally { loadingMore = false }
                }
            }, enabled = !loadingMore, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(9.dp)) {
                if (loadingMore) CircularProgressIndicator(Modifier.size(17.dp), color = Color.White, strokeWidth = 2.dp)
                else Text("加载更多 Worker")
            }
        }
    }
}

@Composable
private fun AgentStatusCard(title: String, status: Pair<String, Color>, recent: AgentRecent?) {
    Surface(shape = RoundedCornerShape(12.dp), color = Color.White.copy(alpha = 0.93f),
        border = BorderStroke(1.dp, AgentsLine)) {
        Column(Modifier.fillMaxWidth().padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), color = AgentsInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                StatusDot(status.second)
                Spacer(Modifier.size(5.dp))
                Text(status.first, color = status.second, fontSize = 11.sp)
            }
            if (recent != null) {
                Spacer(Modifier.height(8.dp))
                Text("最近记录 · ${readableAgentTime(recent.timestamp)}", color = AgentsMuted, fontSize = 11.sp)
                if (recent.summary.isNotBlank()) Text(recent.summary, color = AgentsInk, fontSize = 12.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun WorkerStatusCard(worker: AgentWorker, expanded: Boolean, statusIsSnapshot: Boolean = false,
                             onClick: () -> Unit) {
    val state = if (statusIsSnapshot) "旧状态待更新" to AgentsMuted else workerState(worker.state)
    Surface(shape = RoundedCornerShape(12.dp), color = Color.White.copy(alpha = 0.93f),
        border = BorderStroke(1.dp, AgentsLine)) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(worker.name, Modifier.weight(1f), color = AgentsInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                StatusDot(state.second)
                Spacer(Modifier.size(5.dp))
                Text(state.first, color = state.second, fontSize = 11.sp)
            }
            if (worker.summary.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(worker.summary, color = AgentsInk, fontSize = 12.sp, lineHeight = 18.sp,
                    maxLines = if (expanded) 20 else 2, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(6.dp))
            Text("#${worker.id}  ·  创建 ${readableAgentTime(worker.createdAt)}", color = AgentsMuted, fontSize = 11.sp)
            if (expanded) {
                HorizontalDivider(Modifier.padding(vertical = 9.dp), color = AgentsLine)
                Text("来源：${worker.origin.ifBlank { "未知" }}", color = AgentsMuted, fontSize = 11.sp)
                if (worker.inherited) Text("继承自任务 #${worker.sourceTaskId}", color = AgentsMuted, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun StatusDot(color: Color) {
    Surface(modifier = Modifier.size(7.dp), shape = CircleShape, color = color) {}
}

@Composable
private fun AgentEmpty(text: String) {
    Text(text, color = AgentsMuted, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
}
