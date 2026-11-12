package io.artex.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.artex.mobile.data.ArtexApi
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/** Read-only native entry points for the sections in the web application's full sidebar.
 * Paths are the existing backend JSON endpoints, not web routes. All selected endpoints
 * have object roots; the array-root /companies endpoint is deliberately not used here.
 */
enum class FeatureDestination(
    val title: String,
    val path: String,
    val collectionKey: String?,
    val group: String,
) {
    DASHBOARD("仪表盘", "/api/stats", null, "功能"),
    TRAFFIC("流量", "/api/traffic?page=0&size=50", "exchanges", "功能"),
    COMMANDS("工具执行", "/api/commands?page=0&size=50", "commands", "功能"),
    LLM_RECORDS("LLM 录制", "/api/llm/records?page=0&size=50", "records", "功能"),
    ASSETS("资产", "/api/assets?limit=50&offset=0", "assets", "功能"),
    SYNC("资产同步", "/api/sync/scopesentry/status", null, "功能"),
    WORKSPACE("工作空间", "/api/workspace/list?path=", "entries", "功能"),
    LLM("LLM", "/api/llm/profiles", "profiles", "系统"),
    AGENTS("Agent", "/api/agents", "agents", "系统"),
    MCP("MCP", "/api/mcp", "servers", "系统"),
    SKILLS("Skill", "/api/skills", "skills", "系统"),
    TOOLS("工具", "/api/tools", "tools", "系统"),
    NOTIFY("通知推送", "/api/notify/channels", "channels", "系统"),
    INTERCEPT("拦截规则", "/api/intercept/rules", "rules", "系统"),
    ASSET_INTERCEPT("资产拦截", "/api/asset-intercept/rules", "rules", "系统"),
    APPROVALS("审批记录", "/api/intercept/history?page=1&size=20", "items", "系统"),
    LOGS("日志", "/api/logs/history?limit=100", "items", "系统"),
    SYSTEM_SETTINGS("系统配置", "/api/settings", null, "系统"),
}

private val ExplorerInk = Color(0xFF1C2E2A)
private val ExplorerMuted = Color(0xFF73807A)
private val ExplorerAccent = Color(0xFF3F6658)
private val ExplorerLine = Color(0xFFE3E6DE)

/** Native, lightweight directory. The four primary bottom tabs remain elsewhere. */
@Composable
fun FullModeMenu(onOpen: (FeatureDestination) -> Unit) {
    val grouped = remember { FeatureDestination.entries.groupBy { it.group } }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 28.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("完整模式", Modifier.weight(1f), color = ExplorerInk, fontSize = 27.sp, fontWeight = FontWeight.SemiBold)
                Text("${FeatureDestination.entries.size}", color = ExplorerAccent, fontSize = 25.sp, fontWeight = FontWeight.Light)
            }
            Spacer(Modifier.height(4.dp))
            Text("工作台功能索引", color = ExplorerMuted, fontSize = 12.sp)
            Spacer(Modifier.height(24.dp))
        }
        grouped.forEach { (group, features) ->
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(group, Modifier.weight(1f), color = ExplorerAccent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("${features.size} 项", color = ExplorerMuted, fontSize = 11.sp)
                }
                Spacer(Modifier.height(9.dp))
                Surface(shape = RoundedCornerShape(13.dp), color = Color.White.copy(alpha = 0.93f),
                    border = BorderStroke(1.dp, ExplorerLine)) {
                    Column {
                        features.forEachIndexed { index, feature ->
                            val featured = feature == FeatureDestination.DASHBOARD
                            Row(Modifier.fillMaxWidth().background(if (featured) ExplorerInk else Color.Transparent)
                                .clickable { onOpen(feature) }
                                .padding(horizontal = 15.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text((FeatureDestination.entries.indexOf(feature) + 1).toString().padStart(2, '0'),
                                    color = if (featured) Color.White.copy(alpha = 0.7f) else ExplorerMuted, fontSize = 11.sp)
                                Spacer(Modifier.width(15.dp))
                                Text(feature.title, Modifier.weight(1f), color = if (featured) Color.White else ExplorerInk, fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium)
                                Icon(Icons.Outlined.ArrowForward, contentDescription = null,
                                    modifier = Modifier.size(16.dp), tint = if (featured) Color.White.copy(alpha = 0.8f) else ExplorerMuted)
                            }
                            if (index < features.lastIndex) HorizontalDivider(Modifier.padding(start = 43.dp), color = ExplorerLine)
                        }
                    }
                }
                Spacer(Modifier.height(22.dp))
            }
        }
    }
}

private data class ExplorerItem(val value: JSONObject, val index: Int)

