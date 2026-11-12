package io.artex.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.artex.mobile.data.ArtexApi
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

private val AdminInk = Color(0xFF1C2E2A)
private val AdminMuted = Color(0xFF73807A)
private val AdminAccent = Color(0xFF3F6658)
private val AdminLine = Color(0xFFE3E6DE)
private val AdminDanger = Color(0xFFB56D55)

/** Editable native system screens. Uses only the same JSON API as the web client. */
@Composable
fun AdminResourceScreen(feature: FeatureDestination, api: ArtexApi, onBack: () -> Unit) {
    when (feature) {
        FeatureDestination.MCP, FeatureDestination.TOOLS, FeatureDestination.NOTIFY,
        FeatureDestination.INTERCEPT, FeatureDestination.ASSET_INTERCEPT -> JsonResourceScreen(feature, api, onBack)
        FeatureDestination.SKILLS -> SkillAdminScreen(api, onBack)
        FeatureDestination.APPROVALS -> ApprovalAdminScreen(api, onBack)
        FeatureDestination.AGENTS -> AgentAdminScreen(api, onBack)
        else -> FeatureExplorer(feature, api, onBack)
    }
}

private enum class AdminFieldKind { TEXT, NUMBER, BOOLEAN, JSON_OBJECT, JSON_ARRAY, CHOICE }
private data class AdminFieldSpec(
    val key: String,
    val label: String,
    val kind: AdminFieldKind = AdminFieldKind.TEXT,
    val choices: List<String> = emptyList(),
    val default: String = "",
)
private data class AdminResourceSpec(
    val collection: String,
    val path: String,
    val titleKey: String,
    val fields: List<AdminFieldSpec>,
    val idKey: String = "id",
)

private fun specFor(feature: FeatureDestination): AdminResourceSpec = when (feature) {
    FeatureDestination.MCP -> AdminResourceSpec("servers", "/api/mcp", "name", listOf(
        AdminFieldSpec("name", "名称"),
        AdminFieldSpec("transport", "连接方式", AdminFieldKind.CHOICE, listOf("stdio", "http", "sse"), "stdio"),
        AdminFieldSpec("command", "命令（stdio）"),
        AdminFieldSpec("args", "命令参数（JSON 数组）", AdminFieldKind.JSON_ARRAY, default = "[]"),
        AdminFieldSpec("env", "环境变量（JSON 对象）", AdminFieldKind.JSON_OBJECT, default = "{}"),
        AdminFieldSpec("url", "服务地址（HTTP/SSE）"),
        AdminFieldSpec("enabled", "启用", AdminFieldKind.BOOLEAN, default = "true"),
        AdminFieldSpec("insecure", "跳过 TLS 证书验证", AdminFieldKind.BOOLEAN),
    ))
    FeatureDestination.TOOLS -> AdminResourceSpec("tools", "/api/tools", "key", listOf(
        AdminFieldSpec("key", "工具键"),
        AdminFieldSpec("description", "说明"),
        AdminFieldSpec("schema", "参数结构（JSON 对象）", AdminFieldKind.JSON_OBJECT,
            default = "{\"type\":\"object\",\"properties\":{}}"),
        AdminFieldSpec("agents", "绑定 Agent（JSON 数组）", AdminFieldKind.JSON_ARRAY, default = "[]"),
        AdminFieldSpec("enabled", "启用", AdminFieldKind.BOOLEAN, default = "true"),
        AdminFieldSpec("kind", "自定义工具类型", AdminFieldKind.CHOICE,
            listOf("shell", "command", "script", "http"), "shell"),
        AdminFieldSpec("exec", "执行配置（JSON 对象）", AdminFieldKind.JSON_OBJECT, default = "{}"),
        AdminFieldSpec("deferred", "延迟加载", AdminFieldKind.BOOLEAN),
    ), idKey = "key")
    FeatureDestination.NOTIFY -> AdminResourceSpec("channels", "/api/notify/channels", "name", listOf(
        AdminFieldSpec("name", "渠道名称"),
        AdminFieldSpec("kind", "渠道类型"),
        AdminFieldSpec("enabled", "启用", AdminFieldKind.BOOLEAN, default = "true"),
        AdminFieldSpec("mode", "推送方式", AdminFieldKind.CHOICE, listOf("realtime", "digest"), "realtime"),
        AdminFieldSpec("config", "渠道配置（JSON 对象）", AdminFieldKind.JSON_OBJECT, default = "{}"),
        AdminFieldSpec("filter", "筛选条件（JSON 对象）", AdminFieldKind.JSON_OBJECT, default = "{}"),
        AdminFieldSpec("rate_per_min", "每分钟上限（0 = 不限）", AdminFieldKind.NUMBER, default = "0"),
    ))
    FeatureDestination.INTERCEPT -> AdminResourceSpec("rules", "/api/intercept/rules", "name", listOf(
        AdminFieldSpec("name", "规则名称"),
        AdminFieldSpec("enabled", "启用", AdminFieldKind.BOOLEAN, default = "true"),
        AdminFieldSpec("priority", "优先级", AdminFieldKind.NUMBER, default = "0"),
        AdminFieldSpec("match_target", "匹配对象", AdminFieldKind.CHOICE, listOf("tool_name", "tool_input"), "tool_name"),
        AdminFieldSpec("match_type", "匹配方式", AdminFieldKind.CHOICE, listOf("string", "regex"), "string"),
        AdminFieldSpec("pattern", "匹配内容"),
        AdminFieldSpec("action", "处理方式", AdminFieldKind.CHOICE, listOf("allow", "deny", "ask"), "ask"),
        AdminFieldSpec("message", "提示信息"),
        AdminFieldSpec("timeout_enabled", "启用审批超时", AdminFieldKind.BOOLEAN),
        AdminFieldSpec("timeout_seconds", "审批超时（秒）", AdminFieldKind.NUMBER, default = "0"),
        AdminFieldSpec("timeout_action", "超时处理", AdminFieldKind.CHOICE, listOf("deny", "allow"), "deny"),
    ))
    FeatureDestination.ASSET_INTERCEPT -> AdminResourceSpec("rules", "/api/asset-intercept/rules", "pattern", listOf(
        AdminFieldSpec("enabled", "启用", AdminFieldKind.BOOLEAN, default = "true"),
        AdminFieldSpec("kind", "资产类型", AdminFieldKind.CHOICE,
            listOf("exact_domain", "exact_ip", "exact_url", "fuzzy_domain", "fuzzy_ip", "fuzzy_url", "cidr"), "exact_domain"),
        AdminFieldSpec("pattern", "匹配内容"),
        AdminFieldSpec("note", "备注"),
    ))
    else -> error("Unsupported feature $feature")
}

