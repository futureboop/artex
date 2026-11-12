package io.artex.mobile.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.artex.mobile.data.ArtexApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private val OpsInk = Color(0xFF1C2E2A)
private val OpsAccent = Color(0xFF3F6658)
private val OpsMuted = Color(0xFF73807A)
private val OpsLine = Color(0xFFE3E6DE)
private val OpsDanger = Color(0xFFB56D55)
private const val PAGE_SIZE = 50
private fun opsPageSize(feature: FeatureDestination): Int =
    if (feature == FeatureDestination.COMMANDS) 20 else PAGE_SIZE

/** Native operational screens for the data-heavy sections of the full sidebar. */
@Composable
fun OperationsScreen(feature: FeatureDestination, api: ArtexApi, onBack: () -> Unit) {
    when (feature) {
        FeatureDestination.TRAFFIC, FeatureDestination.COMMANDS,
        FeatureDestination.LLM_RECORDS, FeatureDestination.ASSETS -> PagedOperations(feature, api, onBack)
        FeatureDestination.WORKSPACE -> WorkspaceOperations(api, onBack)
        FeatureDestination.SYNC -> SyncOperations(api, onBack)
        else -> FeatureExplorer(feature, api, onBack)
    }
}

@Composable
private fun OpsTopBar(title: String, subtitle: String, onBack: () -> Unit, onRefresh: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) {
            Icon(Icons.Outlined.ArrowBack, contentDescription = "返回", tint = OpsInk)
        }
        Column(Modifier.weight(1f).padding(start = 5.dp)) {
            Text(title, color = OpsInk, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = OpsMuted, fontSize = 11.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onRefresh) {
            Icon(Icons.Outlined.Refresh, contentDescription = "刷新", tint = OpsAccent)
        }
    }
}

@Composable
private fun OpsMessage(message: String, danger: Boolean = false) {
    Surface(shape = RoundedCornerShape(11.dp), color = Color.White.copy(alpha = 0.95f),
        border = BorderStroke(1.dp, OpsLine)) {
        Text(message, color = if (danger) OpsDanger else OpsMuted, fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().padding(16.dp))
    }
}

private fun opsPath(feature: FeatureDestination, page: Int, query: String,
    task: String = "", assetType: String = "root_domain"): String {
    val q = opsEncode(query)
    val size = opsPageSize(feature)
    return when (feature) {
        FeatureDestination.TRAFFIC -> "/api/traffic?page=" + page + "&size=" + size +
            (if (query.isBlank()) "" else "&q=" + q)
        FeatureDestination.COMMANDS -> "/api/commands?page=" + page + "&size=" + size +
            (if (query.isBlank()) "" else "&q=" + q)
        FeatureDestination.LLM_RECORDS -> "/api/llm/records?page=" + page + "&size=" + size +
            (if (query.isBlank()) "" else "&model=" + q) +
            (if (task.isBlank()) "" else "&task=" + opsEncode(task))
        FeatureDestination.ASSETS -> "/api/assets?type=" + opsEncode(assetType) +
            "&limit=" + size + "&offset=" + (page * size) +
            (if (query.isBlank()) "" else "&dsl=" + q)
        else -> error("Unsupported paged feature")
    }
}

private fun opsCollection(feature: FeatureDestination): String = when (feature) {
    FeatureDestination.TRAFFIC -> "exchanges"
    FeatureDestination.COMMANDS -> "commands"
    FeatureDestination.LLM_RECORDS -> "records"
    FeatureDestination.ASSETS -> "assets"
    else -> ""
}

private fun opsSearchHint(feature: FeatureDestination): String = when (feature) {
    FeatureDestination.TRAFFIC -> "搜索 URL、主机或请求内容"
    FeatureDestination.COMMANDS -> "搜索工具输入"
    FeatureDestination.LLM_RECORDS -> "按模型筛选"
    FeatureDestination.ASSETS -> "资产 DSL 查询"
    else -> "搜索"
}

