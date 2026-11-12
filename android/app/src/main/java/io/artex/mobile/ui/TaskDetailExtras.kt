package io.artex.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.artex.mobile.data.ArtexApi
import io.artex.mobile.data.Finding
import io.artex.mobile.data.Task
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

private val ExtraInk = Color(0xFF1C2E2A)
private val ExtraMuted = Color(0xFF73807A)
private val ExtraLine = Color(0xFFE3E6DE)
private val ExtraAccent = Color(0xFF3F6658)
private val ExtraDanger = Color(0xFFB56D55)

private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
private fun JSONArray?.asObjects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

/** Task detail sections are loaded only when selected. The main Agent chat remains separate. */
@Composable
fun TaskDetailExtras(task: Task, api: ArtexApi) {
    val sections = listOf(
        "overview" to "总览", "goals" to "目标", "constraints" to "约束",
        "scope" to "范围", "findings" to "发现", "assets" to "资产",
        "lineage" to "探索链路", "broadcast" to "播报板", "coverage" to "覆盖图",
        "intercept" to "拦截审批", "report" to "报告",
    )
    var selected by rememberSaveable(task.id) { mutableStateOf("overview") }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            sections.forEach { (key, label) ->
                FilterChip(selected = selected == key, onClick = { selected = key }, label = { Text(label) })
            }
        }
        HorizontalDivider(color = ExtraLine)
        when (selected) {
            "overview" -> ExtraOverview(task, api)
            "goals" -> ExtraTextItems(task, api, "goals")
            "constraints" -> ExtraTextItems(task, api, "constraints")
            "scope" -> ExtraScope(task, api)
            "findings" -> ExtraFindings(task, api)
            "assets" -> ExtraAssets(task, api)
            "lineage" -> ExtraExploration(task, api, lineage = true)
            "broadcast" -> ExtraExploration(task, api, lineage = false)
            "coverage" -> ExtraCoverageGraph(task, api)
            "intercept" -> ExtraIntercept(task, api)
            "report" -> ExtraReport(task, api)
        }
    }
}

@Composable
private fun ExtraOverview(task: Task, api: ArtexApi) {
    var stats by remember(api, task.id) { mutableStateOf<JSONObject?>(null) }
    var coverage by remember(api, task.id) { mutableStateOf<JSONObject?>(null) }
    var error by remember(api, task.id) { mutableStateOf("") }
    LaunchedEffect(api, task.id) {
        launch {
            try { stats = api.request("GET", "/api/stats?task=${enc(task.id)}&scope=task") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "统计读取失败" }
        }
        launch {
            try { coverage = api.request("GET", "/api/tasks/${enc(task.id)}/coverage") }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Older servers may not expose coverage. */ }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp)) {
        item {
            ExtraPanel {
                ExtraTitle("任务说明")
                Text(task.description.ifBlank { "暂无描述" }, color = ExtraInk, fontSize = 13.sp, lineHeight = 20.sp)
                Spacer(Modifier.height(15.dp))
                ExtraTitle("任务目标")
                Text(task.goal.ifBlank { "暂无目标" }, color = ExtraInk, fontSize = 13.sp, lineHeight = 20.sp)
            }
        }
        item {
            ExtraPanel {
                ExtraTitle("运行状态")
                ExtraValueRow("任务状态", statusLabel(task.status))
                ExtraValueRow("引擎状态", stats?.optString("engine_mode").orEmpty().ifBlank { "读取中" })
                ExtraValueRow("已发现", "${task.findings} 项")
                if (error.isNotBlank()) Text(error, color = ExtraDanger, fontSize = 12.sp)
            }
        }
        coverage?.let { value -> item {
            ExtraPanel {
                ExtraTitle("资产覆盖")
                if (!value.optBoolean("enabled", true)) Text("该任务未启用覆盖度", color = ExtraMuted, fontSize = 13.sp)
                else {
                    val pct = if (value.isNull("pct")) "—" else "${value.optDouble("pct").toInt()}%"
                    ExtraValueRow("覆盖率", pct)
                    ExtraValueRow("已测试 / 范围内", "${value.optInt("tested")} / ${value.optInt("denominator")}")
                    ExtraValueRow("范围记录", "${value.optInt("scope_rows")} 条")
                }
            }
        } }
    }
}

private fun statusLabel(status: String): String = when (status) {
    "created" -> "待开始"; "queued" -> "排队中"; "running" -> "运行中"
    "paused" -> "已暂停"; "done" -> "已完成"; "failed" -> "失败"
    "timeout" -> "超时"; else -> status
}