@Composable
private fun JsonResourceScreen(feature: FeatureDestination, api: ArtexApi, onBack: () -> Unit) {
    val spec = remember(feature) { specFor(feature) }
    val scope = rememberCoroutineScope()
    var rows by remember(feature, api) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var loading by remember(feature, api) { mutableStateOf(true) }
    var busy by remember(feature, api) { mutableStateOf(false) }
    var error by remember(feature, api) { mutableStateOf<String?>(null) }
    var notice by remember(feature, api) { mutableStateOf<String?>(null) }
    var editing by remember(feature, api) { mutableStateOf<JSONObject?>(null) }
    var editorOpen by remember(feature, api) { mutableStateOf(false) }
    var deleting by remember(feature, api) { mutableStateOf<JSONObject?>(null) }
    var extra by remember(feature, api) { mutableStateOf<List<String>>(emptyList()) }
    var deliveries by remember(feature, api) { mutableStateOf<List<JSONObject>?>(null) }
    var deliveriesPage by remember(feature, api) { mutableIntStateOf(1) }
    var deliveriesTotal by remember(feature, api) { mutableIntStateOf(0) }
    var judge by remember(feature, api) { mutableStateOf<JSONObject?>(null) }
    var toolConfig by remember(feature, api) { mutableStateOf<JSONObject?>(null) }
    var toolChoices by remember(feature, api) { mutableStateOf<List<JSONObject>>(emptyList()) }

    suspend fun reload() {
        loading = true
        try {
            val result = api.request("GET", spec.path)
            rows = result.optJSONArray(spec.collection).adminObjects()
            if (feature == FeatureDestination.NOTIFY) {
                val meta = api.request("GET", "/api/notify/meta")
                extra = meta.optJSONArray("kinds").adminObjects().map { it.optString("kind") }
            }
            error = null
        } catch (t: Exception) { error = t.message ?: "加载失败" }
        finally { loading = false }
    }
    suspend fun loadDeliveries(page: Int) {
        try {
            val result = api.request("GET", "/api/notify/deliveries?page=$page&page_size=50")
            deliveries = result.optJSONArray("deliveries").adminObjects()
            deliveriesTotal = result.optInt("total", deliveries?.size ?: 0)
            deliveriesPage = page
        } catch (t: Exception) { error = t.message ?: "加载投递记录失败" }
    }
    LaunchedEffect(feature, api) { reload() }

    Column(Modifier.fillMaxSize()) {
        AdminHeader(feature.title, onBack, onRefresh = { scope.launch { reload() } })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                AdminPageTitle(feature.title)
                if (feature == FeatureDestination.NOTIFY && extra.isNotEmpty()) {
                    Text("可用渠道：${extra.joinToString("、")}", color = AdminMuted, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { editing = null; error = null; editorOpen = true }, shape = RoundedCornerShape(9.dp)) {
                        Text("新增")
                    }
                    if (feature == FeatureDestination.NOTIFY) OutlinedButton(onClick = {
                        scope.launch { loadDeliveries(1) }
                    }) { Text("投递记录") }
                    if (feature == FeatureDestination.INTERCEPT) OutlinedButton(onClick = {
                        scope.launch {
                            try { judge = api.request("GET", "/api/intercept/judge") }
                            catch (t: Exception) { error = t.message ?: "加载模型审批配置失败" }
                        }
                    }) { Text("模型兜底") }
                    if (feature == FeatureDestination.INTERCEPT) OutlinedButton(onClick = {
                        scope.launch {
                            try {
                                toolConfig = api.request("GET", "/api/intercept/tool-config")
                                toolChoices = api.request("GET", "/api/tools").optJSONArray("tools").adminObjects()
                            } catch (t: Exception) { error = t.message ?: "加载工具范围失败" }
                        }
                    }) { Text("工具范围") }
                }
            }
            if (loading && rows.isEmpty()) item { AdminLoading() }
            error?.let { item { AdminError(it) } }
            notice?.let { item { Text(it, color = AdminAccent, fontSize = 12.sp) } }
            if (!loading && rows.isEmpty() && error == null) item { AdminEmpty() }
            items(rows, key = { it.optString(spec.idKey) }) { row ->
                val id = row.optString(spec.idKey)
                val builtin = row.optBoolean("builtin")
                val systemTool = feature == FeatureDestination.TOOLS && row.optBoolean("system")
                Surface(shape = RoundedCornerShape(11.dp), color = Color.White, border = BorderStroke(1.dp, AdminLine)) {
                    Column(Modifier.fillMaxWidth().padding(15.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(row.optString(spec.titleKey).ifBlank { "#$id" }, Modifier.weight(1f),
                                color = AdminInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            if (row.has("enabled")) Text(if (row.optBoolean("enabled")) "已启用" else "已停用",
                                color = if (row.optBoolean("enabled")) AdminAccent else AdminMuted, fontSize = 11.sp)
                        }
                        val subtitle = when (feature) {
                            FeatureDestination.MCP -> "${row.optString("transport")} · ${row.optString("url").ifBlank { row.optString("command") }}"
                            FeatureDestination.TOOLS -> if (systemTool) "内置工具" else "${row.optString("kind")} · 自定义工具"
                            FeatureDestination.NOTIFY -> if (row.optString("kind") == "onebot11") {
                                val config = row.optJSONObject("config")
                                val recipient = if (config?.optString("target_type") == "group") "QQ群" else "个人QQ"
                                "OneBot 11 · $recipient ${config?.optString("target_id").orEmpty()} · ${row.optString("mode")}"
                            } else "${row.optString("kind")} · ${row.optString("mode")}"
                            FeatureDestination.INTERCEPT -> "${row.optString("action")} · ${row.optString("pattern")}"
                            else -> "${row.optString("kind")} · ${row.optString("note")}"
                        }
                        if (subtitle.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(subtitle, color = AdminMuted, fontSize = 12.sp, maxLines = 2,
                                overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(7.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            TextButton(onClick = { editing = row; error = null; editorOpen = true }) { Text("编辑") }
                            if (feature == FeatureDestination.MCP || feature == FeatureDestination.NOTIFY ||
                                (feature == FeatureDestination.TOOLS && systemTool)) {
                                TextButton(onClick = {
                                    scope.launch {
                                        busy = true
                                        try {
                                            val result = when (feature) {
                                                FeatureDestination.MCP -> api.request("POST", "/api/mcp/$id/refresh", JSONObject(),  readTimeoutMs = 60_000)
                                                FeatureDestination.NOTIFY -> api.request("POST", "/api/notify/channels/$id/test", JSONObject(), readTimeoutMs = 45_000)
                                                else -> api.request("POST", "/api/tools/${adminEncode(id)}/reset", JSONObject())
                                            }
                                            notice = when (feature) {
                                                FeatureDestination.MCP -> "已发现 ${result.optJSONArray("tools")?.length() ?: 0} 个工具"
                                                FeatureDestination.NOTIFY -> "测试成功 · ${result.optLong("latency_ms")} ms"
                                                else -> "已恢复内置默认值"
                                            }
                                            reload()
                                        } catch (t: Exception) { error = t.message ?: "操作失败" }
                                        finally { busy = false }
                                    }
                                }, enabled = !busy) {
                                    Text(when (feature) {
                                        FeatureDestination.MCP -> "刷新工具"
                                        FeatureDestination.NOTIFY -> "测试"
                                        else -> "重置"
                                    })
                                }
                            }
                            if ((feature == FeatureDestination.INTERCEPT || feature == FeatureDestination.ASSET_INTERCEPT) &&
                                row.has("enabled")) {
                                TextButton(onClick = {
                                    scope.launch {
                                        busy = true
                                        try {
                                            api.request("POST", "${spec.path}/$id/toggle",
                                                JSONObject().put("enabled", !row.optBoolean("enabled")))
                                            reload()
                                        } catch (t: Exception) { error = t.message ?: "切换失败" }
                                        finally { busy = false }
                                    }
                                }, enabled = !busy) { Text(if (row.optBoolean("enabled")) "停用" else "启用") }
                            }
                            Spacer(Modifier.weight(1f))
                            if (!builtin && !systemTool) TextButton(onClick = { deleting = row }, enabled = !busy) {
                                Text("删除", color = AdminDanger)
                            }
                        }
                    }
                }
            }
        }
    }

    if (editorOpen) ResourceEditorDialog(feature, spec, editing, extra, busy, error,
        onDismiss = { editorOpen = false },
        onSave = { body ->
            scope.launch {
                busy = true
                try {
                    val row = editing
                    val id = row?.optString(spec.idKey).orEmpty()
                    val (method, path) = when (feature) {
                        FeatureDestination.MCP -> "POST" to spec.path
                        FeatureDestination.TOOLS -> if (row == null) "POST" to "/api/tools/custom"
                            else if (row.optBoolean("system")) "PUT" to "/api/tools/${adminEncode(id)}"
                            else "PUT" to "/api/tools/custom/${adminEncode(id)}"
                        FeatureDestination.NOTIFY -> if (row == null) "POST" to spec.path
                            else "PATCH" to "${spec.path}/$id"
                        else -> if (row == null) "POST" to spec.path else "PUT" to "${spec.path}/$id"
                    }
                    if (feature == FeatureDestination.MCP && row != null) body.put("id", row.optInt("id"))
                    api.request(method, path, body)
                    notice = "已保存"
                    editorOpen = false
                    reload()
                } catch (t: Exception) { error = t.message ?: "保存失败" }
                finally { busy = false }
            }
        })

    deleting?.let { row ->
        val id = row.optString(spec.idKey)
        AdminDeleteDialog(row.optString(spec.titleKey), onDismiss = { deleting = null }) {
            deleting = null
            scope.launch {
                busy = true
                try {
                    val path = if (feature == FeatureDestination.TOOLS) "/api/tools/custom/${adminEncode(id)}"
                        else "${spec.path}/${adminEncode(id)}"
                    api.request("DELETE", path)
                    notice = "已删除"
                    reload()
                } catch (t: Exception) { error = t.message ?: "删除失败" }
                finally { busy = false }
            }
        }
    }

    deliveries?.let { list ->
        AlertDialog(onDismissRequest = { deliveries = null }, title = { Text("最近投递") },
            text = {
                Column(Modifier.heightIn(max = 490.dp).verticalScroll(rememberScrollState())) {
                    if (list.isEmpty()) Text("暂无记录", color = AdminMuted)
                    list.forEach { row ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text("${row.optString("channel_name")} · ${row.optString("state")}", fontSize = 13.sp,
                                color = AdminInk, fontWeight = FontWeight.Medium)
                            Text(row.optString("title"), fontSize = 12.sp, color = AdminMuted)
                            if (row.optString("state") == "failed") TextButton(onClick = {
                                scope.launch {
                                    try {
                                        api.request("POST", "/api/notify/deliveries/${row.optInt("id")}/retry", JSONObject())
                                        notice = "已提交重试"
                                        deliveries = null
                                    } catch (t: Exception) { error = t.message ?: "重试失败" }
                                }
                            }) { Text("重试") }
                        }
                        HorizontalDivider(color = AdminLine)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("第 $deliveriesPage 页 · 共 $deliveriesTotal 条", Modifier.weight(1f),
                            color = AdminMuted, fontSize = 12.sp)
                        TextButton(onClick = { scope.launch { loadDeliveries(deliveriesPage - 1) } },
                            enabled = deliveriesPage > 1) { Text("上页") }
                        TextButton(onClick = { scope.launch { loadDeliveries(deliveriesPage + 1) } },
                            enabled = deliveriesPage * 50 < deliveriesTotal) { Text("下页") }
                    }
                }
            }, confirmButton = { TextButton(onClick = { deliveries = null }) { Text("关闭") } })
    }
    judge?.let { config ->
        JudgeConfigDialog(config, busy, onDismiss = { judge = null }, onSave = { body ->
            scope.launch {
                busy = true
                try {
                    api.request("PUT", "/api/intercept/judge", body)
                    judge = null
                    notice = "已保存模型兜底审批配置"
                } catch (t: Exception) { error = t.message ?: "保存失败" }
                finally { busy = false }
            }
        })
    }
    toolConfig?.let { config ->
        InterceptToolConfigDialog(config, toolChoices, busy,
            onDismiss = { toolConfig = null }, onSave = { body ->
                scope.launch {
                    busy = true
                    try {
                        api.request("PUT", "/api/intercept/tool-config", body)
                        toolConfig = null
                        notice = "已保存工具拦截范围"
                    } catch (t: Exception) { error = t.message ?: "保存失败" }
                    finally { busy = false }
                }
            })
    }
}