@Composable
private fun PagedOperations(feature: FeatureDestination, api: ArtexApi, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var showCompanies by remember(feature, api) { mutableStateOf(false) }
    var showTrafficCleanup by remember(feature, api) { mutableStateOf(false) }
    var showTaskPicker by remember(feature, api) { mutableStateOf(false) }
    var confirmLlmDelete by remember(feature, api) { mutableStateOf(false) }
    var llmDeleting by remember(feature, api) { mutableStateOf(false) }
    var llmTask by remember(feature, api) { mutableStateOf("") }
    var assetType by remember(feature, api) { mutableStateOf("root_domain") }
    var llmTasks by remember(feature, api) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var actionError by remember(feature, api) { mutableStateOf<String?>(null) }
    var input by remember(feature, api) { mutableStateOf("") }
    var query by remember(feature, api) { mutableStateOf("") }
    var page by remember(feature, api) { mutableIntStateOf(0) }
    var revision by remember(feature, api) { mutableIntStateOf(0) }
    var response by remember(feature, api) { mutableStateOf<JSONObject?>(null) }
    var loading by remember(feature, api) { mutableStateOf(true) }
    var error by remember(feature, api) { mutableStateOf<String?>(null) }
    var selected by remember(feature, api) { mutableStateOf<JSONObject?>(null) }
    var detail by remember(feature, api) { mutableStateOf<JSONObject?>(null) }
    var detailLoading by remember(feature, api) { mutableStateOf(false) }
    var detailError by remember(feature, api) { mutableStateOf<String?>(null) }
    if (feature == FeatureDestination.ASSETS && showCompanies) {
        CompanyOperations(api) { showCompanies = false }
        return
    }

    LaunchedEffect(feature, api, page, query, llmTask, assetType, revision) {
        loading = true
        error = null
        response = null
        try {
            response = api.request("GET", opsPath(feature, page, query, llmTask, assetType))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            error = failure.message ?: "加载失败"
            response = null
        } finally {
            loading = false
        }
    }
    LaunchedEffect(feature, api, revision) {
        if (feature != FeatureDestination.LLM_RECORDS) return@LaunchedEffect
        try {
            val response = api.request("GET", "/api/llm/records/tasks")
            val array = response.optJSONArray("tasks")
            llmTasks = if (array == null) emptyList() else (0 until array.length())
                .mapNotNull(array::optJSONObject)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            llmTasks = emptyList()
        }
    }
    LaunchedEffect(feature, api, selected) {
        val item = selected ?: return@LaunchedEffect
        detail = item
        detailError = null
        val id = item.optString("id").takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        val path = when (feature) {
            FeatureDestination.TRAFFIC -> "/api/traffic/exchange?id=" + opsEncode(id)
            FeatureDestination.LLM_RECORDS -> "/api/llm/records/" + opsEncode(id)
            else -> null
        } ?: return@LaunchedEffect
        detailLoading = true
        try {
            detail = api.request("GET", path)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            detailError = failure.message ?: "详情加载失败"
        } finally {
            detailLoading = false
        }
    }
    BackHandler(enabled = selected != null) { selected = null }
    BackHandler(enabled = selected == null, onBack = onBack)

    val rows = remember(response, feature) {
        val array = response?.optJSONArray(opsCollection(feature))
        if (array == null) emptyList() else (0 until array.length()).mapNotNull(array::optJSONObject)
    }
    val total = response?.optInt("total", response?.optInt("count", rows.size) ?: rows.size) ?: 0
    Column(Modifier.fillMaxSize()) {
        OpsTopBar(feature.title, "现有 API · 原生浏览", onBack) { revision++ }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(input, onValueChange = { input = it }, singleLine = true,
                        placeholder = { Text(opsSearchHint(feature), fontSize = 12.sp) },
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp))
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = { page = 0; query = input.trim(); revision++ },
                        shape = RoundedCornerShape(10.dp)) { Text("搜索") }
                }
            }
            if (feature == FeatureDestination.ASSETS) item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("按类型浏览 · 支持资产 DSL 查询", color = OpsMuted, fontSize = 11.sp,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { showCompanies = true }) { Text("企业与范围") }
                }
            }
            if (feature == FeatureDestination.ASSETS) item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    listOf(
                        "root_domain" to "根域名",
                        "ip" to "IP",
                        "subdomain" to "子域名",
                        "app" to "应用",
                        "service" to "服务",
                        "endpoint" to "接口",
                    ).forEach { (type, label) ->
                        FilterChip(selected = assetType == type, onClick = {
                            if (assetType != type) {
                                assetType = type
                                page = 0
                            }
                        }, label = { Text(label) })
                    }
                }
            }
            if (feature == FeatureDestination.TRAFFIC) item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { showTrafficCleanup = true }) {
                        Text("清理流量", color = OpsDanger)
                    }
                }
            }
            if (feature == FeatureDestination.LLM_RECORDS) item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showTaskPicker = true }, modifier = Modifier.weight(1f)) {
                        Text("任务：" + llmTask.ifBlank { "全部" }, maxLines = 1,
                            overflow = TextOverflow.Ellipsis)
                    }
                    TextButton(onClick = { confirmLlmDelete = true }, enabled = llmTask.isNotBlank()) {
                        Text("清空记录", color = OpsDanger)
                    }
                }
            }
            if (actionError != null) item { OpsMessage(actionError ?: "", true) }
            if (error != null) item { OpsMessage("加载失败：" + error, true) }
            if (!loading && error == null && rows.isEmpty()) item { OpsMessage("暂无匹配记录") }
            item {
                Text("第 " + (page + 1) + " 页 · " + rows.size + " / " + total + " 条",
                    color = OpsMuted, fontSize = 12.sp)
            }
            items(rows, key = { it.optString("id") + ":" + rows.indexOf(it) }) { row ->
                OpsDataRow(opsRowTitle(feature, row), opsRowSubtitle(feature, row)) {
                    detail = null
                    detailError = null
                    selected = row
                }
            }
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = { page-- }, enabled = page > 0 && !loading) { Text("上一页") }
                    TextButton(onClick = { page++ },
                        enabled = !loading && rows.isNotEmpty() && (page + 1) * opsPageSize(feature) < total) {
                        Text("下一页")
                    }
                }
            }
        }
    }
    if (selected != null) {
        if (feature == FeatureDestination.ASSETS) {
            AssetOperationsDialog(api, selected!!, onDismiss = { selected = null }) {
                selected = null
                revision++
            }
        } else {
            OpsDetailDialog(
                title = opsRowTitle(feature, selected!!),
                item = detail ?: selected!!,
                loading = detailLoading,
                error = detailError,
                onDismiss = { selected = null },
            )
        }
    }
    if (showTrafficCleanup) {
        TrafficCleanupDialog(api, onDismiss = { showTrafficCleanup = false }) {
            showTrafficCleanup = false
            page = 0
            revision++
        }
    }
    if (showTaskPicker) {
        AlertDialog(
            onDismissRequest = { showTaskPicker = false },
            title = { Text("按任务筛选录制") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                    TextButton(onClick = {
                        llmTask = ""
                        page = 0
                        showTaskPicker = false
                    }) { Text("全部任务") }
                    llmTasks.forEach { task ->
                        TextButton(onClick = {
                            llmTask = task.optString("task_id")
                            page = 0
                            showTaskPicker = false
                        }) {
                            Text("任务 #" + task.optString("task_id") + " · " + task.optInt("count") + " 条")
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showTaskPicker = false }) { Text("关闭") } },
        )
    }
    if (confirmLlmDelete) {
        val count = llmTasks.firstOrNull { it.optString("task_id") == llmTask }?.optInt("count")
        AlertDialog(
            onDismissRequest = { if (!llmDeleting) confirmLlmDelete = false },
            title = { Text("删除任务录制？") },
            text = { Text("将永久删除任务 #" + llmTask + " 的全部 LLM 录制" +
                (if (count == null) "。" else "（" + count + " 条）。") +
                "不会删除任务或其他任务的录制。") },
            confirmButton = {
                TextButton(onClick = {
                    llmDeleting = true
                    actionError = null
                    scope.launch {
                        try {
                            api.request("DELETE", "/api/llm/records?task=" + opsEncode(llmTask),
                                readTimeoutMs = 60_000)
                            confirmLlmDelete = false
                            llmTask = ""
                            page = 0
                            selected = null
                            revision++
                        } catch (failure: Exception) {
                            actionError = failure.message ?: "删除录制失败"
                            confirmLlmDelete = false
                        } finally {
                            llmDeleting = false
                        }
                    }
                }, enabled = !llmDeleting) { Text("确认删除", color = OpsDanger) }
            },
            dismissButton = { TextButton(onClick = { confirmLlmDelete = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun OpsDataRow(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(11.dp), color = Color.White.copy(alpha = 0.95f),
        border = BorderStroke(1.dp, OpsLine), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(15.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = OpsInk, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) {
                    Spacer(Modifier.height(5.dp))
                    Text(subtitle, color = OpsMuted, fontSize = 11.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Outlined.ArrowForward, null, modifier = Modifier.size(17.dp), tint = OpsMuted)
        }
    }
}

private fun opsRowTitle(feature: FeatureDestination, row: JSONObject): String {
    val fields = when (feature) {
        FeatureDestination.TRAFFIC -> listOf("url", "host", "id")
        FeatureDestination.COMMANDS -> listOf("tool", "command", "id")
        FeatureDestination.LLM_RECORDS -> listOf("model", "profile_name", "id")
        FeatureDestination.ASSETS -> listOf("domain", "ip", "url", "app_name", "service_name", "id")
        else -> listOf("name", "id")
    }
    return fields.firstNotNullOfOrNull { row.optString(it).takeIf(String::isNotBlank) }
        ?.take(180) ?: "记录"
}

private fun opsRowSubtitle(feature: FeatureDestination, row: JSONObject): String = when (feature) {
    FeatureDestination.TRAFFIC -> listOf(row.optString("method"), row.optString("status"), row.optString("ts"))
    FeatureDestination.COMMANDS -> listOf(row.optString("worker"), row.optString("created_at"),
        if (row.optBoolean("is_error")) "执行失败" else "")
    FeatureDestination.LLM_RECORDS -> listOf(row.optString("profile_name"), row.optString("task_id"),
        row.optString("status"), row.optString("ts"))
    FeatureDestination.ASSETS -> listOf(row.optString("type"), row.optString("last_seen"))
    else -> emptyList()
}.filter { it.isNotBlank() && it != "null" }.joinToString(" · ")

@Composable
private fun OpsDetailDialog(title: String, item: JSONObject, loading: Boolean = false,
    error: String? = null, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (error != null) Text(error, color = OpsDanger, fontSize = 12.sp)
                item.keys().asSequence().toList().sorted().forEach { key ->
                    if (!opsSensitive(key)) {
                        Text(key.replace('_', ' '), color = OpsMuted, fontSize = 11.sp,
                            modifier = Modifier.padding(top = 10.dp))
                        Text(opsValue(item.opt(key)), color = OpsInk, fontSize = 13.sp)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private fun opsValue(value: Any?): String {
    val text = when (value) {
        null, JSONObject.NULL -> "—"
        is JSONObject -> value.toString(2)
        is JSONArray -> value.toString(2)
        else -> value.toString()
    }
    return if (text.length > 16_000) text.take(16_000) + "…（内容过长）" else text
}

private fun opsSensitive(key: String): Boolean {
    val lower = key.lowercase()
    if (lower.endsWith("_tokens")) return false
    return listOf("password", "secret", "api_key", "apikey", "authorization",
        "cookie", "credential", "private_key").any(lower::contains) ||
        lower == "token" || lower.endsWith("_token")
}

private fun opsEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

@Composable
private fun TrafficCleanupDialog(api: ArtexApi, onDismiss: () -> Unit, onDone: () -> Unit) {
    val scope = rememberCoroutineScope()
    var hosts by remember(api) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember(api) { mutableStateOf(true) }
    var target by remember(api) { mutableStateOf<String?>(null) }
    var error by remember(api) { mutableStateOf<String?>(null) }
    var working by remember(api) { mutableStateOf(false) }
    LaunchedEffect(api) {
        loading = true
        try {
            val array = api.request("GET", "/api/traffic/hosts").optJSONArray("hosts")
            hosts = if (array == null) emptyList() else (0 until array.length())
                .mapNotNull(array::optJSONObject)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            error = failure.message ?: "主机列表读取失败"
        } finally {
            loading = false
        }
    }
    if (target == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("清理录制流量") },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (error != null) Text(error ?: "", color = OpsDanger, fontSize = 12.sp)
                    Text("选择主机后只删除该主机的录制；全部清理会删除所有流量记录。",
                        color = OpsMuted, fontSize = 12.sp)
                    hosts.forEach { host ->
                        val name = host.optString("host")
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(name + " · " + host.optInt("count") + " 条",
                                color = OpsInk, fontSize = 12.sp,
                                modifier = Modifier.weight(1f), maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { target = name }) { Text("删除") }
                        }
                        HorizontalDivider(color = OpsLine)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { target = "*" }, enabled = !loading) {
                    Text("清理全部", color = OpsDanger)
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
        )
    } else {
        val chosen = target ?: ""
        val count = hosts.firstOrNull { it.optString("host") == chosen }?.optInt("count")
        AlertDialog(
            onDismissRequest = { if (!working) target = null },
            title = { Text(if (chosen == "*") "确认删除全部流量？" else "确认删除主机流量？") },
            text = {
                Column {
                    Text(if (chosen == "*") "将永久删除所有录制流量并压实索引；已绑定到发现的证据会保留。"
                        else "将永久删除 " + chosen + " 的录制流量" +
                            (if (count == null) "。" else "（" + count + " 条）。"))
                    if (error != null) Text(error ?: "", color = OpsDanger, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    working = true
                    error = null
                    scope.launch {
                        try {
                            val path = if (chosen == "*") "/api/traffic/all"
                                else "/api/traffic/hosts"
                            val body = if (chosen == "*") null
                                else JSONObject().put("hosts", JSONArray(listOf(chosen)))
                            api.request("DELETE", path, body, readTimeoutMs = 120_000)
                            onDone()
                        } catch (failure: Exception) {
                            error = failure.message ?: "删除流量失败"
                        } finally {
                            working = false
                        }
                    }
                }, enabled = !working) { Text("确认删除", color = OpsDanger) }
            },
            dismissButton = { TextButton(onClick = { target = null }) { Text("返回") } },
        )
    }
}

@Composable
private fun AssetOperationsDialog(api: ArtexApi, asset: JSONObject,
    onDismiss: () -> Unit, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    val assetId = asset.optLong("id")
    val linked = remember(asset) {
        val array = asset.optJSONArray("task_ids")
        if (array == null) emptySet() else (0 until array.length())
            .map(array::optString).filter(String::isNotBlank).toSet()
    }
    var tasks by remember(api, assetId) { mutableStateOf<List<io.artex.mobile.data.Task>>(emptyList()) }
    var tasksError by remember(api, assetId) { mutableStateOf<String?>(null) }
    var attachTask by remember(api, assetId) { mutableStateOf("") }
    var detachTask by remember(api, assetId) { mutableStateOf(linked.firstOrNull().orEmpty()) }
    var sourceSummary by remember(api, assetId) { mutableStateOf("移动端手动关联") }
    var attachMenu by remember(api, assetId) { mutableStateOf(false) }
    var detachMenu by remember(api, assetId) { mutableStateOf(false) }
    var action by remember(api, assetId) { mutableStateOf<String?>(null) }
    var error by remember(api, assetId) { mutableStateOf<String?>(null) }
    var working by remember(api, assetId) { mutableStateOf(false) }
    var showMore by remember(api, assetId) { mutableStateOf(false) }
    LaunchedEffect(api, assetId) {
        try {
            tasks = api.tasks()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            tasksError = failure.message ?: "任务列表读取失败"
        }
    }
    val available = tasks.filterNot { it.id in linked }
    if (action == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(opsRowTitle(FeatureDestination.ASSETS, asset), maxLines = 2,
                overflow = TextOverflow.Ellipsis) },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
                    Text(asset.optString("type") + " · 资产 #" + assetId, color = OpsMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("已关联任务：" + if (linked.isEmpty()) "无" else linked.joinToString("、") { "#" + it },
                        color = OpsInk, fontSize = 12.sp)
                    if (tasksError != null) Text(tasksError ?: "", color = OpsDanger, fontSize = 12.sp)
                    if (error != null) Text(error ?: "", color = OpsDanger, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("关联到任务", color = OpsInk, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Box {
                        OutlinedButton(onClick = { attachMenu = true }, enabled = available.isNotEmpty()) {
                            Text(if (attachTask.isBlank()) "选择任务" else "任务 #" + attachTask)
                        }
                        DropdownMenu(expanded = attachMenu, onDismissRequest = { attachMenu = false }) {
                            available.forEach { task ->
                                DropdownMenuItem(
                                    text = { Text("#" + task.id + "  " + task.title,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    onClick = { attachTask = task.id; attachMenu = false },
                                )
                            }
                        }
                    }
                    OutlinedTextField(sourceSummary, onValueChange = { sourceSummary = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("关联来源说明") })
                    TextButton(onClick = { action = "attach" },
                        enabled = attachTask.isNotBlank() && assetId > 0) { Text("关联") }
                    if (linked.isNotEmpty()) {
                        HorizontalDivider(color = OpsLine)
                        Text("解除现有关联", color = OpsInk, fontSize = 14.sp,
                            fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp))
                        Box {
                            OutlinedButton(onClick = { detachMenu = true }) {
                                Text("任务 #" + detachTask)
                            }
                            DropdownMenu(expanded = detachMenu, onDismissRequest = { detachMenu = false }) {
                                linked.forEach { id ->
                                    DropdownMenuItem(text = { Text("任务 #" + id) },
                                        onClick = { detachTask = id; detachMenu = false })
                                }
                            }
                        }
                        TextButton(onClick = { action = "detach" }, enabled = detachTask.isNotBlank()) {
                            Text("解除关联", color = OpsDanger)
                        }
                    }
                    TextButton(onClick = { showMore = !showMore }) {
                        Text(if (showMore) "收起详细字段" else "查看详细字段")
                    }
                    if (showMore) {
                        asset.keys().asSequence().toList().sorted().forEach { key ->
                            if (!opsSensitive(key)) {
                                Text(key.replace('_', ' '), color = OpsMuted, fontSize = 11.sp)
                                Text(opsValue(asset.opt(key)), color = OpsInk, fontSize = 12.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
        )
    } else {
        val attaching = action == "attach"
        val taskId = if (attaching) attachTask else detachTask
        AlertDialog(
            onDismissRequest = { if (!working) action = null },
            title = { Text(if (attaching) "确认关联资产？" else "确认解除关联？") },
            text = {
                Column {
                    Text("资产 #" + assetId + (if (attaching) " 将关联到任务 #" else " 将从任务 #") +
                        taskId + (if (attaching) "。" else " 解除关联；资产本身仍会保留。"))
                    if (error != null) Text(error ?: "", color = OpsDanger, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    working = true
                    error = null
                    scope.launch {
                        try {
                            if (attaching) {
                                api.request("POST", "/api/tasks/" + opsEncode(taskId) + "/assets",
                                    JSONObject().put("asset_ids", JSONArray(listOf(assetId)))
                                        .put("source_summary", sourceSummary.trim()))
                            } else {
                                api.request("DELETE",
                                    "/api/tasks/" + opsEncode(taskId) + "/assets/" + assetId)
                            }
                            onChanged()
                        } catch (failure: Exception) {
                            error = failure.message ?: "资产关联操作失败"
                        } finally {
                            working = false
                        }
                    }
                }, enabled = !working) {
                    Text(if (attaching) "确认关联" else "确认解除",
                        color = if (attaching) OpsAccent else OpsDanger)
                }
            },
            dismissButton = { TextButton(onClick = { action = null }) { Text("返回") } },
        )
    }
}

@Composable
private fun WorkspaceOperations(api: ArtexApi, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var directory by remember(api) { mutableStateOf("") }
    var revision by remember(api) { mutableIntStateOf(0) }
    var listing by remember(api) { mutableStateOf<JSONObject?>(null) }
    var loading by remember(api) { mutableStateOf(true) }
    var working by remember(api) { mutableStateOf(false) }
    var error by remember(api) { mutableStateOf<String?>(null) }
    var openFile by remember(api) { mutableStateOf<String?>(null) }
    var fileData by remember(api) { mutableStateOf<JSONObject?>(null) }
    var editText by remember(api) { mutableStateOf("") }
    var fileLoading by remember(api) { mutableStateOf(false) }
    var createKind by remember(api) { mutableStateOf<String?>(null) }
    var createName by remember(api) { mutableStateOf("") }
    var deletePath by remember(api) { mutableStateOf<String?>(null) }
    var pendingDownload by remember(api) { mutableStateOf<String?>(null) }
    var transferNotice by remember(api) { mutableStateOf<String?>(null) }
    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty() && !working) {
            working = true
            error = null
            transferNotice = null
            val targetDirectory = directory
            scope.launch {
                var uploaded = 0
                try {
                    if (uris.size > MAX_WORKSPACE_UPLOAD_FILES)
                        throw IOException("每次最多上传 " + MAX_WORKSPACE_UPLOAD_FILES + " 个文件")
                    withContext(Dispatchers.IO) {
                        uris.forEach { uploaded += workspaceUploadOne(api, context, targetDirectory, it) }
                    }
                    transferNotice = "已上传 " + uploaded + " 个文件"
                    revision++
                } catch (failure: Exception) {
                    error = failure.message ?: "上传失败"
                    if (uploaded > 0) {
                        transferNotice = "已上传 " + uploaded + " 个文件，其余文件未完成"
                        revision++
                    }
                } finally {
                    working = false
                }
            }
        }
    }
    val downloadPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val path = pendingDownload
        pendingDownload = null
        if (uri != null && path != null && !working) {
            working = true
            error = null
            transferNotice = null
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { workspaceDownloadTo(api, context, path, uri) }
                    transferNotice = "已保存 " + path.substringAfterLast('/')
                } catch (failure: Exception) {
                    error = (failure.message ?: "下载失败") + "；请检查保存位置是否留下不完整文件"
                } finally {
                    working = false
                }
            }
        }
    }

    LaunchedEffect(api, directory, revision) {
        loading = true
        error = null
        try {
            listing = api.request("GET", "/api/workspace/list?path=" + opsEncode(directory))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            error = failure.message ?: "目录读取失败"
            listing = null
        } finally {
            loading = false
        }
    }
    LaunchedEffect(api, openFile) {
        val path = openFile ?: return@LaunchedEffect
        fileLoading = true
        fileData = null
        editText = ""
        try {
            val value = api.request("GET", "/api/workspace/read?path=" + opsEncode(path))
            fileData = value
            editText = value.optString("content")
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            error = failure.message ?: "文件读取失败"
            openFile = null
        } finally {
            fileLoading = false
        }
    }
    val goBack = {
        when {
            openFile != null -> openFile = null
            directory.isNotEmpty() -> {
                directory = opsParent(directory)
                listing = null
            }
            else -> onBack()
        }
    }
    BackHandler(onBack = goBack)
    val entries = remember(listing) {
        val array = listing?.optJSONArray("entries")
        if (array == null) emptyList() else (0 until array.length())
            .mapNotNull(array::optJSONObject)
            .sortedWith(compareByDescending<JSONObject> { it.optBoolean("dir") }
                .thenBy { it.optString("name").lowercase() })
    }
    Column(Modifier.fillMaxSize()) {
        OpsTopBar("工作空间", if (directory.isBlank()) "/" else "/" + directory, goBack) { revision++ }
        if (loading || working) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { createName = ""; createKind = "file" }, enabled = !working) {
                        Text("新建文件")
                    }
                    OutlinedButton(onClick = { createName = ""; createKind = "dir" }, enabled = !working) {
                        Text("新建目录")
                    }
                    OutlinedButton(onClick = { uploadPicker.launch(arrayOf("*/*")) }, enabled = !working) {
                        Text("上传")
                    }
                }
                Text("同名文件会覆盖原文件；每次最多 8 个，每个最多 128 MiB。",
                    color = OpsMuted, fontSize = 11.sp)
            }
            if (transferNotice != null) item { OpsMessage(transferNotice ?: "") }
            if (error != null) item { OpsMessage(error ?: "", true) }
            if (!loading && error == null && entries.isEmpty()) item { OpsMessage("目录为空") }
            items(entries, key = { it.optString("path") }) { entry ->
                val path = entry.optString("path")
                val isDir = entry.optBoolean("dir")
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        OpsDataRow((if (isDir) "▣  " else "▤  ") + entry.optString("name"),
                            if (isDir) "目录" else entry.optLong("size").toString() + " B") {
                            if (isDir) {
                                directory = path
                                listing = null
                            } else openFile = path
                        }
                    }
                    if (!isDir) {
                        TextButton(onClick = {
                            pendingDownload = path
                            downloadPicker.launch(entry.optString("name").ifBlank { "download" })
                        }, enabled = !working) { Text("下载") }
                    }
                    TextButton(onClick = { deletePath = path }, enabled = !working) {
                        Text("删除", color = OpsDanger)
                    }
                }
            }
        }
    }

    if (createKind != null) {
        val kind = createKind
        AlertDialog(
            onDismissRequest = { createKind = null },
            title = { Text(if (kind == "dir") "新建目录" else "新建文件") },
            text = {
                OutlinedTextField(createName, onValueChange = { createName = it },
                    singleLine = true, label = { Text("名称") })
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = createName.trim()
                    if (name.isEmpty() || name == "." || name == ".." || '/' in name || '\\' in name) {
                        error = "名称不能留空或包含路径分隔符"
                    } else {
                        val path = opsJoin(directory, name)
                        working = true
                        scope.launch {
                            try {
                                if (kind == "dir") api.request("POST", "/api/workspace/mkdir",
                                    JSONObject().put("path", path))
                                else api.request("POST", "/api/workspace/write",
                                    JSONObject().put("path", path).put("content", ""))
                                createKind = null
                                revision++
                                if (kind == "file") openFile = path
                            } catch (failure: Exception) {
                                error = failure.message ?: "创建失败"
                            } finally {
                                working = false
                            }
                        }
                    }
                }, enabled = !working) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { createKind = null }) { Text("取消") } },
        )
    }
    if (deletePath != null) {
        val target = deletePath ?: ""
        AlertDialog(
            onDismissRequest = { deletePath = null },
            title = { Text("删除工作空间内容？") },
            text = { Text("将删除 /" + target + "。目录中的内容也会一并删除。") },
            confirmButton = {
                TextButton(onClick = {
                    working = true
                    scope.launch {
                        try {
                            api.request("DELETE", "/api/workspace/delete?path=" + opsEncode(target))
                            deletePath = null
                            revision++
                        } catch (failure: Exception) {
                            error = failure.message ?: "删除失败"
                        } finally {
                            working = false
                        }
                    }
                }, enabled = !working) { Text("删除", color = OpsDanger) }
            },
            dismissButton = { TextButton(onClick = { deletePath = null }) { Text("取消") } },
        )
    }
    if (openFile != null) {
        val path = openFile ?: ""
        val binary = fileData?.optBoolean("binary") == true
        val tooLarge = fileData?.optBoolean("too_large") == true
        AlertDialog(
            onDismissRequest = { if (!working) openFile = null },
            title = { Text(path.substringAfterLast('/'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 430.dp).verticalScroll(rememberScrollState())) {
                    if (fileLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    when {
                        binary -> Text("二进制文件无法在文本编辑器中打开。", color = OpsMuted)
                        tooLarge -> Text("文件过大，服务器未返回可编辑内容。", color = OpsMuted)
                        fileData != null -> OutlinedTextField(editText, onValueChange = { editText = it },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 240.dp),
                            label = { Text("内容") })
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    working = true
                    scope.launch {
                        try {
                            api.request("POST", "/api/workspace/write",
                                JSONObject().put("path", path).put("content", editText))
                            openFile = null
                            revision++
                        } catch (failure: Exception) {
                            error = failure.message ?: "保存失败"
                        } finally {
                            working = false
                        }
                    }
                }, enabled = !working && !fileLoading && fileData != null && !binary && !tooLarge) {
                    Text("保存")
                }
            },
            dismissButton = { TextButton(onClick = { openFile = null }) { Text("关闭") } },
        )
    }
}

