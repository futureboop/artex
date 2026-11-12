package io.artex.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import kotlinx.coroutines.delay
import org.json.JSONObject

private val dashInk = Color(0xFF1C2E2A)
private val dashAccent = Color(0xFF3F6658)
private val dashMuted = Color(0xFF73807A)
private val dashLine = Color(0xFFE3E6DE)

@Composable
fun DashboardScreen(api: ArtexApi, onBack: () -> Unit, onTask: (Task) -> Unit, onFinding: (Finding) -> Unit) {
    var stats by remember(api) { mutableStateOf<JSONObject?>(null) }
    var tasks by remember(api) { mutableStateOf<List<Task>>(emptyList()) }
    var findings by remember(api) { mutableStateOf<List<Finding>>(emptyList()) }
    var findingsTotal by remember(api) { mutableIntStateOf(0) }
    var loading by remember(api) { mutableStateOf(true) }
    var error by remember(api) { mutableStateOf("") }
    var refresh by remember(api) { mutableIntStateOf(0) }

    LaunchedEffect(api, refresh) {
        while (true) {
            loading = stats == null
            try {
                stats = api.request("GET", "/api/stats")
                tasks = api.tasks()
                val page = api.findings(limit = 5)
                findings = page.items
                findingsTotal = page.total
                error = ""
            } catch (e: Exception) { error = e.message ?: "读取仪表盘失败" }
            finally { loading = false }
            delay(20_000)
        }
    }

    val running = tasks.count { it.status == "running" }
    val active = stats?.optJSONObject("active_task")?.optString("id")
    val activeTask = tasks.firstOrNull { it.id == active }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 30.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
                Text("仪表盘", Modifier.weight(1f), color = dashInk, fontSize = 25.sp, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = { refresh++ }) { Icon(Icons.Outlined.Refresh, "刷新", tint = dashAccent) }
            }
            Text("每 20 秒更新", Modifier.padding(start = 48.dp, bottom = 17.dp), color = dashMuted, fontSize = 11.sp)
        }
        if (loading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (error.isNotBlank()) item { Text(error, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                DashboardMetric("运行中", running.toString(), Modifier.weight(1f))
                DashboardMetric("全部任务", tasks.size.toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(9.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                DashboardMetric("发现", findingsTotal.toString(), Modifier.weight(1f))
                DashboardMetric("资产", (stats?.optInt("assets") ?: 0).toString(), Modifier.weight(1f))
            }
            Spacer(Modifier.height(27.dp))
        }
        if (activeTask != null) item {
            DashboardHeading("当前任务")
            DashboardItem(activeTask.title, "#${activeTask.id} · ${activeTask.status}") { onTask(activeTask) }
            Spacer(Modifier.height(22.dp))
        }
        item { DashboardHeading("最近任务") }
        items(tasks.take(5), key = { "task-${it.id}" }) { task ->
            DashboardItem(task.title, "#${task.id} · ${task.findings} 个发现") { onTask(task) }
        }
        item { Spacer(Modifier.height(22.dp)); DashboardHeading("最近发现") }
        items(findings, key = { "finding-${it.id}" }) { finding ->
            DashboardItem(finding.title, "${finding.severity} · 任务 #${finding.taskId}") { onFinding(finding) }
        }
        item {
            Spacer(Modifier.height(26.dp))
            Text("LLM ${if (stats?.optBoolean("llm_configured") == true) "已配置" else "未配置"}  ·  规则 ${if (stats?.optBoolean("roe_enabled") == true) "已启用" else "未启用"}",
                color = dashMuted, fontSize = 12.sp)
        }
    }
}

@Composable
private fun DashboardMetric(label: String, value: String, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(13.dp), color = Color.White.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, dashLine)) {
        Column(Modifier.padding(17.dp)) {
            Text(label, color = dashMuted, fontSize = 12.sp)
            Spacer(Modifier.height(9.dp))
            Text(value, color = dashInk, fontSize = 26.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun DashboardHeading(label: String) {
    Text(label, color = dashInk, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun DashboardItem(title: String, subtitle: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = dashInk, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(4.dp))
            Text(subtitle, color = dashMuted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Icons.Outlined.ArrowForward, null, Modifier.size(17.dp), tint = dashMuted)
    }
    HorizontalDivider(color = dashLine)
}