@Composable
private fun InterceptToolConfigDialog(original: JSONObject, tools: List<JSONObject>, busy: Boolean,
                                      onDismiss: () -> Unit, onSave: (JSONObject) -> Unit) {
    var selected by remember(original) {
        mutableStateOf(original.optJSONArray("enabled_tools").adminStrings().toSet())
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("拦截工具范围") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                if (tools.isEmpty()) Text("暂无工具", color = AdminMuted, fontSize = 12.sp)
                tools.forEach { tool ->
                    val key = tool.optString("key")
                    AdminBoolRow(key, key in selected) { checked ->
                        selected = if (checked) selected + key else selected - key
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = {
            onSave(JSONObject().put("enabled_tools", JSONArray(selected.sorted())))
        }, enabled = !busy) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun JudgeConfigDialog(original: JSONObject, busy: Boolean, onDismiss: () -> Unit, onSave: (JSONObject) -> Unit) {
    var enabled by remember(original) { mutableStateOf(original.optBoolean("enabled")) }
    var profileId by remember(original) { mutableStateOf(original.optInt("profile_id").toString()) }
    var prompt by remember(original) { mutableStateOf(original.optString("prompt")) }
    var timeout by remember(original) { mutableStateOf(original.optInt("timeout_seconds").toString()) }
    var failAction by remember(original) { mutableStateOf(original.optString("fail_action", "deny")) }
    var askTimeout by remember(original) { mutableStateOf(original.optInt("ask_timeout_seconds").toString()) }
    var askTimeoutAction by remember(original) { mutableStateOf(original.optString("ask_timeout_action", "deny")) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("模型兜底审批") },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AdminBoolRow("启用模型兜底", enabled) { enabled = it }
                AdminTextField("LLM 配置 ID（0 = 激活配置）", profileId, { profileId = it }, KeyboardType.Number)
                AdminTextField("判定提示词", prompt, { prompt = it }, multiline = true)
                AdminTextField("模型调用超时（秒）", timeout, { timeout = it }, KeyboardType.Number)
                AdminChoiceRow("模型失败时", failAction, listOf("allow", "ask", "deny")) { failAction = it }
                AdminTextField("人工审批超时（秒）", askTimeout, { askTimeout = it }, KeyboardType.Number)
                AdminChoiceRow("人工审批超时后", askTimeoutAction, listOf("allow", "deny")) { askTimeoutAction = it }
            }
        }, confirmButton = { TextButton(onClick = {
            onSave(JSONObject()
                .put("enabled", enabled)
                .put("profile_id", profileId.toIntOrNull() ?: 0)
                .put("prompt", prompt)
                .put("timeout_seconds", timeout.toIntOrNull() ?: 0)
                .put("fail_action", failAction)
                .put("ask_timeout_seconds", askTimeout.toIntOrNull() ?: 0)
                .put("ask_timeout_action", askTimeoutAction))
        }, enabled = !busy) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun ResourceEditorDialog(feature: FeatureDestination, spec: AdminResourceSpec,
                                 original: JSONObject?, options: List<String>, busy: Boolean, serverError: String?, onDismiss: () -> Unit,
                                 onSave: (JSONObject) -> Unit) {
    val values = remember(feature, original) {
        mutableStateMapOf<String, String>().apply {
            spec.fields.forEach { field ->
                val value = original?.opt(field.key)
                put(field.key, if (value == null || value == JSONObject.NULL) field.default else value.toString())
            }
        }
    }
    var feedback by remember(feature, original) { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(if (original == null) "新增${feature.title}" else "编辑${feature.title}") },
        text = {
            Column(Modifier.heightIn(max = 510.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                spec.fields.forEach { field ->
                    if (feature == FeatureDestination.TOOLS && original != null && field.key == "key") {
                        Text("工具键：${original.optString("key")}", color = AdminMuted, fontSize = 12.sp)
                    } else if (feature != FeatureDestination.TOOLS || original?.optBoolean("system") != true ||
                        field.key !in setOf("kind", "exec", "deferred")) {
                        if (feature == FeatureDestination.NOTIFY && field.key == "config" && values["kind"] == "onebot11") {
                            OneBotConfigFields(values[field.key].orEmpty(), original) { key, value ->
                                val latest = runCatching { JSONObject(values["config"].orEmpty()) }.getOrDefault(JSONObject())
                                values["config"] = latest.put(key, value).toString()
                            }
                        } else {
                            val displayed = if (feature == FeatureDestination.NOTIFY && field.key == "kind" && options.isNotEmpty())
                                field.copy(kind = AdminFieldKind.CHOICE, choices = options) else field
                            AdminFormField(displayed, values[field.key].orEmpty()) {
                                if (feature == FeatureDestination.NOTIFY && field.key == "kind" && values[field.key] != it)
                                    values["config"] = "{}"
                                values[field.key] = it
                            }
                        }
                    }
                }
                feedback?.let { Text(it, color = AdminDanger, fontSize = 12.sp) }
                serverError?.let { Text(it, color = AdminDanger, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                try {
                    val body = JSONObject()
                    spec.fields.forEach { field ->
                        val raw = values[field.key].orEmpty()
                        val value: Any = when (field.kind) {
                            AdminFieldKind.BOOLEAN -> raw.toBoolean()
                            AdminFieldKind.NUMBER -> raw.toIntOrNull() ?: throw IllegalArgumentException("${field.label}必须是整数")
                            AdminFieldKind.JSON_OBJECT -> JSONObject(raw)
                            AdminFieldKind.JSON_ARRAY -> JSONArray(raw)
                            else -> raw.trim()
                        }
                        body.put(field.key, value)
                    }
                    if (feature == FeatureDestination.MCP && body.optString("name").isBlank())
                        throw IllegalArgumentException("请填写名称")
                    if (feature == FeatureDestination.TOOLS && body.optString("key").isBlank())
                        throw IllegalArgumentException("请填写工具键")
                    if (feature == FeatureDestination.NOTIFY && (body.optString("name").isBlank() || body.optString("kind").isBlank()))
                        throw IllegalArgumentException("请填写渠道名称和类型")
                    if (feature == FeatureDestination.NOTIFY && body.optString("kind") == "onebot11") {
                        val config = body.getJSONObject("config")
                        if (config.optString("target_type").isBlank()) config.put("target_type", "group")
                        if (config.optString("base_url").isBlank()) throw IllegalArgumentException("请填写 OneBot API 地址")
                        if (config.optString("target_type") !in setOf("group", "private"))
                            throw IllegalArgumentException("请选择群聊或个人QQ")
                        val target = config.optString("target_id")
                        if (!target.matches(Regex("[1-9][0-9]*"))) throw IllegalArgumentException("请填写有效的群号或QQ号")
                        val previous = original?.takeIf { it.optString("kind") == "onebot11" }?.optJSONObject("config")
                        val destinationChanged = previous != null && listOf("base_url", "target_type", "target_id")
                            .any { config.optString(it) != previous.optString(it) }
                        if (destinationChanged && !config.has("access_token")) config.put("access_token", "")
                        if (destinationChanged && config.optString("access_token").startsWith("__masked__"))
                            throw IllegalArgumentException("接收目标已变更，请重新填写访问令牌或明确清除")
                    }
                    onSave(body)
                } catch (t: Exception) { feedback = t.message ?: "输入有误" }
            }, enabled = !busy) { Text("保存") }
        }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun OneBotConfigFields(value: String, original: JSONObject?, onChange: (String, String) -> Unit) {
    val config = remember(value) { runCatching { JSONObject(value) }.getOrDefault(JSONObject()) }
    val targetType = config.optString("target_type").ifBlank { "group" }
    val originalToken = original?.takeIf { it.optString("kind") == "onebot11" }
        ?.optJSONObject("config")?.optString("access_token").orEmpty()
    val token = config.optString("access_token")
    val masked = token.startsWith("__masked__")

    Text("OneBot 11 接收配置", color = AdminInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    AdminTextField("OneBot API 地址", config.optString("base_url"),
        { onChange("base_url", it) }, KeyboardType.Uri)
    Text("填写 ARTEX 服务器可访问的 HTTP(S) API 地址。服务端访问本机地址时需启用 ARTEX_NOTIFY_ALLOW_LOCAL=1。",
        color = AdminMuted, fontSize = 11.sp, lineHeight = 16.sp)
    Column {
        Text("发送到", color = AdminInk, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(5.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = targetType == "group", onClick = { onChange("target_type", "group") },
                label = { Text("QQ群") }, shape = RoundedCornerShape(7.dp))
            FilterChip(selected = targetType == "private", onClick = { onChange("target_type", "private") },
                label = { Text("个人QQ") }, shape = RoundedCornerShape(7.dp))
        }
    }
    AdminTextField(if (targetType == "group") "群号" else "QQ号", config.optString("target_id"),
        { onChange("target_id", it) }, KeyboardType.Number)
    Column {
        Text("访问令牌（可选）", color = AdminInk, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(5.dp))
        OutlinedTextField(value = if (masked) "" else token, onValueChange = { onChange("access_token", it) },
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { Text(if (masked) "已设置，留空保留" else "未设置") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            visualTransformation = PasswordVisualTransformation(),
            shape = RoundedCornerShape(8.dp), textStyle = LocalTextStyle.current.copy(fontSize = 13.sp))
        if (masked) TextButton(onClick = { onChange("access_token", "") }) { Text("清除已保存的令牌") }
        else if (originalToken.startsWith("__masked__") && token.isBlank())
            Text("令牌将在保存后清除", color = AdminMuted, fontSize = 11.sp)
    }
}

@Composable
private fun AdminFormField(field: AdminFieldSpec, value: String, onChange: (String) -> Unit) {
    when (field.kind) {
        AdminFieldKind.BOOLEAN -> Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(field.label, Modifier.weight(1f), color = AdminInk, fontSize = 13.sp)
            Switch(checked = value == "true", onCheckedChange = { onChange(it.toString()) })
        }
        AdminFieldKind.CHOICE -> Column {
            Text(field.label, color = AdminInk, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                field.choices.forEach { choice ->
                    FilterChip(selected = value == choice, onClick = { onChange(choice) },
                        label = { Text(choice, fontSize = 11.sp) }, shape = RoundedCornerShape(7.dp))
                }
            }
        }
        else -> Column {
            Text(field.label, color = AdminInk, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(5.dp))
            OutlinedTextField(value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth(),
                minLines = if (field.kind == AdminFieldKind.JSON_OBJECT || field.kind == AdminFieldKind.JSON_ARRAY) 2 else 1,
                maxLines = if (field.kind == AdminFieldKind.JSON_OBJECT || field.kind == AdminFieldKind.JSON_ARRAY) 7 else 2,
                keyboardOptions = KeyboardOptions(keyboardType = if (field.kind == AdminFieldKind.NUMBER) KeyboardType.Number else KeyboardType.Text),
                shape = RoundedCornerShape(8.dp), textStyle = LocalTextStyle.current.copy(fontSize = 13.sp))
        }
    }
}

@Composable
private fun SkillAdminScreen(api: ArtexApi, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var skills by remember(api) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selected by remember(api) { mutableStateOf<JSONObject?>(null) }
    var files by remember(api) { mutableStateOf<List<String>>(emptyList()) }
    var editingSkill by remember(api) { mutableStateOf<JSONObject?>(null) }
    var skillEditorOpen by remember(api) { mutableStateOf(false) }
    var filePath by remember(api) { mutableStateOf<String?>(null) }
    var fileContent by remember(api) { mutableStateOf("") }
    var newPath by remember(api) { mutableStateOf<String?>(null) }
    var newPathIsDir by remember(api) { mutableStateOf(false) }
    var deleting by remember(api) { mutableStateOf<Pair<String, String>?>(null) }
    var busy by remember(api) { mutableStateOf(false) }
    var loading by remember(api) { mutableStateOf(true) }
    var error by remember(api) { mutableStateOf<String?>(null) }
    var notice by remember(api) { mutableStateOf<String?>(null) }

    suspend fun reloadSkills() {
        loading = true
        try {
            skills = api.request("GET", "/api/skills").optJSONArray("skills").adminObjects()
            selected = selected?.let { old -> skills.find { it.optString("name") == old.optString("name") } }
            error = null
        } catch (t: Exception) { error = t.message ?: "加载失败" }
        finally { loading = false }
    }
    suspend fun loadFiles(skill: JSONObject) {
        try {
            files = api.request("GET", "/api/skills/${adminEncode(skill.optString("name"))}/files")
                .optJSONArray("files").adminStrings()
            error = null
        } catch (t: Exception) { error = t.message ?: "加载文件失败" }
    }
    LaunchedEffect(api) { reloadSkills() }
    BackHandler(enabled = selected != null) { selected = null; files = emptyList() }

    Column(Modifier.fillMaxSize()) {
        AdminHeader(if (selected == null) "Skill" else selected!!.optString("name"),
            onBack = { if (selected != null) { selected = null; files = emptyList() } else onBack() },
            onRefresh = { scope.launch { if (selected == null) reloadSkills() else loadFiles(selected!!) } })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            if (selected == null) {
                item {
                    AdminPageTitle("Skill")
                    Button(onClick = { editingSkill = null; skillEditorOpen = true }, shape = RoundedCornerShape(9.dp)) {
                        Text("新增 Skill")
                    }
                }
                if (loading && skills.isEmpty()) item { AdminLoading() }
                error?.let { item { AdminError(it) } }
                notice?.let { item { Text(it, color = AdminAccent, fontSize = 12.sp) } }
                if (!loading && skills.isEmpty() && error == null) item { AdminEmpty() }
                items(skills, key = { it.optString("name") }) { skill ->
                    AdminListRow(skill.optString("name"), skill.optString("description")) {
                        selected = skill
                        scope.launch { loadFiles(skill) }
                    }
                }
            } else {
                val skill = selected!!
                val skillName = skill.optString("name")
                item {
                    Text(skill.optString("description"), color = AdminMuted, fontSize = 13.sp)
                    Spacer(Modifier.height(9.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        OutlinedButton(onClick = { editingSkill = skill; skillEditorOpen = true }) { Text("编辑信息") }
                        OutlinedButton(onClick = { deleting = "skill" to skillName }) { Text("删除") }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("文件", color = AdminInk, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(7.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        TextButton(onClick = { newPathIsDir = false; newPath = "" }) { Text("新建文件") }
                        TextButton(onClick = { newPathIsDir = true; newPath = "" }) { Text("新建目录") }
                    }
                }
                error?.let { item { AdminError(it) } }
                notice?.let { item { Text(it, color = AdminAccent, fontSize = 12.sp) } }
                if (files.isEmpty()) item { Text("暂无文件", color = AdminMuted, fontSize = 13.sp) }
                items(files, key = { it }) { path ->
                    Column {
                        Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(path, Modifier.weight(1f).clickable(enabled = !path.endsWith('/')) {
                                scope.launch {
                                    try {
                                        fileContent = api.request("GET", "/api/skills/${adminEncode(skillName)}/files/${adminEncodePath(path)}")
                                            .optString("content")
                                        filePath = path
                                    } catch (t: Exception) { error = t.message ?: "读取文件失败" }
                                }
                            }, color = AdminInk, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { deleting = "file" to path }) { Text("删除", color = AdminDanger) }
                        }
                        HorizontalDivider(color = AdminLine)
                    }
                }
            }
        }
    }

    if (skillEditorOpen) SkillEditorDialog(editingSkill, busy, onDismiss = { skillEditorOpen = false }) { body ->
        scope.launch {
            busy = true
            try {
                val oldName = editingSkill?.optString("name")
                if (oldName == null) api.request("POST", "/api/skills", body)
                else api.request("PUT", "/api/skills/${adminEncode(oldName)}/meta", body)
                skillEditorOpen = false
                notice = "已保存"
                reloadSkills()
            } catch (t: Exception) { error = t.message ?: "保存失败" }
            finally { busy = false }
        }
    }

    filePath?.let { path ->
        var draft by remember(path) { mutableStateOf(fileContent) }
        AlertDialog(onDismissRequest = { filePath = null }, title = { Text(path) },
            text = {
                OutlinedTextField(value = draft, onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 230.dp, max = 430.dp),
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp))
            }, confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        busy = true
                        try {
                            api.request("PUT", "/api/skills/${adminEncode(selected!!.optString("name"))}/files/${adminEncodePath(path)}",
                                JSONObject().put("content", draft))
                            notice = "已保存文件"
                            filePath = null
                            loadFiles(selected!!)
                        } catch (t: Exception) { error = t.message ?: "保存文件失败" }
                        finally { busy = false }
                    }
                }, enabled = !busy) { Text("保存") }
            }, dismissButton = { TextButton(onClick = { filePath = null }) { Text("取消") } })
    }

    newPath?.let { initial ->
        var input by remember(newPathIsDir) { mutableStateOf(initial) }
        AlertDialog(onDismissRequest = { newPath = null },
            title = { Text(if (newPathIsDir) "新建目录" else "新建文件") },
            text = { AdminTextField("相对路径", input, { input = it }) },
            confirmButton = { TextButton(onClick = {
                if (input.isBlank()) { error = "请输入路径"; return@TextButton }
                scope.launch {
                    busy = true
                    try {
                        val name = selected!!.optString("name")
                        if (newPathIsDir) api.request("POST", "/api/skills/${adminEncode(name)}/dirs",
                            JSONObject().put("path", input.trim()))
                        else api.request("PUT", "/api/skills/${adminEncode(name)}/files/${adminEncodePath(input.trim())}",
                            JSONObject().put("content", ""))
                        newPath = null
                        loadFiles(selected!!)
                    } catch (t: Exception) { error = t.message ?: "创建失败" }
                    finally { busy = false }
                }
            }, enabled = !busy) { Text("创建") } },
            dismissButton = { TextButton(onClick = { newPath = null }) { Text("取消") } })
    }

    deleting?.let { (kind, value) ->
        AdminDeleteDialog(value, onDismiss = { deleting = null }) {
            deleting = null
            scope.launch {
                busy = true
                try {
                    val name = selected!!.optString("name")
                    if (kind == "skill") {
                        api.request("DELETE", "/api/skills/${adminEncode(name)}")
                        selected = null
                        reloadSkills()
                    } else {
                        api.request("DELETE", "/api/skills/${adminEncode(name)}/files/${adminEncodePath(value)}")
                        loadFiles(selected!!)
                    }
                    notice = "已删除"
                } catch (t: Exception) { error = t.message ?: "删除失败" }
                finally { busy = false }
            }
        }
    }
}

@Composable
private fun SkillEditorDialog(original: JSONObject?, busy: Boolean, onDismiss: () -> Unit, onSave: (JSONObject) -> Unit) {
    var name by remember(original) { mutableStateOf(original?.optString("name").orEmpty()) }
    var description by remember(original) { mutableStateOf(original?.optString("description").orEmpty()) }
    var license by remember(original) { mutableStateOf(original?.optString("license").orEmpty()) }
    var compatibility by remember(original) { mutableStateOf(original?.optString("compatibility").orEmpty()) }
    var mcps by remember(original) { mutableStateOf(original?.optJSONArray("mcps").adminStrings().joinToString(", ")) }
    var instructions by remember(original) { mutableStateOf("") }
    var feedback by remember(original) { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (original == null) "新增 Skill" else "编辑 Skill 信息") },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (original == null) AdminTextField("名称（小写英文/数字/连字符）", name, { name = it })
                AdminTextField("用途说明", description, { description = it })
                AdminTextField("许可证", license, { license = it })
                AdminTextField("兼容要求", compatibility, { compatibility = it })
                AdminTextField("关联 MCP 名称（逗号分隔）", mcps, { mcps = it })
                if (original == null) AdminTextField("初始指令（Markdown）", instructions,
                    { instructions = it }, multiline = true)
                feedback?.let { Text(it, color = AdminDanger, fontSize = 12.sp) }
            }
        }, confirmButton = { TextButton(onClick = {
            if (description.isBlank() || (original == null && name.isBlank())) {
                feedback = "请填写名称和用途说明"
                return@TextButton
            }
            val body = JSONObject().put("description", description.trim()).put("license", license.trim())
                .put("compatibility", compatibility.trim())
                .put("mcps", JSONArray(mcps.split(',').map { it.trim() }.filter { it.isNotEmpty() }))
            if (original == null) body.put("name", name.trim()).put("instructions", instructions)
            onSave(body)
        }, enabled = !busy) { Text("保存") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun ApprovalAdminScreen(api: ArtexApi, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var pending by remember(api) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var history by remember(api) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var historyPage by remember(api) { mutableIntStateOf(1) }
    var historyTotal by remember(api) { mutableIntStateOf(0) }
    var loading by remember(api) { mutableStateOf(true) }
    var busy by remember(api) { mutableStateOf(false) }
    var error by remember(api) { mutableStateOf<String?>(null) }
    var decision by remember(api) { mutableStateOf<Pair<JSONObject, String>?>(null) }
    var detail by remember(api) { mutableStateOf<JSONObject?>(null) }

    suspend fun reload() {
        loading = true
        try {
            pending = api.request("GET", "/api/intercept/pending").optJSONArray("pending").adminObjects()
            val historyResult = api.request("GET", "/api/intercept/history?page=$historyPage&size=20")
            history = historyResult.optJSONArray("items").adminObjects()
            historyTotal = historyResult.optInt("total", history.size)
            error = null
        } catch (t: Exception) { error = t.message ?: "加载失败" }
        finally { loading = false }
    }
    LaunchedEffect(api) { reload() }

    Column(Modifier.fillMaxSize()) {
        AdminHeader("审批记录", onBack, onRefresh = { scope.launch { reload() } })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            item { AdminPageTitle("待审批 ${pending.size} 项") }
            if (loading && pending.isEmpty()) item { AdminLoading() }
            error?.let { item { AdminError(it) } }
            if (!loading && pending.isEmpty()) item { Text("暂无待审批操作", color = AdminMuted, fontSize = 13.sp) }
            items(pending, key = { "pending-${it.optInt("id")}" }) { row ->
                Surface(shape = RoundedCornerShape(10.dp), color = Color.White, border = BorderStroke(1.dp, AdminLine)) {
                    Column(Modifier.padding(14.dp)) {
                        Text("${row.optString("agent_name")} · ${row.optString("tool_name")}",
                            color = AdminInk, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(4.dp))
                        Text(row.optString("reason"), color = AdminMuted, fontSize = 12.sp,
                            maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Row {
                            TextButton(onClick = { detail = row }) { Text("查看请求") }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { decision = row to "denied" }, enabled = !busy) {
                                Text("拒绝", color = AdminDanger)
                            }
                            TextButton(onClick = { decision = row to "allowed" }, enabled = !busy) { Text("允许") }
                        }
                    }
                }
            }
            item {
                Spacer(Modifier.height(12.dp))
                Text("最近记录", color = AdminInk, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
            items(history, key = { "history-${it.optInt("id")}" }) { row ->
                AdminListRow("${row.optString("tool_name")} · ${row.optString("status")}",
                    "${row.optString("agent_name")} · ${row.optString("created_at")}") { detail = row }
            }
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("第 $historyPage 页 · 共 $historyTotal 条", Modifier.weight(1f),
                        color = AdminMuted, fontSize = 12.sp)
                    TextButton(onClick = { historyPage--; scope.launch { reload() } },
                        enabled = historyPage > 1 && !loading) { Text("上一页") }
                    TextButton(onClick = { historyPage++; scope.launch { reload() } },
                        enabled = historyPage * 20 < historyTotal && !loading) { Text("下一页") }
                }
            }
        }
    }

    decision?.let { (row, result) ->
        AlertDialog(onDismissRequest = { decision = null }, title = { Text(if (result == "allowed") "允许执行？" else "拒绝执行？") },
            text = { Text("${row.optString("agent_name")} 请求调用 ${row.optString("tool_name")}。请确认这项审批。") },
            confirmButton = { TextButton(onClick = {
                decision = null
                scope.launch {
                    busy = true
                    try {
                        api.request("POST", "/api/intercept/pending/${row.optInt("id")}/decide",
                            JSONObject().put("decision", result))
                        reload()
                    } catch (t: Exception) { error = t.message ?: "审批失败" }
                    finally { busy = false }
                }
            }) { Text("确认") } },
            dismissButton = { TextButton(onClick = { decision = null }) { Text("取消") } })
    }
    detail?.let { row ->
        AlertDialog(onDismissRequest = { detail = null }, title = { Text("请求 #${row.optInt("id")}") },
            text = {
                Column(Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState())) {
                    Text("${row.optString("agent_name")} · ${row.optString("tool_name")}",
                        color = AdminInk, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.height(9.dp))
                    Text(row.optString("reason"), color = AdminMuted, fontSize = 12.sp)
                    Spacer(Modifier.height(11.dp))
                    Text(row.optJSONObject("tool_input")?.toString(2) ?: "{}", fontSize = 12.sp, color = AdminInk)
                }
            }, confirmButton = { TextButton(onClick = { detail = null }) { Text("关闭") } })
    }
}