private fun opsParent(path: String): String = path.trimEnd('/').substringBeforeLast('/', "")
private fun opsJoin(parent: String, name: String): String =
    if (parent.isBlank()) name else parent.trimEnd('/') + "/" + name

private const val MAX_WORKSPACE_UPLOAD_FILES = 8
private const val MAX_WORKSPACE_UPLOAD_BYTES = 128L * 1024 * 1024
private const val MAX_WORKSPACE_DOWNLOAD_BYTES = 256L * 1024 * 1024
private val workspaceTransferHttp = OkHttpClient.Builder()
    .connectTimeout(8, TimeUnit.SECONDS)
    .readTimeout(120, TimeUnit.SECONDS)
    .writeTimeout(300, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .build()

private data class PickedWorkspaceFile(val name: String, val size: Long)

private fun pickedWorkspaceFile(context: Context, uri: Uri): PickedWorkspaceFile {
    var name: String? = null
    var size = -1L
    context.contentResolver.query(uri,
        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
        null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
        }
    }
    if (size < 0) {
        context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { descriptor ->
            size = descriptor.length
        }
    }
    if (size > MAX_WORKSPACE_UPLOAD_BYTES)
        throw IOException("单文件最多上传 128 MiB")
    val safeName = (name ?: uri.lastPathSegment ?: "upload")
        .substringAfterLast('/').substringAfterLast('\\')
        .replace(Regex("[\\r\\n\\\"]"), "_").take(180)
    if (safeName.isBlank() || safeName == "." || safeName == "..")
        throw IOException("文件名无效")
    return PickedWorkspaceFile(safeName, size)
}