@Composable
private fun ExtraTextItems(task: Task, api: ArtexApi, type: String) {
    val goals = type == "goals"
    val title = if (goals) "目标管理" else "操作约束"
    val base = "/api/tasks/${enc(task.id)}/$type"
    var rows by remember(api, task.id, type) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember(api, task.id, type) { mutableStateOf(true) }
    var error by remember(api, task.id, type) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var input by rememberSaveable(task.id, type) { mutableStateOf("") }
    var secondary by rememberSaveable(task.id, type) { mutableStateOf("") }
    var kind by rememberSaveable(task.id, type) { mutableStateOf("deny") }
    var edit by remember { mutableStateOf<JSONObject?>(null) }
    var editText by remember { mutableStateOf("") }
    var editSecondary by remember { mutableStateOf("") }
    var editKind by remember { mutableStateOf("deny") }
    var pendingDelete by remember { mutableStateOf<JSONObject?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(api, task.id, type, reload) {
        loading = rows.isEmpty()
        try {
            rows = api.request("GET", base).optJSONArray(type).asObjects()
            error = ""
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "读取失败" }
        finally { loading = false }
    }
    fun submit(method: String, path: String, body: JSONObject? = null, success: () -> Unit = {}) {
        if (busy) return
        scope.launch {
            busy = true
            try { api.request(method, path, body); success(); reload++ }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "操作失败" }
            finally { busy = false }
        }
    }
    fun body(text: String, aux: String, ruleKind: String) = JSONObject().put("text", text.trim()).apply {
        if (goals) put("vulnclass", aux.trim()) else put("kind", ruleKind)
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(11.dp)) {
        item { Text(title, color = ExtraInk, fontSize = 19.sp, fontWeight = FontWeight.SemiBold) }
        item {
            ExtraPanel {
                OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(),
                    placeholder = { Text(if (goals) "输入新的测试目标" else "输入操作约束") },
                    minLines = 2, maxLines = 4, shape = RoundedCornerShape(10.dp))
                Spacer(Modifier.height(9.dp))
                if (goals) OutlinedTextField(secondary, { secondary = it }, Modifier.fillMaxWidth(),
                    placeholder = { Text("漏洞类别（可选）") }, singleLine = true,
                    shape = RoundedCornerShape(10.dp))
                else ExtraKindToggle(kind) { kind = it }
                Spacer(Modifier.height(9.dp))
                Button(onClick = {
                    submit("POST", base, body(input, secondary, kind)) { input = ""; secondary = "" }
                }, enabled = input.isNotBlank() && !busy) { Text("添加") }
            }
        }
        if (loading) item { ExtraLoading() }
        if (error.isNotBlank()) item { Text(error, color = ExtraDanger, fontSize = 12.sp) }
        if (!loading && rows.isEmpty()) item { ExtraEmpty(if (goals) "暂无目标" else "暂无约束") }
        items(rows, key = { it.optString("id") }) { row ->
            ExtraPanel {
                val badge = if (goals) row.optString("state").ifBlank { row.optString("vulnclass") }
                    else if (row.optString("kind") == "allow") "允许" else "禁止"
                if (badge.isNotBlank()) Text(badge, color = ExtraAccent, fontSize = 11.sp)
                Spacer(Modifier.height(5.dp))
                Text(row.optString("text"), color = ExtraInk, fontSize = 13.sp, lineHeight = 20.sp)
                Row {
                    TextButton(onClick = {
                        edit = row; editText = row.optString("text")
                        editSecondary = row.optString("vulnclass"); editKind = row.optString("kind", "deny")
                    }) { Text("编辑") }
                    TextButton(onClick = { pendingDelete = row }) { Text("删除", color = ExtraDanger) }
                }
            }
        }
    }
    edit?.let { row ->
        AlertDialog(onDismissRequest = { edit = null }, title = { Text(if (goals) "编辑目标" else "编辑约束") },
            text = {
                Column {
                    OutlinedTextField(editText, { editText = it }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 5)
                    Spacer(Modifier.height(10.dp))
                    if (goals) OutlinedTextField(editSecondary, { editSecondary = it }, Modifier.fillMaxWidth(),
                        label = { Text("漏洞类别") }, singleLine = true)
                    else ExtraKindToggle(editKind) { editKind = it }
                }
            },
            confirmButton = { TextButton(onClick = {
                submit("PATCH", "$base/${enc(row.optString("id"))}", body(editText, editSecondary, editKind)) { edit = null }
            }, enabled = editText.isNotBlank() && !busy) { Text("保存") } },
            dismissButton = { TextButton(onClick = { edit = null }) { Text("取消") } })
    }
    pendingDelete?.let { row ->
        AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("删除${if (goals) "目标" else "约束"}") },
            text = { Text(row.optString("text")) },
            confirmButton = { TextButton(onClick = {
                submit("DELETE", "$base/${enc(row.optString("id"))}") { pendingDelete = null }
            }, enabled = !busy) { Text("确认删除", color = ExtraDanger) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } })
    }
}

@Composable
private fun ExtraKindToggle(kind: String, onChange: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = kind == "deny", onClick = { onChange("deny") }, label = { Text("禁止") })
        FilterChip(selected = kind == "allow", onClick = { onChange("allow") }, label = { Text("允许") })
    }
}

