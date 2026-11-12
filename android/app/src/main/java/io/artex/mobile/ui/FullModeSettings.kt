package io.artex.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
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
import org.json.JSONObject

private val FullInk = Color(0xFF1C2E2A)
private val FullMuted = Color(0xFF73807A)
private val FullAccent = Color(0xFF3F6658)
private val FullLine = Color(0xFFE3E6DE)

private data class MobileLlmProfile(
    val id: Int,
    val name: String,
    val format: String,
    val model: String,
    val baseUrl: String,
    val proxy: String,
    val apiKeyHint: String,
    val isDefault: Boolean,
    val ratePerSecond: String,
    val ratePerMinute: String,
    val contextWindowK: String,
    val thinkingType: String,
    val reasoningEffort: String,
    val priority: String,
    val poolExclude: Boolean,
    val streaming: Boolean,
    val maxTokens: String,
    val maxTokensField: String,
    val sessionHeaderKey: String,
    val retryConnectAttempts: String,
    val retryConnectIntervalMs: String,
    val retryEmptyAttempts: String,
    val retryEmptyIntervalMs: String,
    val retryStreamAttempts: String,
    val retryStreamIntervalMs: String,
) {
    companion object {
        fun blank() = MobileLlmProfile(0, "", "openai", "", "", "", "", false,
            "0", "0", "0", "", "", "0", false, true, "0", "", "",
            "0", "0", "0", "0", "0", "0")

        fun fromJson(obj: JSONObject): MobileLlmProfile {
            val retry = obj.optJSONObject("retry")
            val connect = retry?.optJSONObject("connect")
            val empty = retry?.optJSONObject("empty")
            val stream = retry?.optJSONObject("stream")
            return MobileLlmProfile(
            id = obj.optInt("id"),
            name = obj.optString("name"),
            format = obj.optString("format", "openai"),
            model = obj.optString("model"),
            baseUrl = obj.optString("base_url"),
            proxy = obj.optString("proxy"),
            apiKeyHint = obj.optString("api_key_hint"),
            isDefault = obj.optBoolean("is_default"),
            ratePerSecond = obj.optDouble("rate_per_second", 0.0).toString(),
            ratePerMinute = obj.optDouble("rate_per_minute", 0.0).toString(),
            contextWindowK = obj.optInt("context_window_k").toString(),
            thinkingType = obj.optString("thinking_type"),
            reasoningEffort = obj.optString("reasoning_effort"),
            priority = obj.optInt("priority").toString(),
            poolExclude = obj.optBoolean("pool_exclude"),
            streaming = obj.optBoolean("streaming", true),
            maxTokens = obj.optInt("max_tokens").toString(),
            maxTokensField = obj.optString("max_tokens_field"),
            sessionHeaderKey = obj.optString("session_header_key"),
            retryConnectAttempts = (connect?.optInt("attempts") ?: 0).toString(),
            retryConnectIntervalMs = (connect?.optInt("interval_ms") ?: 0).toString(),
            retryEmptyAttempts = (empty?.optInt("attempts") ?: 0).toString(),
            retryEmptyIntervalMs = (empty?.optInt("interval_ms") ?: 0).toString(),
            retryStreamAttempts = (stream?.optInt("attempts") ?: 0).toString(),
            retryStreamIntervalMs = (stream?.optInt("interval_ms") ?: 0).toString(),
            )
        }
    }
}