private class WorkspaceUriBody(
    private val context: Context,
    private val uri: Uri,
    private val knownSize: Long,
) : RequestBody() {
    override fun contentType(): MediaType? =
        context.contentResolver.getType(uri)?.toMediaTypeOrNull()
            ?: "application/octet-stream".toMediaTypeOrNull()

    override fun contentLength(): Long = knownSize
    override fun isOneShot(): Boolean = true

    override fun writeTo(sink: BufferedSink) {
        val source = context.contentResolver.openInputStream(uri)
            ?: throw IOException("无法读取选中的文件")
        source.use { input ->
            val buffer = ByteArray(32 * 1024)
            var copied = 0L
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                copied += count
                if (copied > MAX_WORKSPACE_UPLOAD_BYTES)
                    throw IOException("单文件超过 128 MiB")
                sink.write(buffer, 0, count)
            }
            if (knownSize >= 0 && copied != knownSize)
                throw IOException("文件大小在上传过程中发生变化")
        }
    }
}

/** One multipart request per file keeps memory and server request size bounded. */
private fun workspaceUploadOne(api: ArtexApi, context: Context, directory: String, uri: Uri): Int {
    val file = pickedWorkspaceFile(context, uri)
    val token = api.token?.takeIf(String::isNotBlank) ?: throw IOException("请先登录")
    val body = MultipartBody.Builder().setType(MultipartBody.FORM)
        .addFormDataPart("file", file.name, WorkspaceUriBody(context, uri, file.size))
        .build()
    val request = Request.Builder()
        .url(api.baseUrl + "/api/workspace/upload?path=" + opsEncode(directory))
        .header("Authorization", "Bearer " + token)
        .header("Accept", "application/json")
        .header("Cache-Control", "no-store")
        .post(body)
        .build()
    workspaceTransferHttp.newCall(request).execute().use { response ->
        if (!response.isSuccessful)
            throw IOException("上传失败：服务器返回 " + response.code)
        val result = JSONObject(response.peekBody(8192).string())
        if (result.optInt("uploaded") != 1) throw IOException("服务器未确认文件上传")
        return 1
    }
}