@Composable
private fun ExtraScope(task: Task, api: ArtexApi) {
    val base = "/api/tasks/${enc(task.id)}/scope"
    val kinds = listOf("root_domain" to "根域名", "subdomain" to "子域名", "ip" to "IP",
        "cidr" to "网段", "icp" to "ICP", "keyword" to "关键词", "company" to "公司")
    var rows by remember(api, task.id) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember(api, task.id) { mutableStateOf(true) }
    var error by remember(api, task.id) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var kind by rememberSaveable(task.id) { mutableStateOf("root_domain") }
    var value by rememberSaveable(task.id) { mutableStateOf("") }
    var reason by rememberSaveable(task.id) { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<JSONObject?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(api, task.id, reload) {
        loading = rows.isEmpty()
        try {
            rows = api.request("GET", base).optJSONArray("scope").asObjects()
            error = ""
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "范围读取失败" }
        finally { loading = false }
    }
    fun submit(method: String, path: String, body: JSONObject? = null, success: () -> Unit = {}) {
        if (busy) return
        scope.launch {
            busy = true
            try { api.request(method, path, body); success(); reload++ }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "操作失败" }
            finally { busy = false }
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("测试范围", color = ExtraInk, fontSize = 19.sp, fontWeight = FontWeight.SemiBold) }
        item { Text("范围同时作为测试边界和资产覆盖度的分母。", color = ExtraMuted, fontSize = 12.sp) }
        item {
            ExtraPanel {
                Box {
                    OutlinedButton(onClick = { menu = true }) { Text(kinds.firstOrNull { it.first == kind }?.second ?: kind) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        kinds.forEach { (key, label) -> DropdownMenuItem(text = { Text(label) }, onClick = {
                            kind = key; menu = false
                        }) }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value, { value = it }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text(if (kind == "company") "公司名或 ID" else "输入范围值") },
                    shape = RoundedCornerShape(10.dp))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(reason, { reason = it }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("备注（可选）") }, shape = RoundedCornerShape(10.dp))
                Spacer(Modifier.height(9.dp))
                Button(onClick = {
                    submit("POST", base, JSONObject().put("kind", kind).put("value", value.trim())
                        .put("reason", reason.trim())) { value = ""; reason = "" }
                }, enabled = value.isNotBlank() && !busy) { Text("添加范围") }
            }
        }
        if (loading) item { ExtraLoading() }
        if (error.isNotBlank()) item { Text(error, color = ExtraDanger, fontSize = 12.sp) }
        if (!loading && rows.isEmpty()) item { ExtraEmpty("暂无测试范围") }
        items(rows, key = { it.optLong("id") }) { row ->
            ExtraPanel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(kinds.firstOrNull { it.first == row.optString("kind") }?.second ?: row.optString("kind"),
                        color = ExtraAccent, fontSize = 11.sp)
                    Spacer(Modifier.weight(1f))
                    Text(if (row.optString("task_id") == task.id) row.optString("source") else "继承",
                        color = ExtraMuted, fontSize = 11.sp)
                }
                Spacer(Modifier.height(6.dp))
                Text(scopeValue(row), color = ExtraInk, fontSize = 13.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (row.optString("task_id") == task.id) TextButton(onClick = { pendingDelete = row }) {
                    Text("移除", color = ExtraDanger)
                }
            }
        }
    }
    pendingDelete?.let { row ->
        AlertDialog(onDismissRequest = { pendingDelete = null }, title = { Text("移除测试范围") },
            text = { Text(scopeValue(row)) },
            confirmButton = { TextButton(onClick = {
                submit("DELETE", "$base/${row.optLong("id")}") { pendingDelete = null }
            }, enabled = !busy) { Text("确认移除", color = ExtraDanger) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } })
    }
}

private fun scopeValue(row: JSONObject): String = row.optString("value").ifBlank {
    row.optString("domain").ifBlank {
        row.optString("net").ifBlank {
            row.optString("company_name").ifBlank { "企业 #${row.optLong("company_id")}" }
        }
    }
}