/** Native editor for the existing /api/llm/profiles endpoints. */
@Composable
fun LlmProfilesScreen(api: ArtexApi, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var profiles by remember(api) { mutableStateOf<List<MobileLlmProfile>>(emptyList()) }
    var loading by remember(api) { mutableStateOf(true) }
    var busy by remember(api) { mutableStateOf(false) }
    var error by remember(api) { mutableStateOf<String?>(null) }
    var editing by remember(api) { mutableStateOf<MobileLlmProfile?>(null) }
    var showEditor by remember(api) { mutableStateOf(false) }
    var deleting by remember(api) { mutableStateOf<MobileLlmProfile?>(null) }

    suspend fun reload() {
        loading = true
        try {
            val response = api.request("GET", "/api/llm/profiles")
            val array = response.optJSONArray("profiles")
            profiles = if (array == null) emptyList() else (0 until array.length()).mapNotNull {
                array.optJSONObject(it)?.let(MobileLlmProfile::fromJson)
            }
            error = null
        } catch (t: Exception) {
            error = t.message ?: "加载失败"
        } finally {
            loading = false
        }
    }
    LaunchedEffect(api) { reload() }

    if (showEditor) {
        LlmProfileEditor(
            api = api,
            original = editing,
            onBack = { showEditor = false },
            onSaved = {
                showEditor = false
                scope.launch { reload() }
            },
        )
        return
    }

    Column(Modifier.fillMaxSize()) {
        FullModeHeader("LLM 配置", onBack, onRefresh = { scope.launch { reload() } })
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("模型配置", color = FullInk, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(5.dp))
                Text("新增或编辑模型，然后设为激活。", color = FullMuted, fontSize = 12.sp)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { editing = null; showEditor = true }, shape = RoundedCornerShape(9.dp)) {
                    Text("新增模型配置")
                }
            }
            if (loading && profiles.isEmpty()) item { FullLoading() }
            error?.let { message -> item { FullError(message) } }
            if (!loading && profiles.isEmpty() && error == null) item {
                Text("尚未创建模型配置", color = FullMuted, modifier = Modifier.padding(vertical = 24.dp))
            }
            items(profiles, key = { it.id }) { profile ->
                Surface(shape = RoundedCornerShape(11.dp), color = Color.White,
                    border = BorderStroke(1.dp, FullLine)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(profile.name, Modifier.weight(1f), color = FullInk, fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (profile.isDefault) Text("激活中", color = FullAccent, fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold)
                        }
                        Spacer(Modifier.height(5.dp))
                        Text("${profile.format} · ${profile.model}", color = FullMuted, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (profile.baseUrl.isNotBlank()) {
                            Spacer(Modifier.height(3.dp))
                            Text(profile.baseUrl, color = FullMuted, fontSize = 11.sp,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            TextButton(onClick = { editing = profile; showEditor = true }) { Text("编辑") }
                            if (!profile.isDefault) TextButton(onClick = {
                                scope.launch {
                                    busy = true
                                    try {
                                        api.request("POST", "/api/llm/profiles/active", JSONObject().put("id", profile.id))
                                        reload()
                                    } catch (t: Exception) { error = t.message ?: "激活失败" }
                                    finally { busy = false }
                                }
                            }, enabled = !busy) { Text("设为激活") }
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { deleting = profile }, enabled = !busy) { Text("删除", color = Color(0xFFB56D55)) }
                        }
                    }
                }
            }
        }
    }

    deleting?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("删除模型配置？") },
            text = { Text("将删除「${profile.name}」。正在使用它的任务可能受到影响。") },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    scope.launch {
                        busy = true
                        try {
                            api.request("DELETE", "/api/llm/profiles/${profile.id}")
                            reload()
                        } catch (t: Exception) { error = t.message ?: "删除失败" }
                        finally { busy = false }
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun LlmProfileEditor(api: ArtexApi, original: MobileLlmProfile?, onBack: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var form by remember(original?.id) { mutableStateOf(original ?: MobileLlmProfile.blank()) }
    var apiKey by remember(original?.id) { mutableStateOf("") }
    var advanced by remember(original?.id) { mutableStateOf(false) }
    var busy by remember(original?.id) { mutableStateOf(false) }
    var testing by remember(original?.id) { mutableStateOf(false) }
    var feedback by remember(original?.id) { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        FullModeHeader(if (original == null) "新增模型" else "编辑模型", onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(13.dp)) {
            item { FullTextField("名称", form.name, { form = form.copy(name = it) }) }
            item {
                Text("接口格式", color = FullInk, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(7.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("openai", "anthropic", "openai-responses").forEach { option ->
                        FilterChip(selected = form.format == option, onClick = { form = form.copy(format = option) },
                            label = { Text(option, fontSize = 11.sp) }, shape = RoundedCornerShape(8.dp))
                    }
                }
            }
            item { FullTextField("模型", form.model, { form = form.copy(model = it) }) }
            item { FullTextField("Base URL", form.baseUrl, { form = form.copy(baseUrl = it) }, KeyboardType.Uri) }
            item {
                FullTextField("API Key", apiKey, { apiKey = it }, KeyboardType.Password, secret = true,
                    hint = if (original != null) "留空保留已存密钥 ${original.apiKeyHint}" else "输入 API Key")
            }
            item { FullTextField("模型代理", form.proxy, { form = form.copy(proxy = it) }, KeyboardType.Uri) }
            item { FullSwitch("流式输出", form.streaming) { form = form.copy(streaming = it) } }
            item {
                HorizontalDivider(color = FullLine)
                Text("高级参数", Modifier.fillMaxWidth().clickable { advanced = !advanced }.padding(vertical = 12.dp),
                    color = FullAccent, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            if (advanced) {
                item { FullTextField("每秒请求数（0 = 不限）", form.ratePerSecond,
                    { form = form.copy(ratePerSecond = it) }, KeyboardType.Decimal) }
                item { FullTextField("每分钟请求数（0 = 不限）", form.ratePerMinute,
                    { form = form.copy(ratePerMinute = it) }, KeyboardType.Number) }
                item { FullTextField("上下文窗口（千 token）", form.contextWindowK,
                    { form = form.copy(contextWindowK = it) }, KeyboardType.Number) }
                item { FullTextField("优先级", form.priority,
                    { form = form.copy(priority = it) }, KeyboardType.Number) }
                item { FullTextField("输出上限（token，0 = 默认）", form.maxTokens,
                    { form = form.copy(maxTokens = it) }, KeyboardType.Number) }
                item { FullTextField("思考类型（空 / disabled / enabled）", form.thinkingType,
                    { form = form.copy(thinkingType = it) }) }
                item { FullTextField("思考强度（空 / low / medium / high / xhigh / max）", form.reasoningEffort,
                    { form = form.copy(reasoningEffort = it) }) }
                item { FullTextField("输出上限字段名", form.maxTokensField,
                    { form = form.copy(maxTokensField = it) }) }
                item { FullTextField("自定义会话头", form.sessionHeaderKey,
                    { form = form.copy(sessionHeaderKey = it) }) }
                item { FullSwitch("不加入故障转移池", form.poolExclude) { form = form.copy(poolExclude = it) } }
                item { FullGroupTitle("本配置重试覆盖（0 = 跟随全局，-1 = 关闭）") }
                item { FullTextField("建连重试次数", form.retryConnectAttempts,
                    { form = form.copy(retryConnectAttempts = it) }) }
                item { FullTextField("建连重试间隔（毫秒）", form.retryConnectIntervalMs,
                    { form = form.copy(retryConnectIntervalMs = it) }, KeyboardType.Number) }
                item { FullTextField("空响应重试次数", form.retryEmptyAttempts,
                    { form = form.copy(retryEmptyAttempts = it) }) }
                item { FullTextField("空响应重试间隔（毫秒）", form.retryEmptyIntervalMs,
                    { form = form.copy(retryEmptyIntervalMs = it) }, KeyboardType.Number) }
                item { FullTextField("断流重试次数", form.retryStreamAttempts,
                    { form = form.copy(retryStreamAttempts = it) }) }
                item { FullTextField("断流重试间隔（毫秒）", form.retryStreamIntervalMs,
                    { form = form.copy(retryStreamIntervalMs = it) }, KeyboardType.Number) }
            }
            feedback?.let { message -> item { Text(message, color = FullAccent, fontSize = 12.sp) } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            testing = true
                            try {
                                val response = api.request("POST", "/api/llm/test", JSONObject()
                                    .put("provider", form.format).put("model", form.model.trim())
                                    .put("base_url", form.baseUrl.trim()).put("proxy", form.proxy.trim())
                                    .put("api_key", apiKey).put("thinking_type", form.thinkingType.trim())
                                    .put("reasoning_effort", form.reasoningEffort.trim())
                                    .put("profile_id", if (form.id == 0) JSONObject.NULL else form.id)
                                    .put("streaming", form.streaming).put("session_header_key", form.sessionHeaderKey.trim()),
                                    readTimeoutMs = 90_000)
                                feedback = if (response.optBoolean("ok"))
                                    "连接成功 · ${response.optLong("latency_ms")} ms · ${response.optString("reply").take(160)}"
                                else "连接失败：${response.optString("error")}"
                            } catch (t: Exception) { feedback = t.message ?: "测试失败" }
                            finally { testing = false }
                        }
                    }, enabled = !testing && !busy) { Text(if (testing) "测试中…" else "测试连接") }
                    Button(onClick = {
                        if (form.name.isBlank() || form.model.isBlank()) {
                            feedback = "请填写名称和模型"
                        } else {
                            scope.launch {
                                busy = true
                                try {
                                    api.request("POST", "/api/llm/profiles", JSONObject()
                                        .apply { if (form.id > 0) put("id", form.id) }
                                        .put("name", form.name.trim()).put("format", form.format)
                                        .put("model", form.model.trim()).put("base_url", form.baseUrl.trim())
                                        .put("proxy", form.proxy.trim()).put("api_key", apiKey)
                                        .put("rate_per_second", form.ratePerSecond.toDoubleOrNull() ?: 0.0)
                                        .put("rate_per_minute", form.ratePerMinute.toDoubleOrNull() ?: 0.0)
                                        .put("context_window_k", form.contextWindowK.toIntOrNull() ?: 0)
                                        .put("thinking_type", form.thinkingType.trim())
                                        .put("reasoning_effort", form.reasoningEffort.trim())
                                        .put("priority", form.priority.toIntOrNull() ?: 0)
                                        .put("pool_exclude", form.poolExclude).put("streaming", form.streaming)
                                        .put("max_tokens", form.maxTokens.toIntOrNull() ?: 0)
                                        .put("max_tokens_field", if (form.format == "openai") form.maxTokensField.trim() else "")
                                        .put("session_header_key", form.sessionHeaderKey.trim())
                                        .put("retry", JSONObject()
                                            .put("connect", JSONObject()
                                                .put("attempts", form.retryConnectAttempts.toIntOrNull() ?: 0)
                                                .put("interval_ms", form.retryConnectIntervalMs.toIntOrNull() ?: 0))
                                            .put("empty", JSONObject()
                                                .put("attempts", form.retryEmptyAttempts.toIntOrNull() ?: 0)
                                                .put("interval_ms", form.retryEmptyIntervalMs.toIntOrNull() ?: 0))
                                            .put("stream", JSONObject()
                                                .put("attempts", form.retryStreamAttempts.toIntOrNull() ?: 0)
                                                .put("interval_ms", form.retryStreamIntervalMs.toIntOrNull() ?: 0))))
                                    onSaved()
                                } catch (t: Exception) { feedback = t.message ?: "保存失败" }
                                finally { busy = false }
                            }
                        }
                    }, enabled = !busy && !testing) { Text(if (busy) "保存中…" else "保存") }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

private data class SettingsDraft(
    val trafficCapture: Boolean,
    val agentTrafficBinding: Boolean,
    val llmRecord: Boolean,
    val webSearchEnabled: Boolean,
    val webSearchBackend: String,
    val braveKeySet: Boolean,
    val tavilyKeySet: Boolean,
    val webSearchProxy: String,
    val globalProxy: String,
    val pythonInterpreter: String,
    val workers: String,
    val taskConcurrencyEnabled: Boolean,
    val taskConcurrencyLimit: String,
    val llmPoolEnabled: Boolean,
    val llmPoolBindFallback: Boolean,
    val constraintsInjectPlanner: Boolean,
    val constraintsInjectWorker: Boolean,
    val noaCompaction: Boolean,
    val notifyEnabled: Boolean,
    val notifyPublicBaseUrl: String,
    val notifyDigestIntervalMin: String,
) {
    companion object {
        fun fromJson(obj: JSONObject) = SettingsDraft(
            trafficCapture = obj.optBoolean("traffic_capture"),
            agentTrafficBinding = obj.optBoolean("agent_traffic_binding"),
            llmRecord = obj.optBoolean("llm_record"),
            webSearchEnabled = obj.optBoolean("web_search_enabled"),
            webSearchBackend = obj.optString("web_search_backend", "ddgs"),
            braveKeySet = obj.optBoolean("brave_key_set"),
            tavilyKeySet = obj.optBoolean("tavily_key_set"),
            webSearchProxy = obj.optString("web_search_proxy"),
            globalProxy = obj.optString("global_proxy"),
            pythonInterpreter = obj.optString("python_interpreter"),
            workers = obj.optInt("workers", 3).toString(),
            taskConcurrencyEnabled = obj.optBoolean("task_concurrency_enabled"),
            taskConcurrencyLimit = obj.optInt("task_concurrency_limit", 5).toString(),
            llmPoolEnabled = obj.optBoolean("llm_pool_enabled"),
            llmPoolBindFallback = obj.optBoolean("llm_pool_bind_fallback"),
            constraintsInjectPlanner = obj.optBoolean("constraints_inject_planner", true),
            constraintsInjectWorker = obj.optBoolean("constraints_inject_worker", true),
            noaCompaction = obj.optBoolean("noa_compaction"),
            notifyEnabled = obj.optBoolean("notify_enabled", true),
            notifyPublicBaseUrl = obj.optString("notify_public_base_url"),
            notifyDigestIntervalMin = obj.optInt("notify_digest_interval_min", 30).toString(),
        )
    }

    fun toJson() = JSONObject()
        .put("traffic_capture", trafficCapture)
        .put("agent_traffic_binding", agentTrafficBinding)
        .put("llm_record", llmRecord)
        .put("web_search_enabled", webSearchEnabled)
        .put("web_search_backend", webSearchBackend)
        .put("web_search_proxy", webSearchProxy.trim())
        .put("global_proxy", globalProxy.trim())
        .put("python_interpreter", pythonInterpreter.trim())
        .put("workers", workers.toIntOrNull()?.coerceIn(1, 128) ?: 3)
        .put("task_concurrency_enabled", taskConcurrencyEnabled)
        .put("task_concurrency_limit", taskConcurrencyLimit.toIntOrNull()?.coerceIn(1, 128) ?: 5)
        .put("llm_pool_enabled", llmPoolEnabled)
        .put("llm_pool_bind_fallback", llmPoolBindFallback)
        .put("constraints_inject_planner", constraintsInjectPlanner)
        .put("constraints_inject_worker", constraintsInjectWorker)
        .put("noa_compaction", noaCompaction)
        .put("notify_enabled", notifyEnabled)
        .put("notify_public_base_url", notifyPublicBaseUrl.trim())
        .put("notify_digest_interval_min", notifyDigestIntervalMin.toIntOrNull()?.coerceIn(1, 1440) ?: 30)
}

/** Runtime settings editor; credential fields are left unchanged on save. */
@Composable
fun SystemSettingsScreen(api: ArtexApi, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var draft by remember(api) { mutableStateOf<SettingsDraft?>(null) }
    var loading by remember(api) { mutableStateOf(true) }
    var saving by remember(api) { mutableStateOf(false) }
    var error by remember(api) { mutableStateOf<String?>(null) }
    var saved by remember(api) { mutableStateOf(false) }
    var braveKey by remember(api) { mutableStateOf("") }
    var tavilyKey by remember(api) { mutableStateOf("") }
    var clearBraveKey by remember(api) { mutableStateOf(false) }
    var clearTavilyKey by remember(api) { mutableStateOf(false) }

    suspend fun reload() {
        loading = true
        try {
            draft = SettingsDraft.fromJson(api.request("GET", "/api/settings"))
            error = null
        } catch (t: Exception) { error = t.message ?: "加载失败" }
        finally { loading = false }
    }
    LaunchedEffect(api) { reload() }

    Column(Modifier.fillMaxSize()) {
        FullModeHeader("系统配置", onBack, onRefresh = { scope.launch { reload() } })
        if (loading && draft == null) FullLoading()
        error?.let { FullError(it) }
        val current = draft
        if (current != null) {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Text("系统配置", color = FullInk, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(5.dp))
                    Text("修改后点击底部保存。密钥留空时不改动已保存的值。", color = FullMuted, fontSize = 12.sp)
                }
                item { FullGroupTitle("运行") }
                item { FullTextField("并发工作 Agent 数", current.workers, { draft = current.copy(workers = it) }, KeyboardType.Number) }
                item { FullSwitch("任务并发限制", current.taskConcurrencyEnabled) { draft = current.copy(taskConcurrencyEnabled = it) } }
                if (current.taskConcurrencyEnabled) item { FullTextField("同时运行的任务上限", current.taskConcurrencyLimit,
                    { draft = current.copy(taskConcurrencyLimit = it) }, KeyboardType.Number) }
                item { FullSwitch("捕获流量", current.trafficCapture) { draft = current.copy(trafficCapture = it) } }
                item { FullSwitch("Agent 自动绑定流量证据", current.agentTrafficBinding) { draft = current.copy(agentTrafficBinding = it) } }
                item { FullSwitch("录制 LLM 调用", current.llmRecord) { draft = current.copy(llmRecord = it) } }
                item { FullGroupTitle("模型与 Agent") }
                item { FullSwitch("启用 LLM 故障转移池", current.llmPoolEnabled) { draft = current.copy(llmPoolEnabled = it) } }
                item { FullSwitch("已绑定模型失败时回落到池", current.llmPoolBindFallback) {
                    draft = current.copy(llmPoolBindFallback = it) } }
                item { FullSwitch("向规划 Agent 注入操作约束", current.constraintsInjectPlanner) {
                    draft = current.copy(constraintsInjectPlanner = it) } }
                item { FullSwitch("向工作 Agent 注入操作约束", current.constraintsInjectWorker) {
                    draft = current.copy(constraintsInjectWorker = it) } }
                item { FullSwitch("实验性上下文压缩", current.noaCompaction) { draft = current.copy(noaCompaction = it) } }
                item { FullGroupTitle("网络与搜索") }
                item { FullTextField("全局出口代理", current.globalProxy,
                    { draft = current.copy(globalProxy = it) }, KeyboardType.Uri, secret = true) }
                item { FullSwitch("启用 Web 搜索", current.webSearchEnabled) { draft = current.copy(webSearchEnabled = it) } }
                if (current.webSearchEnabled) {
                    item {
                        Text("搜索服务", color = FullInk, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Row(Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                            listOf("ddgs", "brave-free", "tavily", "deepseek").forEach { option ->
                                FilterChip(selected = current.webSearchBackend == option,
                                    onClick = { draft = current.copy(webSearchBackend = option) },
                                    label = { Text(option, fontSize = 10.sp) }, shape = RoundedCornerShape(7.dp))
                            }
                        }
                    }
                    item { FullTextField("搜索代理", current.webSearchProxy,
                        { draft = current.copy(webSearchProxy = it) }, KeyboardType.Uri, secret = true) }
                    item { FullTextField("Brave API Key（${if (current.braveKeySet) "已配置" else "未配置"}）", braveKey,
                        { braveKey = it; if (it.isNotBlank()) clearBraveKey = false },
                        KeyboardType.Password, secret = true) }
                    if (current.braveKeySet) item {
                        TextButton(onClick = { clearBraveKey = !clearBraveKey; braveKey = "" }) {
                            Text(if (clearBraveKey) "保存时将清除 Brave Key · 撤销" else "清除 Brave Key")
                        }
                    }
                    item { FullTextField("Tavily API Key（${if (current.tavilyKeySet) "已配置" else "未配置"}）", tavilyKey,
                        { tavilyKey = it; if (it.isNotBlank()) clearTavilyKey = false },
                        KeyboardType.Password, secret = true) }
                    if (current.tavilyKeySet) item {
                        TextButton(onClick = { clearTavilyKey = !clearTavilyKey; tavilyKey = "" }) {
                            Text(if (clearTavilyKey) "保存时将清除 Tavily Key · 撤销" else "清除 Tavily Key")
                        }
                    }
                }
                item { FullTextField("Python 解释器路径", current.pythonInterpreter,
                    { draft = current.copy(pythonInterpreter = it) }) }
                item { FullGroupTitle("通知") }
                item { FullSwitch("启用漏洞通知", current.notifyEnabled) { draft = current.copy(notifyEnabled = it) } }
                item { FullTextField("外部访问地址", current.notifyPublicBaseUrl,
                    { draft = current.copy(notifyPublicBaseUrl = it) }, KeyboardType.Uri) }
                item { FullTextField("汇总周期（分钟）", current.notifyDigestIntervalMin,
                    { draft = current.copy(notifyDigestIntervalMin = it) }, KeyboardType.Number) }
                if (saved) item { Text("已保存", color = FullAccent, fontSize = 12.sp) }
                item {
                    Button(onClick = {
                        scope.launch {
                            saving = true
                            saved = false
                            try {
                                val patch = current.toJson()
                                if (clearBraveKey) patch.put("brave_search_api_key", "")
                                else if (braveKey.isNotBlank()) patch.put("brave_search_api_key", braveKey)
                                if (clearTavilyKey) patch.put("tavily_search_api_key", "")
                                else if (tavilyKey.isNotBlank()) patch.put("tavily_search_api_key", tavilyKey)
                                draft = SettingsDraft.fromJson(api.request("PUT", "/api/settings", patch))
                                braveKey = ""
                                tavilyKey = ""
                                clearBraveKey = false
                                clearTavilyKey = false
                                error = null
                                saved = true
                            } catch (t: Exception) { error = t.message ?: "保存失败" }
                            finally { saving = false }
                        }
                    }, enabled = !saving, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(9.dp)) {
                        Text(if (saving) "保存中…" else "保存配置")
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun FullModeHeader(title: String, onBack: () -> Unit, onRefresh: (() -> Unit)? = null) {
    Column {
        Row(Modifier.fillMaxWidth().height(55.dp).padding(horizontal = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回", tint = FullInk) }
            Text(title, Modifier.weight(1f), color = FullInk, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            if (onRefresh != null) IconButton(onClick = onRefresh) {
                Icon(Icons.Outlined.Refresh, "刷新", tint = FullMuted)
            }
        }
        HorizontalDivider(color = FullLine)
    }
}

@Composable
private fun FullTextField(label: String, value: String, onValueChange: (String) -> Unit,
                          keyboardType: KeyboardType = KeyboardType.Text, secret: Boolean = false, hint: String = "") {
    Column {
        Text(label, color = FullInk, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(7.dp))
        OutlinedTextField(value = value, onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(), singleLine = true,
            placeholder = { if (hint.isNotBlank()) Text(hint, fontSize = 12.sp) },
            visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
            shape = RoundedCornerShape(9.dp),
            colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color.White,
                focusedContainerColor = Color.White, unfocusedBorderColor = FullLine))
    }
}

@Composable
private fun FullSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 47.dp).clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = FullInk, fontSize = 13.sp)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun FullGroupTitle(title: String) {
    Column(Modifier.padding(top = 13.dp)) {
        Text(title, color = FullInk, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = FullLine)
    }
}

@Composable
private fun FullLoading() {
    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(23.dp), strokeWidth = 2.dp)
    }
}

@Composable
private fun FullError(message: String) {
    Text(message, Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 9.dp),
        color = Color(0xFFB56D55), fontSize = 12.sp)
}