private fun workspaceDownloadTo(api: ArtexApi, context: Context, path: String, uri: Uri) {
    val token = api.token?.takeIf(String::isNotBlank) ?: throw IOException("请先登录")
    val request = Request.Builder()
        .url(api.baseUrl + "/api/workspace/download?path=" + opsEncode(path))
        .header("Authorization", "Bearer " + token)
        .header("Accept", "application/octet-stream")
        .header("Cache-Control", "no-store")
        .get()
        .build()
    workspaceTransferHttp.newCall(request).execute().use { response ->
        if (!response.isSuccessful)
            throw IOException("下载失败：服务器返回 " + response.code)
        val body = response.body ?: throw IOException("服务器未返回文件")
        if (body.contentLength() > MAX_WORKSPACE_DOWNLOAD_BYTES)
            throw IOException("单文件下载上限为 256 MiB")
        val output = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IOException("无法写入选择的位置")
        output.use { target ->
            body.byteStream().use { source ->
                val buffer = ByteArray(32 * 1024)
                var copied = 0L
                while (true) {
                    val count = source.read(buffer)
                    if (count < 0) break
                    copied += count
                    if (copied > MAX_WORKSPACE_DOWNLOAD_BYTES)
                        throw IOException("单文件下载上限为 256 MiB")
                    target.write(buffer, 0, count)
                }
                target.flush()
            }
        }
    }
}