@Composable
private fun AgentAdminScreen(api: ArtexApi, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var agents by remember(api) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var selected by remember(api) { mutableStateOf<JSONObject?>(null) }
    var detail by remember(api) { mutableStateOf<JSONObject?>(null) }
    var creating by remember(api) { mutableStateOf(false) }
    var deleting by remember(api) { mutableStateOf<JSONObject?>(null) }
    var promptEditing by remember(api) { mutableStateOf(false) }
    var visibility by remember(api) { mutableStateOf<JSONObject?>(null) }
    var mcpChoices by remember(api) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var skillChoices by remember(api) { mutableStateOf<List<JSONObject>>(emptyList()) }
    var triggerRows by remember(api) { mutableStateOf<List<JSONObject>?>(null) }
    var triggerEditing by remember(api) { mutableStateOf<JSONObject?>(null) }
    var triggerEditorOpen by remember(api) { mutableStateOf(false) }
    var triggerDeleting by remember(api) { mutableStateOf<JSONObject?>(null) }
    var error by remember(api) { mutableStateOf<String?>(null) }
    var notice by remember(api) { mutableStateOf<String?>(null) }
    var busy by remember(api) { mutableStateOf(false) }
    var loading by remember(api) { mutableStateOf(true) }

    suspend fun reload() {
        loading = true
        try {
            agents = api.request("GET", "/api/agents").optJSONArray("agents").adminObjects()
            selected = selected?.let { old -> agents.find { it.optString("key") == old.optString("key") } }
            error = null
        } catch (t: Exception) { error = t.message ?: "加载失败" }
        finally { loading = false }
    }
    suspend fun loadDetail(agent: JSONObject) {
        try {
            detail = api.request("GET", "/api/agents/${adminEncode(agent.optString("key"))}")
            error = null
        } catch (t: Exception) { error = t.message ?: "加载 Agent 失败" }
    }
    suspend fun loadVisibility(agent: JSONObject) {
        try {
            val key = adminEncode(agent.optString("key"))
            val current = api.request("GET", "/api/agents/$key/visibility")
            mcpChoices = api.request("GET", "/api/mcp").optJSONArray("servers").adminObjects()
            skillChoices = api.request("GET", "/api/skills").optJSONArray("skills").adminObjects()
            visibility = current
        } catch (t: Exception) { error = t.message ?: "加载可见资源失败" }
    }
    suspend fun loadTriggers(agent: JSONObject) {
        try {
            triggerRows = api.request("GET", "/api/agents/${adminEncode(agent.optString("key"))}/triggers")
                .optJSONArray("triggers").adminObjects()
        } catch (t: Exception) { error = t.message ?: "加载触发器失败" }
    }
    LaunchedEffect(api) { reload() }
    BackHandler(enabled = selected != null) { selected = null; detail = null }

    Column(Modifier.fillMaxSize()) {
        AdminHeader(if (selected == null) "Agent" else selected!!.optString("name"),
            onBack = { if (selected == null) onBack() else { selected = null; detail = null } },
            onRefresh = { scope.launch { if (selected == null) reload() else loadDetail(selected!!) } })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)) {
            if (selected == null) {
                item {
                    AdminPageTitle("Agent")
                    Button(onClick = { creating = true }, shape = RoundedCornerShape(9.dp)) { Text("新增 Agent") }
                }
                if (loading && agents.isEmpty()) item { AdminLoading() }
                error?.let { item { AdminError(it) } }
                notice?.let { item { Text(it, color = AdminAccent, fontSize = 12.sp) } }
                items(agents, key = { it.optString("key") }) { agent ->
                    AdminListRow(agent.optString("name"), "${agent.optString("key")} · ${agent.optString("description")}") {
                        selected = agent
                        scope.launch { loadDetail(agent) }
                    }
                }
            } else {
                val agent = selected!!
                item {
                    Text(agent.optString("description"), color = AdminMuted, fontSize = 13.sp)
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        OutlinedButton(onClick = { promptEditing = true }) { Text("编辑提示词") }
                        OutlinedButton(onClick = { scope.launch { loadVisibility(agent) } }) { Text("可见资源") }
                        if (!agent.optBoolean("builtin")) OutlinedButton(onClick = { deleting = agent }) { Text("删除") }
                    }
                    if (!agent.optBoolean("builtin")) TextButton(onClick = { scope.launch { loadTriggers(agent) } }) {
                        Text("管理触发器")
                    }
                }
                error?.let { item { AdminError(it) } }
                notice?.let { item { Text(it, color = AdminAccent, fontSize = 12.sp) } }
                if (detail == null) item { AdminLoading() }
                detail?.let { loaded ->
                    item {
                        AgentConfigForm(loaded, busy, onSave = { meta, config ->
                            scope.launch {
                                busy = true
                                try {
                                    val key = adminEncode(agent.optString("key"))
                                    if (!agent.optBoolean("builtin")) api.request("PATCH", "/api/agents/$key", meta)
                                    api.request("PUT", "/api/agents/$key/config", config)
                                    notice = "已保存 Agent 配置"
                                    reload()
                                    selected?.let { loadDetail(it) }
                                } catch (t: Exception) { error = t.message ?: "保存失败" }
                                finally { busy = false }
                            }
                        })
                    }
                }
            }
        }
    }

    if (creating) {
        var key by remember { mutableStateOf("") }
        var name by remember { mutableStateOf("") }
        var description by remember { mutableStateOf("") }
        AlertDialog(onDismissRequest = { creating = false }, title = { Text("新增 Agent") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AdminTextField("键", key, { key = it })
                    AdminTextField("名称", name, { name = it })
                    AdminTextField("说明", description, { description = it })
                }
            }, confirmButton = { TextButton(onClick = {
                scope.launch {
                    busy = true
                    try {
                        api.request("POST", "/api/agents", JSONObject()
                            .put("key", key.trim()).put("name", name.trim()).put("description", description.trim()))
                        creating = false
                        reload()
                    } catch (t: Exception) { error = t.message ?: "创建失败" }
                    finally { busy = false }
                }
            }, enabled = !busy && key.isNotBlank() && name.isNotBlank()) { Text("创建") } },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("取消") } })
    }

    if (promptEditing && detail != null) {
        val agent = selected!!
        var prompt by remember(detail) { mutableStateOf(detail!!.optString("prompt")) }
        var note by remember(detail) { mutableStateOf("") }
        AlertDialog(onDismissRequest = { promptEditing = false }, title = { Text("${agent.optString("name")} · 提示词") },
            text = {
                Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState())) {
                    AdminTextField("版本备注", note, { note = it })
                    Spacer(Modifier.height(10.dp))
                    AdminTextField("提示词", prompt, { prompt = it }, multiline = true)
                }
            }, confirmButton = { TextButton(onClick = {
                scope.launch {
                    busy = true
                    try {
                        api.request("PUT", "/api/agents/${adminEncode(agent.optString("key"))}/prompt",
                            JSONObject().put("template", prompt).put("note", note))
                        promptEditing = false
                        notice = "已保存提示词"
                        loadDetail(agent)
                    } catch (t: Exception) { error = t.message ?: "保存提示词失败" }
                    finally { busy = false }
                }
            }, enabled = !busy) { Text("保存") } },
            dismissButton = { TextButton(onClick = { promptEditing = false }) { Text("取消") } })
    }

    visibility?.let { current ->
        AgentVisibilityDialog(current, mcpChoices, skillChoices, busy,
            onDismiss = { visibility = null }, onSave = { body ->
                scope.launch {
                    busy = true
                    try {
                        api.request("PUT", "/api/agents/${adminEncode(selected!!.optString("key"))}/visibility", body)
                        visibility = null
                        notice = "已保存可见资源"
                        loadDetail(selected!!)
                    } catch (t: Exception) { error = t.message ?: "保存可见资源失败" }
                    finally { busy = false }
                }
            })
    }

    triggerRows?.let { rows ->
        if (triggerEditorOpen) TriggerEditorDialog(triggerEditing, busy,
            onDismiss = { triggerEditorOpen = false }, onSave = { body ->
                scope.launch {
                    busy = true
                    try {
                        val old = triggerEditing
                        if (old == null) api.request("POST", "/api/agents/${adminEncode(selected!!.optString("key"))}/triggers", body)
                        else api.request("PATCH", "/api/triggers/${old.optInt("id")}", body)
                        triggerEditorOpen = false
                        notice = "已保存触发器"
                        loadTriggers(selected!!)
                    } catch (t: Exception) { error = t.message ?: "保存触发器失败" }
                    finally { busy = false }
                }
            })
        else TriggerListDialog(rows, onDismiss = { triggerRows = null },
            onAdd = { triggerEditing = null; triggerEditorOpen = true },
            onEdit = { triggerEditing = it; triggerEditorOpen = true },
            onDelete = { triggerRows = null; triggerDeleting = it })
    }

    triggerDeleting?.let { row ->
        AdminDeleteDialog("触发器 #${row.optInt("id")}", onDismiss = { triggerDeleting = null }) {
            triggerDeleting = null
            scope.launch {
                busy = true
                try {
                    api.request("DELETE", "/api/triggers/${row.optInt("id")}")
                    notice = "已删除触发器"
                    selected?.let { loadTriggers(it) }
                } catch (t: Exception) { error = t.message ?: "删除触发器失败" }
                finally { busy = false }
            }
        }
    }

    deleting?.let { agent ->
        AdminDeleteDialog(agent.optString("name"), onDismiss = { deleting = null }) {
            deleting = null
            scope.launch {
                busy = true
                try {
                    api.request("DELETE", "/api/agents/${adminEncode(agent.optString("key"))}")
                    selected = null
                    detail = null
                    reload()
                } catch (t: Exception) { error = t.message ?: "删除失败" }
                finally { busy = false }
            }
        }
    }
}