@Composable
private fun ExtraFindings(task: Task, api: ArtexApi) {
    var rows by remember(api, task.id) { mutableStateOf<List<Finding>>(emptyList()) }
    var total by remember(api, task.id) { mutableStateOf(0) }
    var page by rememberSaveable(task.id) { mutableStateOf(1) }
    var reload by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var opened by remember { mutableStateOf<Finding?>(null) }
    BackHandler(enabled = opened != null) { opened = null }
    LaunchedEffect(api, task.id, page, reload) {
        loading = rows.isEmpty()
        try {
            // `task` selects the legacy bare-array node view. `task_id` selects the
            // paginated findings table that this screen and FindingDetailScreen use.
            val result = api.request("GET", "/api/exploration/findings?task_id=${enc(task.id)}&page=$page&limit=20")
            val newRows = result.optJSONArray("items").asObjects().map { item ->
                Finding(
                    id = item.optString("finding_id").ifBlank { item.optString("id") },
                    title = item.optString("name").ifBlank { item.optString("vulnclass") },
                    severity = item.optString("severity"), status = item.optString("status"),
                    summary = item.optString("summary"), taskId = item.optString("task_id"),
                    ts = item.optString("ts"),
                )
            }
            rows = if (page == 1) newRows else rows + newRows
            total = result.optInt("total")
            error = ""
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "发现读取失败" }
        finally { loading = false }
    }
    opened?.let { finding ->
        FindingDetailScreen(finding, api, onBack = { opened = null }, onChanged = {
            rows = emptyList(); page = 1; reload++
        })
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("任务发现", Modifier.weight(1f), color = ExtraInk, fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold)
                Text("$total 项", color = ExtraMuted, fontSize = 12.sp)
            }
        }
        item { Text("点击发现可查看证据、更新状态并发起复测。", color = ExtraMuted, fontSize = 12.sp) }
        if (loading) item { ExtraLoading() }
        if (error.isNotBlank()) item { Text(error, color = ExtraDanger, fontSize = 12.sp) }
        if (!loading && rows.isEmpty()) item { ExtraEmpty("此任务暂无发现") }
        items(rows, key = { it.id }) { finding ->
            Surface(Modifier.fillMaxWidth().clickable { opened = finding },
                shape = RoundedCornerShape(11.dp), color = Color.White.copy(alpha = 0.94f),
                border = BorderStroke(1.dp, ExtraLine)) {
                Column(Modifier.padding(15.dp)) {
                    Row {
                        Text(severityLabel(finding.severity), color = ExtraDanger, fontSize = 11.sp)
                        Spacer(Modifier.width(10.dp))
                        Text(finding.status, color = ExtraMuted, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(7.dp))
                    Text(finding.title, color = ExtraInk, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (finding.summary.isNotBlank()) {
                        Spacer(Modifier.height(5.dp))
                        Text(finding.summary, color = ExtraMuted, fontSize = 12.sp,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (rows.size < total) item {
            OutlinedButton(onClick = { page++ }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                Text("加载更多")
            }
        }
    }
}

private fun severityLabel(severity: String) = when (severity) {
    "critical" -> "严重"; "high" -> "高危"; "medium" -> "中危"; "low" -> "低危"; else -> severity
}

@Composable
private fun ExtraAssets(task: Task, api: ArtexApi) {
    val types = listOf("" to "全部", "root_domain" to "根域名", "ip" to "IP", "subdomain" to "子域名",
        "app" to "应用", "service" to "服务", "endpoint" to "接口")
    var assets by remember(api, task.id) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var total by remember(api, task.id) { mutableStateOf(0) }
    var kind by rememberSaveable(task.id) { mutableStateOf("") }
    var page by rememberSaveable(task.id) { mutableStateOf(0) }
    var reload by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var detach by remember { mutableStateOf<JSONObject?>(null) }
    var attachIds by rememberSaveable(task.id) { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(api, task.id, kind, page, reload) {
        loading = assets.isEmpty()
        try {
            val path = "/api/assets?task_id=${enc(task.id)}&type=${enc(kind)}&limit=30&offset=${page * 30}"
            val result = api.request("GET", path)
            val loaded = result.optJSONArray("assets").asObjects()
            assets = if (page == 0) loaded else assets + loaded
            total = result.optInt("total", result.optInt("count"))
            error = ""
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "资产读取失败" }
        finally { loading = false }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("测试资产", Modifier.weight(1f), color = ExtraInk, fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold)
                Text("$total 项", color = ExtraMuted, fontSize = 12.sp)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                types.forEach { (value, label) ->
                    FilterChip(selected = kind == value, onClick = {
                        if (kind != value) { kind = value; page = 0; assets = emptyList() }
                        else reload++
                    },
                        label = { Text(label) })
                }
            }
        }
        item {
            ExtraPanel {
                Text("关联现有资产", color = ExtraInk, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(7.dp))
                OutlinedTextField(attachIds, { attachIds = it }, Modifier.fillMaxWidth(), singleLine = true,
                    placeholder = { Text("资产 ID，多个用逗号分隔") }, shape = RoundedCornerShape(10.dp))
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    val parts = attachIds.split(',', '，').map { it.trim() }.filter { it.isNotBlank() }
                    val ids = parts.mapNotNull { it.toLongOrNull()?.takeIf { id -> id > 0 } }.distinct()
                    if (ids.isEmpty() || ids.size != parts.size) {
                        error = "请输入有效资产 ID，多个 ID 用逗号分隔"; return@OutlinedButton
                    }
                    scope.launch {
                        busy = true
                        try {
                            api.request("POST", "/api/tasks/${enc(task.id)}/assets",
                                JSONObject().put("asset_ids", JSONArray(ids)).put("source_summary", "Android 手动关联"))
                            attachIds = ""; page = 0; assets = emptyList(); reload++
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: "关联资产失败" }
                        finally { busy = false }
                    }
                }, enabled = !busy) { Text("关联") }
            }
        }
        if (loading) item { ExtraLoading() }
        if (error.isNotBlank()) item { Text(error, color = ExtraDanger, fontSize = 12.sp) }
        if (!loading && assets.isEmpty()) item { ExtraEmpty("此任务暂无资产") }
        items(assets, key = { it.optLong("id") }) { asset ->
            ExtraPanel {
                Text(asset.optString("type"), color = ExtraAccent, fontSize = 11.sp)
                Spacer(Modifier.height(5.dp))
                Text(assetLabel(asset), color = ExtraInk, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("#${asset.optLong("id")}", color = ExtraMuted, fontSize = 11.sp)
                TextButton(onClick = { detach = asset }) { Text("从任务移除", color = ExtraDanger) }
            }
        }
        if (assets.size < total) item {
            OutlinedButton(onClick = { page++ }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                Text("加载更多")
            }
        }
    }
    detach?.let { asset ->
        AlertDialog(onDismissRequest = { detach = null }, title = { Text("移除任务资产") },
            text = { Text(assetLabel(asset)) },
            confirmButton = { TextButton(onClick = {
                if (!busy) scope.launch {
                    busy = true
                    try {
                        api.request("DELETE", "/api/tasks/${enc(task.id)}/assets/${asset.optLong("id")}")
                        detach = null; page = 0; assets = emptyList(); reload++
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "移除资产失败" }
                    finally { busy = false }
                }
            }, enabled = !busy) { Text("确认移除", color = ExtraDanger) } },
            dismissButton = { TextButton(onClick = { detach = null }) { Text("取消") } })
    }
}

private fun assetLabel(asset: JSONObject): String = when (asset.optString("type")) {
    "root_domain", "subdomain" -> asset.optString("domain")
    "ip" -> asset.optString("ip")
    "app" -> asset.optString("app_name").ifBlank { asset.optString("url") }
    "service" -> asset.optString("url").ifBlank {
        listOf(asset.optString("ip").ifBlank { asset.optString("domain") },
            asset.optInt("port").takeIf { it > 0 }?.toString().orEmpty()).filter { it.isNotBlank() }.joinToString(":")
    }
    "endpoint" -> listOf(asset.optString("method"), asset.optString("url"))
        .filter { it.isNotBlank() }.joinToString(" ")
    else -> ""
}.ifBlank { "资产 #${asset.optLong("id")}" }

private val nodeKinds = listOf("" to "全部", "goal" to "目标", "intent" to "意图",
    "fact" to "事实", "finding" to "发现", "hint" to "提示", "digest" to "压缩")

@Composable
private fun ExtraExploration(task: Task, api: ArtexApi, lineage: Boolean) {
    var nodes by remember(api, task.id, lineage) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var edges by remember(api, task.id, lineage) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var refs by remember(api, task.id, lineage) { mutableStateOf(JSONObject()) }
    var total by remember(api, task.id, lineage) { mutableStateOf(0) }
    var page by rememberSaveable(task.id, lineage) { mutableStateOf(1) }
    var kind by rememberSaveable(task.id, lineage) { mutableStateOf("") }
    var draft by rememberSaveable(task.id, lineage) { mutableStateOf("") }
    var query by rememberSaveable(task.id, lineage) { mutableStateOf("") }
    var ascending by rememberSaveable(task.id, lineage) { mutableStateOf(false) }
    var fullGraph by rememberSaveable(task.id, lineage) { mutableStateOf(false) }
    var expanded by rememberSaveable(task.id, lineage) { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(api, task.id, lineage, page, kind, query, ascending, fullGraph, reload) {
        loading = true
        try {
            if (lineage && fullGraph) {
                val result = api.request("GET", "/api/exploration/graph?task=${enc(task.id)}", readTimeoutMs = 60_000)
                val all = result.optJSONArray("nodes").asObjects()
                nodes = all.filter { item ->
                    (kind.isBlank() || item.optString("type") == kind) &&
                        (query.isBlank() || nodeSummary(item).contains(query, ignoreCase = true))
                }.sortedByDescending { it.optString("ts") }
                edges = result.optJSONArray("edges").asObjects()
                refs = JSONObject().apply { all.forEach { put(it.optString("id"), it) } }
                total = nodes.size
            } else {
                val path = buildString {
                    append("/api/exploration/nodes?task=${enc(task.id)}&page=$page&size=20")
                    if (kind.isNotBlank()) append("&kind=${enc(kind)}")
                    if (query.isNotBlank()) append("&q=${enc(query)}")
                    if (ascending) append("&order=asc")
                }
                val result = api.request("GET", path)
                nodes = result.optJSONArray("items").asObjects()
                edges = result.optJSONArray("edges").asObjects()
                refs = result.optJSONObject("refs") ?: JSONObject()
                total = result.optInt("total")
            }
            error = ""
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "探索记录读取失败" }
        finally { loading = false }
    }
    val sourceEdges = remember(edges) { edges.groupBy { it.optString("dst") } }
    val outputEdges = remember(edges) { edges.groupBy { it.optString("src") } }
    fun refLabel(id: String): String {
        val node = refs.optJSONObject(id) ?: nodes.firstOrNull { it.optString("id") == id }
        return node?.let { "${nodeKindLabel(it.optString("type"))} · ${nodeSummary(it).take(70)}" } ?: "节点 #$id"
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (lineage) "探索链路" else "播报板", Modifier.weight(1f), color = ExtraInk,
                    fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { reload++ }) { Text("刷新") }
            }
        }
        item { Text(if (lineage) "按节点查看上游来源和下游产出。" else "按时间查看探索进展。",
            color = ExtraMuted, fontSize = 12.sp) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), singleLine = true,
                    placeholder = { Text("搜索节点内容") }, shape = RoundedCornerShape(10.dp))
                TextButton(onClick = { query = draft.trim(); page = 1 }) { Text("搜索") }
            }
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                nodeKinds.forEach { (value, label) ->
                    FilterChip(selected = kind == value, onClick = { kind = value; page = 1 }, label = { Text(label) })
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (lineage) TextButton(onClick = { fullGraph = !fullGraph; page = 1 }) {
                    Text(if (fullGraph) "使用分页视图" else "载入完整链路（含继承）")
                } else TextButton(onClick = { ascending = !ascending; page = 1 }) {
                    Text(if (ascending) "正序 · 切换倒序" else "倒序 · 切换正序")
                }
                Spacer(Modifier.weight(1f))
                Text("$total 节点", color = ExtraMuted, fontSize = 11.sp)
            }
        }
        if (loading) item { ExtraLoading() }
        if (error.isNotBlank()) item { Text(error, color = ExtraDanger, fontSize = 12.sp) }
        if (!loading && nodes.isEmpty() && error.isBlank()) item { ExtraEmpty("没有探索记录") }
        items(nodes) { node ->
            val id = node.optString("id")
            ExtraPanel {
                Column(Modifier.fillMaxWidth().clickable { expanded = if (expanded == id) "" else id }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(nodeKindLabel(node.optString("type")), color = ExtraAccent, fontSize = 11.sp)
                        Spacer(Modifier.width(9.dp))
                        Text(node.optString("state"), color = ExtraMuted, fontSize = 11.sp)
                        Spacer(Modifier.weight(1f))
                        Text("#$id", color = ExtraMuted, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(nodeSummary(node), color = ExtraInk, fontSize = 13.sp, lineHeight = 20.sp,
                        maxLines = if (expanded == id) Int.MAX_VALUE else 3,
                        overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(5.dp))
                    Text(node.optString("ts") + if (node.optBoolean("inherited")) " · 继承" else "",
                        color = ExtraMuted, fontSize = 11.sp)
                    if (expanded == id) {
                        HorizontalDivider(Modifier.padding(vertical = 11.dp), color = ExtraLine)
                        val upstream = sourceEdges[id].orEmpty()
                        val downstream = outputEdges[id].orEmpty()
                        Text("上游 · ${upstream.size}", color = ExtraMuted, fontSize = 11.sp)
                        upstream.take(20).forEach { edge ->
                            Text("${relationLabel(edge.optString("rel"))} · ${refLabel(edge.optString("src"))}",
                                color = ExtraInk, fontSize = 12.sp, lineHeight = 18.sp)
                        }
                        Text("下游 · ${downstream.size}", Modifier.padding(top = 9.dp), color = ExtraMuted, fontSize = 11.sp)
                        downstream.take(20).forEach { edge ->
                            Text("${relationLabel(edge.optString("rel"))} · ${refLabel(edge.optString("dst"))}",
                                color = ExtraInk, fontSize = 12.sp, lineHeight = 18.sp)
                        }
                    }
                }
            }
        }
        if (!fullGraph && page * 20 < total) item {
            OutlinedButton(onClick = { page++ }, modifier = Modifier.fillMaxWidth(), enabled = !loading) {
                Text("下一页")
            }
        }
        if (!fullGraph && page > 1) item {
            TextButton(onClick = { page-- }, modifier = Modifier.fillMaxWidth(), enabled = !loading) {
                Text("上一页")
            }
        }
    }
}