/** Read-only explorer of an existing ARTEX JSON endpoint. No WebView or API mutation. */
@Composable
fun FeatureExplorer(feature: FeatureDestination, api: ArtexApi, onBack: () -> Unit) {
    var revision by remember(feature, api) { mutableIntStateOf(0) }
    var workspacePath by remember(feature, api) { mutableStateOf("") }
    var response by remember(feature, api) { mutableStateOf<JSONObject?>(null) }
    var loading by remember(feature, api) { mutableStateOf(true) }
    var error by remember(feature, api) { mutableStateOf<String?>(null) }
    var selected by remember(feature, api) { mutableStateOf<JSONObject?>(null) }
    var selectedTitle by remember(feature, api) { mutableStateOf("") }
    var detail by remember(feature, api) { mutableStateOf<JSONObject?>(null) }
    var detailError by remember(feature, api) { mutableStateOf<String?>(null) }
    var detailLoading by remember(feature, api) { mutableStateOf(false) }

    val requestPath = if (feature == FeatureDestination.WORKSPACE)
        "/api/workspace/list?path=${urlEncode(workspacePath)}" else feature.path

    LaunchedEffect(feature, api, requestPath, revision) {
        loading = true
        error = null
        try {
            response = api.request("GET", requestPath)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            error = failure.message ?: "加载失败"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(feature, api, selected) {
        val item = selected ?: return@LaunchedEffect
        detail = item
        detailError = null
        val path = detailPath(feature, item) ?: return@LaunchedEffect
        detailLoading = true
        try {
            detail = api.request("GET", path)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (failure: Exception) {
            detailError = failure.message ?: "完整内容加载失败"
        } finally {
            detailLoading = false
        }
    }

    BackHandler(enabled = selected != null || workspacePath.isNotEmpty()) {
        if (selected != null) selected = null else workspacePath = parentPath(workspacePath)
    }
    BackHandler(enabled = selected == null && workspacePath.isEmpty(), onBack = onBack)

    val collection = remember(response, feature) {
        val array = feature.collectionKey?.let { response?.optJSONArray(it) }
        if (array == null) emptyList() else (0 until array.length()).map { index ->
            ExplorerItem(array.optJSONObject(index) ?: JSONObject().put("value", array.opt(index)), index)
        }
    }
    val fields = remember(response, feature) {
        if (feature.collectionKey != null) emptyList() else response?.keys()?.asSequence()?.toList().orEmpty()
            .filterNot { isSecret(it) }.sorted()
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                if (workspacePath.isNotEmpty()) workspacePath = parentPath(workspacePath) else onBack()
            }) { Icon(Icons.Outlined.ArrowBack, contentDescription = "返回", tint = ExplorerInk) }
            Column(Modifier.weight(1f).padding(start = 5.dp)) {
                Text(feature.title, color = ExplorerInk, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(if (workspacePath.isNotEmpty()) workspacePath else "只读浏览",
                    color = ExplorerMuted, fontSize = 11.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { revision++ }) {
                Icon(Icons.Outlined.Refresh, contentDescription = "刷新", tint = ExplorerAccent)
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
        ) {
            if (error != null) item {
                ExplorerNotice("加载失败：$error", error = true)
                Spacer(Modifier.height(12.dp))
            }
            if (response != null) {
                item {
                    val count = if (feature.collectionKey == null) fields.size else collection.size
                    val total = response?.optInt("total", count) ?: count
                    Text(if (total > count) "显示 $count / $total 条" else "$count 条内容",
                        color = ExplorerMuted, fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 10.dp))
                }
                if (feature.collectionKey != null) {
                    if (collection.isEmpty() && !loading) item { ExplorerNotice("暂无内容") }
                    items(collection, key = { "${it.index}" }) { item ->
                        val obj = item.value
                        val title = itemTitle(feature, obj, item.index)
                        val subtitle = itemSubtitle(obj)
                        ExplorerRow(title, subtitle) {
                            if (feature == FeatureDestination.WORKSPACE && obj.optBoolean("dir")) {
                                workspacePath = obj.optString("path")
                                response = null
                            } else {
                                selectedTitle = title
                                selected = obj
                            }
                        }
                    }
                } else {
                    items(fields) { key ->
                        val value = response?.opt(key)
                        ExplorerRow(fieldLabel(key), shortValue(key, value)) {
                            selectedTitle = fieldLabel(key)
                            selected = JSONObject().put(key, value)
                        }
                    }
                }
            } else if (loading) item {
                Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = ExplorerAccent, modifier = Modifier.size(25.dp))
                }
            }
        }
    }

    if (selected != null) {
        AlertDialog(
            onDismissRequest = { selected = null },
            title = { Text(selectedTitle, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(Modifier.fillMaxWidth().heightIn(max = 480.dp).verticalScroll(rememberScrollState())) {
                    if (detailLoading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (detailError != null) Text("完整内容加载失败：$detailError；下方显示列表摘要。",
                        color = Color(0xFFB56D55), fontSize = 12.sp)
                    val obj = detail ?: selected
                    if (obj != null) {
                        obj.keys().asSequence().toList().filterNot { isSecret(it) }.sorted().forEach { key ->
                            Text(fieldLabel(key), color = ExplorerMuted, fontSize = 11.sp,
                                modifier = Modifier.padding(top = 10.dp))
                            Text(readableValue(key, obj.opt(key)), color = ExplorerInk, fontSize = 13.sp)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { selected = null }) { Text("关闭") } },
        )
    }
}

@Composable
private fun ExplorerRow(title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = ExplorerInk, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(subtitle, color = ExplorerMuted, fontSize = 12.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Outlined.ArrowForward, contentDescription = null,
            tint = ExplorerMuted, modifier = Modifier.size(17.dp))
    }
    HorizontalDivider(color = ExplorerLine)
}

@Composable
private fun ExplorerNotice(message: String, error: Boolean = false) {
    Surface(shape = RoundedCornerShape(12.dp), color = Color.White.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, ExplorerLine)) {
        Text(message, color = if (error) Color(0xFFB56D55) else ExplorerMuted,
            fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(18.dp))
    }
}

private fun detailPath(feature: FeatureDestination, item: JSONObject): String? {
    val id = item.optString("id").takeIf { it.isNotBlank() } ?: return when (feature) {
        FeatureDestination.WORKSPACE -> item.optString("path").takeIf { it.isNotBlank() }
            ?.let { "/api/workspace/read?path=${urlEncode(it)}" }
        else -> null
    }
    return when (feature) {
        FeatureDestination.TRAFFIC -> "/api/traffic/exchange?id=${urlEncode(id)}"
        FeatureDestination.LLM_RECORDS -> "/api/llm/records/${urlEncode(id)}"
        FeatureDestination.AGENTS -> item.optString("key").takeIf { it.isNotBlank() }
            ?.let { "/api/agents/${urlEncode(it)}" }
        FeatureDestination.APPROVALS -> "/api/intercept/history/${urlEncode(id)}"
        FeatureDestination.WORKSPACE -> item.optString("path").takeIf { it.isNotBlank() }
            ?.let { "/api/workspace/read?path=${urlEncode(it)}" }
        else -> null
    }
}

private fun itemTitle(feature: FeatureDestination, item: JSONObject, index: Int): String {
    val candidates = when (feature) {
        FeatureDestination.TRAFFIC -> listOf("url", "host", "id")
        FeatureDestination.ASSETS -> listOf("domain", "ip", "url", "app_name", "service_name", "id")
        FeatureDestination.COMMANDS -> listOf("tool", "worker", "id")
        FeatureDestination.LLM_RECORDS -> listOf("model", "profile_name", "id")
        FeatureDestination.LOGS -> listOf("text", "tag", "id")
        else -> listOf("name", "title", "key", "path", "value", "model", "pattern", "id")
    }
    return candidates.firstNotNullOfOrNull { key ->
        item.optString(key).takeIf { it.isNotBlank() && it != "null" }?.take(140)
    } ?: "记录 ${index + 1}"
}

private fun itemSubtitle(item: JSONObject): String =
    listOf("status", "type", "kind", "level", "worker", "description", "ts", "created_at")
        .mapNotNull { key -> item.optString(key).takeIf { it.isNotBlank() && it != "null" } }
        .take(2).joinToString(" · ").take(180)

private fun shortValue(key: String, value: Any?): String = readableValue(key, value, 120)

private fun readableValue(key: String, value: Any?, limit: Int = 4_000): String {
    if (isSecret(key)) return "••••"
    val rendered = when (value) {
        null, JSONObject.NULL -> "—"
        is JSONObject -> "对象（${value.length()} 项）\n${safeObject(value, 1)}"
        is JSONArray -> "列表（${value.length()} 项）\n${safeArray(value, 1)}"
        else -> value.toString()
    }
    return if (rendered.length > limit) rendered.take(limit) + "…" else rendered
}

private fun safeObject(obj: JSONObject, depth: Int): String {
    if (depth > 2) return "{…}"
    return obj.keys().asSequence().toList().take(30).joinToString("\n") { key ->
        val value = obj.opt(key)
        "$key: ${safeNested(key, value, depth + 1)}"
    }
}

private fun safeArray(array: JSONArray, depth: Int): String =
    (0 until minOf(array.length(), 10)).joinToString("\n") { index ->
        "${index + 1}. ${safeNested("", array.opt(index), depth + 1)}"
    } + if (array.length() > 10) "\n…" else ""

private fun safeNested(key: String, value: Any?, depth: Int): String {
    if (isSecret(key)) return "••••"
    val raw = when (value) {
        null, JSONObject.NULL -> "—"
        is JSONObject -> safeObject(value, depth)
        is JSONArray -> if (depth > 2) "[${value.length()} 项]" else safeArray(value, depth)
        else -> value.toString()
    }
    return raw.take(1_000) + if (raw.length > 1_000) "…" else ""
}

private fun isSecret(key: String): Boolean {
    val normalized = key.lowercase()
    // Token accounting fields are measurements, not authentication credentials.
    if (normalized == "tokens" || normalized.endsWith("_tokens") || normalized.contains("token_usage")) return false
    return normalized == "token" || normalized.endsWith("_token") ||
        listOf("password", "secret", "api_key", "apikey", "authorization", "cookie",
            "credential", "private_key", "proxy").any { normalized.contains(it) }
}

private fun fieldLabel(key: String): String = key.replace('_', ' ')
private fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
private fun parentPath(path: String): String = path.trimEnd('/').substringBeforeLast('/', "")