@Composable
private fun AgentConfigForm(detail: JSONObject, busy: Boolean, onSave: (JSONObject, JSONObject) -> Unit) {
    val agent = detail.optJSONObject("agent") ?: JSONObject()
    var name by remember(detail) { mutableStateOf(agent.optString("name")) }
    var description by remember(detail) { mutableStateOf(agent.optString("description")) }
    var profileId by remember(detail) { mutableStateOf(agent.optInt("llm_profile_id").toString()) }
    var maxTurns by remember(detail) { mutableStateOf(agent.optInt("max_turns").toString()) }
    var runSeconds by remember(detail) { mutableStateOf(agent.optInt("run_seconds").toString()) }
    var webSearch by remember(detail) { mutableStateOf(agent.optBoolean("web_search")) }
    var interactiveShell by remember(detail) { mutableStateOf(agent.optBoolean("interactive_shell")) }
    var triggerRunMode by remember(detail) { mutableStateOf(agent.optString("trigger_run_mode", "serial")) }
    var triggerMergeMode by remember(detail) { mutableStateOf(agent.optString("trigger_merge_mode", "by_task")) }
    var triggerMaxParallel by remember(detail) { mutableStateOf(agent.optInt("trigger_max_parallel").toString()) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (agent.optBoolean("builtin")) {
            Text("${agent.optString("name")} · 内置 Agent", color = AdminMuted, fontSize = 13.sp)
        } else {
            AdminTextField("名称", name, { name = it })
            AdminTextField("说明", description, { description = it })
        }
        AdminTextField("LLM 配置 ID（0 = 跟随任务/默认）", profileId, { profileId = it }, KeyboardType.Number)
        AdminTextField("最大轮数（0 = 不限）", maxTurns, { maxTurns = it }, KeyboardType.Number)
        AdminTextField("运行秒数（0 = 不限）", runSeconds, { runSeconds = it }, KeyboardType.Number)
        AdminBoolRow("允许 Web 搜索", webSearch) { webSearch = it }
        AdminBoolRow("允许交互式 Shell", interactiveShell) { interactiveShell = it }
        if (!agent.optBoolean("builtin")) {
            AdminChoiceRow("触发运行方式", triggerRunMode, listOf("serial", "parallel")) { triggerRunMode = it }
            AdminChoiceRow("触发合并方式", triggerMergeMode, listOf("by_task", "all", "none")) { triggerMergeMode = it }
            AdminTextField("触发并发上限", triggerMaxParallel, { triggerMaxParallel = it }, KeyboardType.Number)
        }
        Button(onClick = {
            val meta = JSONObject().put("name", name.trim()).put("description", description.trim())
            val config = JSONObject()
                .put("llm_profile_id", profileId.toIntOrNull()?.takeIf { it > 0 } ?: JSONObject.NULL)
                .put("max_turns", maxTurns.toIntOrNull() ?: 0)
                .put("run_seconds", runSeconds.toIntOrNull() ?: 0)
                .put("web_search", webSearch)
                .put("interactive_shell", interactiveShell)
            if (!agent.optBoolean("builtin")) config.put("trigger_run_mode", triggerRunMode)
                .put("trigger_merge_mode", triggerMergeMode)
                .put("trigger_max_parallel", triggerMaxParallel.toIntOrNull() ?: 0)
            onSave(meta, config)
        }, enabled = !busy && name.isNotBlank(), shape = RoundedCornerShape(9.dp)) { Text("保存 Agent 配置") }
    }
}