private fun nodeKindLabel(type: String): String = when (type) {
    "goal" -> "目标"; "intent" -> "意图"; "fact" -> "事实"; "finding" -> "发现"
    "hint" -> "提示"; "digest" -> "压缩"; "task" -> "任务"; else -> type
}

private fun relationLabel(rel: String): String = when (rel) {
    "spawns" -> "派生"; "derived_from" -> "意图链"; "yields" -> "产出"
    "proves" -> "证明"; "covers" -> "覆盖"; else -> rel
}

private fun nodeSummary(node: JSONObject): String {
    val raw = node.optString("payload")
    if (raw.isBlank()) return "（无内容）"
    val fields = when (node.optString("type")) {
        "goal", "hint" -> listOf("text", "summary")
        "finding" -> listOf("name", "summary")
        "digest" -> listOf("body", "summary")
        else -> listOf("summary", "description", "text")
    }
    val payload = runCatching { JSONObject(raw) }.getOrNull()
    return fields.firstNotNullOfOrNull { field -> payload?.optString(field)?.takeIf { it.isNotBlank() } }
        ?: raw.take(2_000)
}

private val coverageKinds = listOf("" to "全部", "company" to "公司", "root_domain" to "根域名",
    "subdomain" to "子域名", "ip" to "IP", "service" to "服务", "app" to "应用", "endpoint" to "接口")