@Composable
private fun SyncOperations(api: ArtexApi, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var statusRevision by remember(api) { mutableIntStateOf(0) }
    var listRevision by remember(api) { mutableIntStateOf(0) }
    var status by remember(api) { mutableStateOf<JSONObject?>(null) }
    var statusLoading by remember(api) { mutableStateOf(true) }
    var statusError by remember(api) { mutableStateOf<String?>(null) }
    var sourceUrl by remember(api) { mutableStateOf("") }
    var sourceKey by remember(api) { mutableStateOf("") }
    var dimension by remember(api) { mutableStateOf("project") }
    var page by remember(api) { mutableIntStateOf(1) }
    var searchInput by remember(api) { mutableStateOf("") }
    var search by remember(api) { mutableStateOf("") }
    var rows by remember(api) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var listLoading by remember(api) { mutableStateOf(false) }
    var listError by remember(api) { mutableStateOf<String?>(null) }
    var selected by remember(api) { mutableStateOf<Set<String>>(emptySet()) }
    var assetTypes by remember(api) { mutableStateOf(setOf("subdomain", "service", "app")) }
    var createCompany by remember(api) { mutableStateOf(true) }
    var working by remember(api) { mutableStateOf(false) }
    var actionError by remember(api) { mutableStateOf<String?>(null) }
    var result by remember(api) { mutableStateOf<JSONObject?>(null) }
    var confirmSync by remember(api) { mutableStateOf(false) }

    LaunchedEffect(api, statusRevision) {
        statusLoading = true
        statusError = null
        try {
            val next = api.request("GET", "/api/sync/scopesentry/status")
            status = next
            sourceUrl = next.optString("url")
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            statusError = failure.message ?: "状态读取失败"
        } finally {
            statusLoading = false
        }
    }
    val ready = status?.let {
        it.optBoolean("exists") && it.optBoolean("configured") && it.optBoolean("enabled")
    } == true
    LaunchedEffect(api, ready, dimension, page, search, listRevision) {
        if (!ready) return@LaunchedEffect
        listLoading = true
        listError = null
        selected = emptySet()
        rows = emptyList()
        try {
            val path = if (dimension == "project")
                "/api/sync/scopesentry/projects?page=" + page + "&size=50&search=" + opsEncode(search)
            else "/api/sync/scopesentry/tasks?page=" + page + "&size=50&search=" + opsEncode(search)
            val response = api.request("GET", path)
            val array = response.optJSONArray(if (dimension == "project") "projects" else "tasks")
            rows = if (array == null) emptyList() else (0 until array.length()).mapNotNull(array::optJSONObject)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            listError = failure.message ?: "列表读取失败"
            rows = emptyList()
        } finally {
            listLoading = false
        }
    }
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        OpsTopBar("资产同步", "ScopeSentry 数据源", onBack) {
            statusRevision++
            listRevision++
        }
        if (statusLoading || listLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 9.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)) {
            if (statusError != null) item { OpsMessage("状态读取失败：" + statusError, true) }
            if (actionError != null) item { OpsMessage(actionError ?: "", true) }
            item {
                Surface(shape = RoundedCornerShape(12.dp), color = Color.White.copy(alpha = 0.95f),
                    border = BorderStroke(1.dp, OpsLine)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("数据源状态", color = OpsInk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        val stateLabel = when {
                            statusLoading -> "检测中…"
                            status?.optBoolean("exists") != true -> "未创建"
                            status?.optBoolean("configured") != true -> "未配置"
                            status?.optBoolean("enabled") != true -> "未启用"
                            status?.optBoolean("reachable") == true -> "已连接"
                            else -> "已配置，但当前不可达"
                        }
                        Text(stateLabel, color = if (ready && status?.optBoolean("reachable") == true)
                            OpsAccent else OpsMuted, fontSize = 13.sp)
                        if (status?.optBoolean("exists") == true) {
                            Spacer(Modifier.height(13.dp))
                            OutlinedTextField(sourceUrl, onValueChange = { sourceUrl = it },
                                label = { Text("MCP 地址") }, singleLine = true,
                                modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(sourceKey, onValueChange = { sourceKey = it },
                                label = { Text("API Key（留空保留）") }, singleLine = true,
                                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth())
                        }
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = {
                            working = true
                            actionError = null
                            scope.launch {
                                try {
                                    if (status?.optBoolean("exists") == true) {
                                        if (sourceUrl.isBlank()) error("请填写 MCP 地址")
                                        api.request("POST", "/api/sync/scopesentry/datasource",
                                            JSONObject().put("url", sourceUrl.trim())
                                                .put("api_key", sourceKey.trim()))
                                        sourceKey = ""
                                    } else {
                                        api.request("POST", "/api/sync/scopesentry/datasource", JSONObject())
                                    }
                                    statusRevision++
                                } catch (failure: Exception) {
                                    actionError = failure.message ?: "数据源保存失败"
                                } finally {
                                    working = false
                                }
                            }
                        }, enabled = !working && !statusLoading) {
                            Text(if (status?.optBoolean("exists") == true) "保存并启用" else "创建数据源")
                        }
                    }
                }
            }
            if (ready) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = dimension == "project",
                            onClick = { dimension = "project"; page = 1 },
                            label = { Text("项目") })
                        FilterChip(selected = dimension == "task",
                            onClick = { dimension = "task"; page = 1 },
                            label = { Text("任务") })
                    }
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(searchInput, onValueChange = { searchInput = it },
                            singleLine = true,
                            placeholder = { Text(if (dimension == "project") "搜索项目" else "搜索任务") },
                            modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { page = 1; search = searchInput.trim(); listRevision++ }) {
                            Text("搜索")
                        }
                    }
                }
                item {
                    Text("同步资产", color = OpsInk, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("subdomain" to "子域名", "service" to "服务", "app" to "App")
                            .forEach { (value, label) ->
                                FilterChip(selected = value in assetTypes,
                                    onClick = {
                                        assetTypes = if (value in assetTypes) assetTypes - value else assetTypes + value
                                    }, label = { Text(label, fontSize = 11.sp) })
                            }
                    }
                    if (dimension == "project") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(createCompany, onCheckedChange = { createCompany = it })
                            Text("按项目建立企业并写入资产范围", color = OpsInk, fontSize = 12.sp)
                        }
                    }
                }
                if (listError != null) item { OpsMessage("列表读取失败：" + listError, true) }
                if (!listLoading && rows.isEmpty() && listError == null) item { OpsMessage("暂无匹配项目或任务") }
                item {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("第 " + page + " 页 · 已选 " + selected.size, color = OpsMuted,
                            fontSize = 12.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            selected = rows.map { syncRowId(dimension, it) }.filter(String::isNotBlank).toSet()
                        }, enabled = rows.isNotEmpty()) { Text("全选本页") }
                    }
                }
                items(rows, key = { it.optString("id") }) { row ->
                    val id = syncRowId(dimension, row)
                    val checked = id in selected
                    Row(Modifier.fillMaxWidth().clickable {
                        selected = if (checked) selected - id else selected + id
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = checked,
                            onCheckedChange = { selected = if (checked) selected - id else selected + id })
                        Column(Modifier.weight(1f)) {
                            Text(row.optString("name").ifBlank { id }, color = OpsInk, fontSize = 14.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(if (dimension == "project")
                                "资产 " + row.optInt("AssetCount") + " · " + row.optString("tag")
                                else "进度 " + row.optString("progress") + "%",
                                color = OpsMuted, fontSize = 11.sp)
                        }
                    }
                    HorizontalDivider(color = OpsLine)
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { page-- }, enabled = page > 1 && !listLoading) { Text("上一页") }
                        TextButton(onClick = { page++ }, enabled = rows.size == 50 && !listLoading) {
                            Text("下一页")
                        }
                    }
                }
                item {
                    Button(onClick = { confirmSync = true },
                        enabled = selected.isNotEmpty() && assetTypes.isNotEmpty() && !working,
                        modifier = Modifier.fillMaxWidth()) {
                        Text(if (working) "同步中…" else "同步选中项")
                    }
                }
                if (result != null) item {
                    val synced = result?.optJSONObject("synced")
                    val summary = if (synced == null) "" else synced.keys().asSequence()
                        .toList().joinToString(" · ") { key -> key + " " + synced.optInt(key) }
                    OpsMessage("同步完成" + if (summary.isBlank()) "" else " · " + summary)
                }
            } else if (!statusLoading) item {
                OpsMessage("数据源就绪后可选择项目或任务进行同步。")
            }
        }
    }
    if (confirmSync) {
        AlertDialog(
            onDismissRequest = { confirmSync = false },
            title = { Text("确认同步资产") },
            text = { Text("将从 " + selected.size + " 个" +
                (if (dimension == "project") "项目" else "任务") + "同步 " +
                assetTypes.joinToString("、") + " 到本地资产库。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmSync = false
                    working = true
                    actionError = null
                    result = null
                    val payload = JSONObject()
                        .put("dimension", dimension)
                        .put("targets", JSONArray(selected.toList()))
                        .put("asset_types", JSONArray(assetTypes.toList()))
                        .put("create_company", dimension == "project" && createCompany)
                    scope.launch {
                        try {
                            result = api.request("POST", "/api/sync/scopesentry/sync", payload,
                                readTimeoutMs = 120_000)
                            listRevision++
                        } catch (failure: Exception) {
                            actionError = failure.message ?: "同步失败"
                        } finally {
                            working = false
                        }
                    }
                }) { Text("开始同步") }
            },
            dismissButton = { TextButton(onClick = { confirmSync = false }) { Text("取消") } },
        )
    }
}