@Composable
private fun AgentVisibilityDialog(original: JSONObject, mcps: List<JSONObject>, skills: List<JSONObject>,
                                  busy: Boolean, onDismiss: () -> Unit, onSave: (JSONObject) -> Unit) {
    var selectedMcp by remember(original) {
        mutableStateOf(original.optJSONArray("mcp").adminInts().toSet())
    }
    var selectedSkills by remember(original) {
        mutableStateOf(original.optJSONArray("skill").adminStrings().toSet())
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Agent 可见资源") },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                Text("MCP", color = AdminInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (mcps.isEmpty()) Text("暂无 MCP 服务器", color = AdminMuted, fontSize = 12.sp)
                mcps.forEach { mcp ->
                    val id = mcp.optInt("id")
                    AdminBoolRow(mcp.optString("name"), id in selectedMcp) { checked ->
                        selectedMcp = if (checked) selectedMcp + id else selectedMcp - id
                    }
                }
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = AdminLine)
                Spacer(Modifier.height(10.dp))
                Text("Skill", color = AdminInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                if (skills.isEmpty()) Text("暂无 Skill", color = AdminMuted, fontSize = 12.sp)
                skills.forEach { skill ->
                    val name = skill.optString("name")
                    AdminBoolRow(name, name in selectedSkills) { checked ->
                        selectedSkills = if (checked) selectedSkills + name else selectedSkills - name
                    }
                }
            }
        }, confirmButton = { TextButton(onClick = {
            onSave(JSONObject()
                .put("mcp", JSONArray(selectedMcp.sorted()))
                .put("skill", JSONArray(selectedSkills.sorted())))
        }, enabled = !busy) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun TriggerListDialog(rows: List<JSONObject>, onDismiss: () -> Unit, onAdd: () -> Unit,
                              onEdit: (JSONObject) -> Unit, onDelete: (JSONObject) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Agent 触发器") },
        text = {
            Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState())) {
                if (rows.isEmpty()) Text("暂无触发器", color = AdminMuted, fontSize = 13.sp)
                rows.forEach { row ->
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Text("#${row.optInt("id")} · ${if (row.optBoolean("enabled")) "启用" else "停用"}",
                            color = AdminInk, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        val modes = buildList {
                            if (row.optInt("interval_sec") > 0) add("每 ${row.optInt("interval_sec")} 秒")
                            if (row.optBoolean("on_finding")) add("发现")
                            if (row.optBoolean("on_goal_met")) add("目标达成")
                            if (row.optBoolean("on_task_timeout")) add("任务超时")
                            if (row.optBoolean("on_tool_call")) add("工具调用")
                            if (row.optBoolean("on_task_create")) add("任务创建")
                        }
                        Text(modes.joinToString(" · "), color = AdminMuted, fontSize = 11.sp)
                        Row {
                            TextButton(onClick = { onEdit(row) }) { Text("编辑") }
                            TextButton(onClick = { onDelete(row) }) { Text("删除", color = AdminDanger) }
                        }
                    }
                    HorizontalDivider(color = AdminLine)
                }
            }
        }, confirmButton = { TextButton(onClick = onAdd) { Text("新增触发器") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

@Composable
private fun TriggerEditorDialog(original: JSONObject?, busy: Boolean, onDismiss: () -> Unit,
                                onSave: (JSONObject) -> Unit) {
    var enabled by remember(original) { mutableStateOf(original?.optBoolean("enabled", true) ?: true) }
    var intervalSec by remember(original) { mutableStateOf(original?.optInt("interval_sec", 0)?.toString() ?: "0") }
    var onFinding by remember(original) { mutableStateOf(original?.optBoolean("on_finding") ?: false) }
    var onGoalMet by remember(original) { mutableStateOf(original?.optBoolean("on_goal_met") ?: false) }
    var onTaskTimeout by remember(original) { mutableStateOf(original?.optBoolean("on_task_timeout") ?: false) }
    var onToolCall by remember(original) { mutableStateOf(original?.optBoolean("on_tool_call") ?: false) }
    var onTaskCreate by remember(original) { mutableStateOf(original?.optBoolean("on_task_create") ?: false) }
    var intervalMessage by remember(original) { mutableStateOf(original?.optString("interval_message").orEmpty()) }
    var findingMessage by remember(original) { mutableStateOf(original?.optString("finding_message").orEmpty()) }
    var goalMessage by remember(original) { mutableStateOf(original?.optString("goal_message").orEmpty()) }
    var timeoutMessage by remember(original) { mutableStateOf(original?.optString("task_timeout_message").orEmpty()) }
    var toolMessage by remember(original) { mutableStateOf(original?.optString("tool_call_message").orEmpty()) }
    var createMessage by remember(original) { mutableStateOf(original?.optString("task_create_message").orEmpty()) }
    var toolNames by remember(original) {
        mutableStateOf(original?.optJSONArray("tool_names").adminStrings().joinToString(", "))
    }
    var feedback by remember(original) { mutableStateOf<String?>(null) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(if (original == null) "新增触发器" else "编辑触发器") },
        text = {
            Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)) {
                AdminBoolRow("启用", enabled) { enabled = it }
                AdminTextField("定时间隔（秒，0 = 关闭）", intervalSec, { intervalSec = it }, KeyboardType.Number)
                if ((intervalSec.toIntOrNull() ?: 0) > 0) AdminTextField("定时消息", intervalMessage,
                    { intervalMessage = it }, multiline = true)
                AdminBoolRow("发现漏洞时", onFinding) { onFinding = it }
                if (onFinding) AdminTextField("发现消息", findingMessage, { findingMessage = it }, multiline = true)
                AdminBoolRow("目标达成时", onGoalMet) { onGoalMet = it }
                if (onGoalMet) AdminTextField("目标消息", goalMessage, { goalMessage = it }, multiline = true)
                AdminBoolRow("任务超时时", onTaskTimeout) { onTaskTimeout = it }
                if (onTaskTimeout) AdminTextField("超时消息", timeoutMessage, { timeoutMessage = it }, multiline = true)
                AdminBoolRow("工具调用后", onToolCall) { onToolCall = it }
                if (onToolCall) {
                    AdminTextField("工具键（逗号分隔）", toolNames, { toolNames = it })
                    AdminTextField("工具消息", toolMessage, { toolMessage = it }, multiline = true)
                }
                AdminBoolRow("任务创建时", onTaskCreate) { onTaskCreate = it }
                if (onTaskCreate) AdminTextField("创建消息", createMessage, { createMessage = it }, multiline = true)
                feedback?.let { Text(it, color = AdminDanger, fontSize = 12.sp) }
            }
        }, confirmButton = { TextButton(onClick = {
            val interval = intervalSec.toIntOrNull()?.coerceAtLeast(0) ?: 0
            val names = toolNames.split(',').map { it.trim() }.filter { it.isNotBlank() }
            if (interval == 0 && !onFinding && !onGoalMet && !onTaskTimeout && !onToolCall && !onTaskCreate) {
                feedback = "至少选择一种触发条件"
                return@TextButton
            }
            if (onToolCall && names.isEmpty()) {
                feedback = "工具调用触发至少选择一个工具"
                return@TextButton
            }
            onSave(JSONObject().put("enabled", enabled).put("interval_sec", interval)
                .put("on_finding", onFinding).put("on_goal_met", onGoalMet)
                .put("on_task_timeout", onTaskTimeout).put("on_tool_call", onToolCall)
                .put("on_task_create", onTaskCreate).put("interval_message", intervalMessage)
                .put("finding_message", findingMessage).put("goal_message", goalMessage)
                .put("task_timeout_message", timeoutMessage).put("tool_call_message", toolMessage)
                .put("task_create_message", createMessage).put("tool_names", JSONArray(names)))
        }, enabled = !busy) { Text("保存") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun AdminHeader(title: String, onBack: () -> Unit, onRefresh: (() -> Unit)? = null) {
    Column {
        Row(Modifier.fillMaxWidth().height(55.dp).padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回", tint = AdminInk) }
            Text(title, Modifier.weight(1f), color = AdminInk, fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (onRefresh != null) IconButton(onClick = onRefresh) {
                Icon(Icons.Outlined.Refresh, "刷新", tint = AdminMuted)
            }
        }
        HorizontalDivider(color = AdminLine)
    }
}

@Composable
private fun AdminPageTitle(title: String) {
    Text(title, color = AdminInk, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(5.dp))
}

@Composable
private fun AdminListRow(title: String, subtitle: String, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Text(title, Modifier.padding(top = 13.dp), color = AdminInk, fontSize = 15.sp,
            fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotBlank()) Text(subtitle, Modifier.padding(top = 4.dp, bottom = 13.dp),
            color = AdminMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        else Spacer(Modifier.height(13.dp))
        HorizontalDivider(color = AdminLine)
    }
}

@Composable
private fun AdminLoading() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(23.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun AdminError(message: String) {
    Text(message, color = AdminDanger, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
}

@Composable
private fun AdminEmpty() {
    Text("暂无内容", color = AdminMuted, fontSize = 13.sp, modifier = Modifier.padding(vertical = 20.dp))
}

@Composable
private fun AdminDeleteDialog(name: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("确认删除？") },
        text = { Text("将删除「$name」。") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("删除", color = AdminDanger) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

@Composable
private fun AdminTextField(label: String, value: String, onChange: (String) -> Unit,
                           keyboardType: KeyboardType = KeyboardType.Text, multiline: Boolean = false) {
    Column {
        Text(label, color = AdminInk, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(5.dp))
        OutlinedTextField(value = value, onValueChange = onChange, modifier = Modifier.fillMaxWidth(),
            minLines = if (multiline) 5 else 1, maxLines = if (multiline) 15 else 2,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            shape = RoundedCornerShape(8.dp), textStyle = LocalTextStyle.current.copy(fontSize = 13.sp))
    }
}

@Composable
private fun AdminBoolRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = AdminInk, fontSize = 13.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun AdminChoiceRow(label: String, selected: String, options: List<String>, onChange: (String) -> Unit) {
    Column {
        Text(label, color = AdminInk, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { option ->
                FilterChip(selected = selected == option, onClick = { onChange(option) },
                    label = { Text(option, fontSize = 11.sp) }, shape = RoundedCornerShape(7.dp))
            }
        }
    }
}

private fun JSONArray?.adminObjects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

private fun JSONArray?.adminStrings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { optString(it) }

private fun JSONArray?.adminInts(): List<Int> =
    if (this == null) emptyList() else (0 until length()).map { optInt(it) }

private fun adminEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

private fun adminEncodePath(value: String): String = value.split('/').joinToString("/") { adminEncode(it) }