@Composable
private fun ExtraCoverageGraph(task: Task, api: ArtexApi) {
    var nodes by remember(api, task.id) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var edges by remember(api, task.id) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    var kind by rememberSaveable(task.id) { mutableStateOf("") }
    var search by rememberSaveable(task.id) { mutableStateOf("") }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var linked by remember { mutableStateOf<JSONObject?>(null) }
    var linkedError by remember { mutableStateOf("") }

    LaunchedEffect(api, task.id, reload) {
        loading = true
        try {
            val result = api.request("GET", "/api/tasks/${enc(task.id)}/coverage-graph", readTimeoutMs = 60_000)
            nodes = result.optJSONArray("nodes").asObjects()
            edges = result.optJSONArray("edges").asObjects()
            error = ""
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "覆盖图读取失败" }
        finally { loading = false }
    }
    LaunchedEffect(api, task.id, selected) {
        val id = selected?.optLong("asset_id") ?: 0
        linked = null
        linkedError = ""
        if (id > 0) {
            try { linked = api.request("GET", "/api/tasks/${enc(task.id)}/asset-refs?asset_id=$id") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { linkedError = e.message ?: "关联记录读取失败" }
        }
    }
    val displayed = remember(nodes, kind, search) {
        nodes.filter { item ->
            (kind.isBlank() || item.optString("kind") == kind) &&
                (search.isBlank() || item.optString("label").contains(search, ignoreCase = true))
        }.sortedWith(compareBy<JSONObject> { it.optString("kind") }.thenByDescending { it.optBoolean("tested") }
            .thenBy { it.optString("label") })
    }
    val parents = remember(edges) { edges.associate { it.optString("src") to it.optString("dst") } }
    val labels = remember(nodes) { nodes.associate { it.optString("key") to it.optString("label") } }
    val inScope = nodes.count { it.optBoolean("in_scope") }
    val tested = nodes.count { it.optBoolean("in_scope") && it.optBoolean("tested") }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("资产覆盖", Modifier.weight(1f), color = ExtraInk, fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { reload++ }) { Text("刷新") }
            }
        }
        item { Text("范围内 $inScope · 已测试 $tested · 连线 ${edges.size}", color = ExtraMuted, fontSize = 12.sp) }
        item {
            OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("搜索资产") }, shape = RoundedCornerShape(10.dp))
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                coverageKinds.forEach { (value, label) ->
                    FilterChip(selected = kind == value, onClick = { kind = value }, label = { Text(label) })
                }
            }
        }
        if (loading) item { ExtraLoading() }
        if (error.isNotBlank()) item { Text(error, color = ExtraDanger, fontSize = 12.sp) }
        if (!loading && displayed.isEmpty() && error.isBlank()) item { ExtraEmpty("没有匹配资产") }
        items(displayed, key = { it.optString("key") }) { node ->
            val parent = parents[node.optString("key")]?.let { labels[it] }
            ExtraPanel {
                Column(Modifier.fillMaxWidth().clickable { selected = node }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(coverageKinds.firstOrNull { it.first == node.optString("kind") }?.second
                            ?: node.optString("kind"), color = ExtraAccent, fontSize = 11.sp)
                        Spacer(Modifier.weight(1f))
                        Text(when {
                            !node.optBoolean("in_scope") -> "上下文"
                            node.optBoolean("tested") -> "已测试"
                            else -> "未测试"
                        }, color = ExtraMuted, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(node.optString("label"), color = ExtraInk, fontSize = 13.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (!parent.isNullOrBlank()) Text("归属 · $parent", color = ExtraMuted, fontSize = 11.sp)
                }
            }
        }
    }
    selected?.let { node ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text(node.optString("label")) },
            text = {
                Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                    val kindLabel = coverageKinds.firstOrNull { it.first == node.optString("kind") }?.second
                        ?: node.optString("kind")
                    val testedLabel = if (node.optBoolean("tested")) "已测试" else "未测试"
                    Text("$kindLabel · $testedLabel", fontSize = 12.sp, color = ExtraMuted)
                    if (linkedError.isNotBlank()) Text(linkedError, color = ExtraDanger, fontSize = 12.sp)
                    if (node.optLong("asset_id") > 0 && linked == null && linkedError.isBlank()) ExtraLoading()
                    linked?.let { data ->
                        listOf("intents" to "相关意图", "facts" to "相关事实", "findings" to "相关发现").forEach { (key, label) ->
                            val refs = data.optJSONArray(key).asObjects()
                            Spacer(Modifier.height(11.dp))
                            Text("$label · ${refs.size}", color = ExtraInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            refs.take(30).forEach { ref -> Text(ref.optString("summary"),
                                Modifier.padding(top = 4.dp), color = ExtraMuted, fontSize = 12.sp) }
                        }
                    }
                }
            }, confirmButton = { TextButton(onClick = { selected = null }) { Text("关闭") } })
    }
}