private fun syncRowId(dimension: String, row: JSONObject): String =
    if (dimension == "project") row.optString("id") else row.optString("name")

@Composable
private fun CompanyOperations(api: ArtexApi, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var revision by remember(api) { mutableIntStateOf(0) }
    var companies by remember(api) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember(api) { mutableStateOf(true) }
    var working by remember(api) { mutableStateOf(false) }
    var error by remember(api) { mutableStateOf<String?>(null) }
    var newName by remember(api) { mutableStateOf("") }
    var creating by remember(api) { mutableStateOf(false) }
    var selected by remember(api) { mutableStateOf<JSONObject?>(null) }
    var detail by remember(api) { mutableStateOf<JSONObject?>(null) }
    var detailLoading by remember(api) { mutableStateOf(false) }
    var deleteTarget by remember(api) { mutableStateOf<JSONObject?>(null) }
    var scopeTarget by remember(api) { mutableStateOf<JSONObject?>(null) }
    var scopeReplace by remember(api) { mutableStateOf(false) }
    var scopeText by remember(api) { mutableStateOf("") }
    var scopeReason by remember(api) { mutableStateOf("") }
    var scopeError by remember(api) { mutableStateOf<String?>(null) }
    var confirmReplace by remember(api) { mutableStateOf(false) }

    LaunchedEffect(api, revision) {
        loading = true
        error = null
        try {
            val array = api.requestArray("/api/companies")
            companies = (0 until array.length()).mapNotNull(array::optJSONObject)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            error = failure.message ?: "企业读取失败"
        } finally {
            loading = false
        }
    }
    LaunchedEffect(api, selected) {
        val id = selected?.optString("id")?.takeIf(String::isNotBlank) ?: return@LaunchedEffect
        detail = selected
        detailLoading = true
        try {
            detail = api.request("GET", "/api/companies/" + opsEncode(id))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            error = failure.message ?: "企业详情读取失败"
        } finally {
            detailLoading = false
        }
    }
    BackHandler(enabled = selected != null || creating || deleteTarget != null || scopeTarget != null) {
        selected = null
        creating = false
        deleteTarget = null
        scopeTarget = null
    }
    BackHandler(enabled = selected == null && !creating && deleteTarget == null && scopeTarget == null,
        onBack = onBack)
    Column(Modifier.fillMaxSize()) {
        OpsTopBar("企业与范围", "资产归属和授权范围", onBack) { revision++ }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 26.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Button(onClick = { newName = ""; creating = true }) { Text("新建企业") }
            }
            if (error != null) item { OpsMessage(error ?: "", true) }
            if (!loading && companies.isEmpty() && error == null) item { OpsMessage("暂无企业") }
            items(companies, key = { it.optString("id") }) { company ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        OpsDataRow(company.optString("name"),
                            "资产 " + company.optInt("asset_count") + " · 范围 " +
                                (company.optJSONArray("scope")?.length() ?: 0) + " 条") {
                            detail = null
                            selected = company
                        }
                    }
                    TextButton(onClick = {
                        scopeTarget = company
                        scopeReplace = false
                        scopeText = ""
                        scopeReason = ""
                        scopeError = null
                    }, enabled = !working) { Text("范围") }
                    TextButton(onClick = { deleteTarget = company }, enabled = !working) {
                        Text("删除", color = OpsDanger)
                    }
                }
            }
        }
    }
    if (creating) {
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("新建企业") },
            text = {
                Column {
                    OutlinedTextField(newName, onValueChange = { newName = it },
                        label = { Text("企业名称") }, singleLine = true)
                    Spacer(Modifier.height(8.dp))
                    Text("创建后可在网页端维护详细授权范围。", color = OpsMuted, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newName.isBlank()) {
                        error = "请输入企业名称"
                    } else {
                        working = true
                        scope.launch {
                            try {
                                api.request("POST", "/api/companies",
                                    JSONObject().put("name", newName.trim()).put("scope", JSONArray()))
                                creating = false
                                revision++
                            } catch (failure: Exception) {
                                error = failure.message ?: "创建失败"
                            } finally {
                                working = false
                            }
                        }
                    }
                }, enabled = !working) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("取消") } },
        )
    }
    if (deleteTarget != null) {
        val company = deleteTarget ?: JSONObject()
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除企业？") },
            text = {
                Text("将删除“" + company.optString("name") +
                    "”及其授权范围。已归属的资产将保留，不会随企业删除。")
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = company.optString("id")
                    working = true
                    scope.launch {
                        try {
                            api.request("DELETE", "/api/companies/" + opsEncode(id),
                                JSONObject().put("delete_assets", false))
                            deleteTarget = null
                            revision++
                        } catch (failure: Exception) {
                            error = failure.message ?: "删除失败"
                        } finally {
                            working = false
                        }
                    }
                }, enabled = !working) { Text("删除", color = OpsDanger) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }
    if (selected != null) {
        OpsDetailDialog(selected?.optString("name").orEmpty(), detail ?: selected!!,
            loading = detailLoading, onDismiss = { selected = null })
    }
    val submitScope: () -> Unit = {
        val company = scopeTarget
        val lines = scopeText.lines().map(String::trim).filter(String::isNotBlank)
        when {
            company == null -> Unit
            lines.isEmpty() && !scopeReplace -> scopeError = "请填写至少一条范围"
            else -> {
                working = true
                scopeError = null
                val existing = company.optJSONArray("scope")
                val preservedKinds = if (!scopeReplace || existing == null) emptyMap<String, String>()
                    else (0 until existing.length()).mapNotNull(existing::optJSONObject)
                        .mapNotNull { saved ->
                            val raw = saved.optString("raw").ifBlank { saved.optString("value") }
                            val kind = saved.optString("kind")
                            if (raw.isBlank() || kind.isBlank()) null else raw to kind
                        }.toMap()
                val rules = JSONArray(lines.map { line ->
                    JSONObject().put("value", line).apply {
                        preservedKinds[line]?.let { put("kind", it) }
                    }
                })
                val body = JSONObject()
                    .put("scope", rules)
                    .put("reason", scopeReason.trim())
                    .put("reset", scopeReplace)
                scope.launch {
                    try {
                        val result = api.request("POST",
                            "/api/companies/" + opsEncode(company.optString("id")) + "/scope", body)
                        scopeTarget = null
                        confirmReplace = false
                        revision++
                        if (result.optInt("invalid") > 0)
                            error = "已保存，但有 " + result.optInt("invalid") + " 条范围无效"
                    } catch (failure: Exception) {
                        scopeError = failure.message ?: "范围保存失败"
                        confirmReplace = false
                    } finally {
                        working = false
                    }
                }
            }
        }
    }
    if (scopeTarget != null) {
        AlertDialog(
            onDismissRequest = { if (!working) scopeTarget = null },
            title = { Text("管理范围 · " + scopeTarget?.optString("name")) },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = !scopeReplace, onClick = {
                            scopeReplace = false
                            scopeText = ""
                            scopeError = null
                        }, label = { Text("追加") })
                        FilterChip(selected = scopeReplace, onClick = {
                            scopeReplace = true
                            val array = scopeTarget?.optJSONArray("scope")
                            scopeText = if (array == null) "" else (0 until array.length())
                                .mapNotNull(array::optJSONObject)
                                .map { it.optString("raw").ifBlank { it.optString("value") } }
                                .filter(String::isNotBlank).joinToString("\n")
                            scopeError = null
                        }, label = { Text("覆盖全部") })
                    }
                    Text("每行一条：域名、IP、CIDR、ICP 或企业关键词。覆盖会替换已有全部规则。",
                        color = OpsMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(scopeText, onValueChange = { scopeText = it },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                        label = { Text("资产范围") }, minLines = 6, maxLines = 12)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(scopeReason, onValueChange = { scopeReason = it },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        label = { Text("归属依据（可选）") })
                    if (scopeError != null) Text(scopeError ?: "", color = OpsDanger, fontSize = 12.sp)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (scopeReplace) confirmReplace = true else submitScope()
                }, enabled = !working) { Text(if (scopeReplace) "覆盖保存" else "追加") }
            },
            dismissButton = { TextButton(onClick = { scopeTarget = null }) { Text("取消") } },
        )
    }
    if (confirmReplace) {
        AlertDialog(
            onDismissRequest = { confirmReplace = false },
            title = { Text("确认覆盖全部范围？") },
            text = { Text("“" + scopeTarget?.optString("name") +
                "”现有的全部范围将由输入框中的内容替换。空内容会清除全部范围。") },
            confirmButton = {
                TextButton(onClick = submitScope, enabled = !working) { Text("确认覆盖", color = OpsDanger) }
            },
            dismissButton = { TextButton(onClick = { confirmReplace = false }) { Text("返回编辑") } },
        )
    }
}