@Composable
private fun ExtraIntercept(task: Task, api: ArtexApi) {
    val filters = listOf("" to "全部", "pending" to "待审批", "allowed" to "已允许",
        "denied" to "已拒绝", "timeout" to "已超时")
    var history by remember(api, task.id) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var pending by remember(api, task.id) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var total by remember(api, task.id) { mutableStateOf(0) }
    var page by rememberSaveable(task.id) { mutableStateOf(1) }
    var status by rememberSaveable(task.id) { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var deciding by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<JSONObject?>(null) }
    var detail by remember { mutableStateOf<JSONObject?>(null) }
    var decision by remember { mutableStateOf<Pair<Long, String>?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(api, task.id, page, status, reload) {
        while (true) {
            loading = history.isEmpty() && pending.isEmpty()
            try {
                val path = "/api/intercept/task/${enc(task.id)}?page=$page&size=20" +
                    status.takeIf { it.isNotBlank() }?.let { "&status=${enc(it)}" }.orEmpty()
                val result = api.request("GET", path)
                history = result.optJSONArray("items").asObjects()
                total = result.optInt("total")
                error = ""
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "审批记录读取失败" }
            try {
                pending = api.request("GET", "/api/intercept/pending")
                    .optJSONArray("pending").asObjects().filter { it.optString("task_id") == task.id }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* History remains readable if the pending queue is unavailable. */ }
            loading = false
            delay(10_000)
        }
    }
    LaunchedEffect(api, selected) {
        val item = selected ?: return@LaunchedEffect
        detail = null
        try {
            val id = item.optLong("id")
            detail = api.request("GET", if (item.optString("status") == "pending")
                "/api/intercept/pending/$id" else "/api/intercept/history/$id")
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { detail = item }
    }
    val pendingIds = remember(pending) { pending.map { it.optLong("id") }.toSet() }
    val rows = remember(history, pending, status) {
        val historyIds = history.map { it.optLong("id") }.toSet()
        val actionable = if (status.isBlank() || status == "pending")
            pending.filterNot { it.optLong("id") in historyIds } else emptyList()
        actionable + history
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("拦截审批", Modifier.weight(1f), color = ExtraInk, fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { reload++ }) { Text("刷新") }
            }
        }
        item { Text("待审批项独立显示，避免被历史分页挤出。", color = ExtraMuted, fontSize = 12.sp) }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                filters.forEach { (value, label) ->
                    FilterChip(selected = status == value, onClick = { status = value; page = 1 },
                        label = { Text(label) })
                }
            }
        }
        if (loading) item { ExtraLoading() }
        if (error.isNotBlank()) item { Text(error, color = ExtraDanger, fontSize = 12.sp) }
        if (!loading && rows.isEmpty() && error.isBlank()) item { ExtraEmpty("没有审批记录") }
        items(rows, key = { it.optLong("id") }) { row ->
            val id = row.optLong("id")
            ExtraPanel {
                Column(Modifier.fillMaxWidth().clickable { selected = row }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(approvalStatus(row.optString("status")), color = if (row.optString("status") == "pending")
                            ExtraDanger else ExtraAccent, fontSize = 11.sp)
                        Spacer(Modifier.weight(1f))
                        Text("#$id", color = ExtraMuted, fontSize = 11.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(row.optString("tool_name"), color = ExtraInk, fontSize = 14.sp,
                        fontWeight = FontWeight.Medium)
                    Text(row.optString("agent_name") + " · " + row.optString("created_at"),
                        color = ExtraMuted, fontSize = 11.sp)
                    if (row.optString("reason").isNotBlank()) Text(row.optString("reason"),
                        color = ExtraMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                if (id in pendingIds && !deciding) {
                    Row {
                        TextButton(onClick = { decision = id to "allowed" }) { Text("允许") }
                        TextButton(onClick = { decision = id to "denied" }) { Text("拒绝", color = ExtraDanger) }
                    }
                }
            }
        }
        if (page * 20 < total) item {
            OutlinedButton(onClick = { page++ }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                Text("下一页")
            }
        }
        if (page > 1) item {
            TextButton(onClick = { page-- }, enabled = !loading, modifier = Modifier.fillMaxWidth()) {
                Text("上一页")
            }
        }
    }
    selected?.let { item ->
        AlertDialog(onDismissRequest = { selected = null }, title = { Text("审批 #${item.optLong("id")}") },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    val current = detail ?: item
                    ExtraValueRow("状态", approvalStatus(current.optString("status")))
                    ExtraValueRow("Agent", current.optString("agent_name"))
                    ExtraValueRow("工具", current.optString("tool_name"))
                    Text("原因", color = ExtraMuted, fontSize = 12.sp)
                    Text(current.optString("reason"), color = ExtraInk, fontSize = 12.sp, lineHeight = 18.sp)
                    Spacer(Modifier.height(11.dp))
                    Text("工具输入", color = ExtraMuted, fontSize = 12.sp)
                    Text(current.optJSONObject("tool_input")?.toString(2).orEmpty().ifBlank { "未记录" },
                        color = ExtraInk, fontSize = 11.sp, lineHeight = 17.sp)
                    detail?.optJSONObject("audit")?.let { audit ->
                        Spacer(Modifier.height(11.dp))
                        Text("执行结果", color = ExtraMuted, fontSize = 12.sp)
                        Text(audit.optString("execution_status"), color = ExtraInk, fontSize = 12.sp)
                    }
                }
            }, confirmButton = { TextButton(onClick = { selected = null }) { Text("关闭") } })
    }
    decision?.let { (id, action) ->
        AlertDialog(onDismissRequest = { decision = null },
            title = { Text(if (action == "allowed") "允许执行工具？" else "拒绝执行工具？") },
            text = { Text("审批 #$id。此决定会立即作用于等待中的 Agent。") },
            confirmButton = { TextButton(onClick = {
                if (!deciding) scope.launch {
                    deciding = true
                    try {
                        api.request("POST", "/api/intercept/pending/$id/decide",
                            JSONObject().put("decision", action))
                        decision = null
                        pending = pending.filterNot { it.optLong("id") == id }
                        reload++
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "审批失败" }
                    finally { deciding = false }
                }
            }, enabled = !deciding) { Text("确认${if (action == "allowed") "允许" else "拒绝"}") } },
            dismissButton = { TextButton(onClick = { decision = null }) { Text("取消") } })
    }
}

private fun approvalStatus(status: String): String = when (status) {
    "pending" -> "待审批"; "allowed" -> "已允许"; "denied" -> "已拒绝"
    "timeout" -> "已超时"; else -> status
}

@Composable
private fun ExtraReport(task: Task, api: ArtexApi) {
    var report by remember(api, task.id) { mutableStateOf("") }
    var loading by remember(api, task.id) { mutableStateOf(true) }
    var error by remember(api, task.id) { mutableStateOf("") }
    var reload by remember { mutableStateOf(0) }
    LaunchedEffect(api, task.id, reload) {
        loading = report.isEmpty()
        try { report = api.requestText("/api/report?task=${enc(task.id)}"); error = "" }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "报告读取失败" }
        finally { loading = false }
    }
    val paragraphs = remember(report) { report.lines().chunked(20).map { it.joinToString("\n") } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("任务报告", Modifier.weight(1f), color = ExtraInk, fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { reload++ }) { Text("刷新") }
            }
        }
        if (loading) item { ExtraLoading() }
        if (error.isNotBlank()) item { Text(error, color = ExtraDanger, fontSize = 12.sp) }
        if (!loading && report.isBlank()) item { ExtraEmpty("报告尚未生成") }
        items(paragraphs) { paragraph ->
            if (paragraph.isNotBlank()) Text(paragraph, color = ExtraInk, fontSize = 13.sp,
                lineHeight = 20.sp)
        }
    }
}

@Composable
private fun ExtraPanel(content: @Composable () -> Unit) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(11.dp),
        color = Color.White.copy(alpha = 0.94f), border = BorderStroke(1.dp, ExtraLine)) {
        Column(Modifier.padding(15.dp)) { content() }
    }
}

@Composable
private fun ExtraTitle(title: String) {
    Text(title, color = ExtraInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ExtraValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = ExtraMuted, fontSize = 12.sp)
        Text(value, color = ExtraInk, fontSize = 12.sp)
    }
}

@Composable
private fun ExtraLoading() {
    Box(Modifier.fillMaxWidth().padding(22.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun ExtraEmpty(message: String) {
    Text(message, Modifier.fillMaxWidth().padding(vertical = 20.dp), color = ExtraMuted, fontSize = 13.sp)
}
