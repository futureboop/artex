package io.artex.mobile.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.artex.mobile.data.ArtexApi
import io.artex.mobile.data.Finding
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLEncoder

private val findingInk = Color(0xFF1C2E2A)
private val findingMuted = Color(0xFF73807A)
private val findingAccent = Color(0xFF3F6658)
private val findingLine = Color(0xFFE3E6DE)
private val findingDanger = Color(0xFFB56D55)

private val statuses = listOf(
    "pending" to "待处理", "in_progress" to "处理中", "confirmed" to "已确认",
    "resolved" to "已处理", "fixed" to "已修复", "false_positive" to "误报",
    "ignored" to "忽略", "duplicate" to "重复", "risk_accepted" to "接受风险",
)
private val severities = listOf("critical" to "严重", "high" to "高危", "medium" to "中危", "low" to "低危")

@Composable
fun FindingDetailScreen(finding: Finding, api: ArtexApi, onBack: () -> Unit, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    val encodedId = remember(finding.id) { URLEncoder.encode(finding.id, "UTF-8") }
    val path = "/api/exploration/findings/$encodedId"
    var detail by remember(api, finding.id) { mutableStateOf<JSONObject?>(null) }
    var loading by remember(api, finding.id) { mutableStateOf(true) }
    var working by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var name by remember(finding.id) { mutableStateOf(finding.title) }
    var statusExpanded by remember { mutableStateOf(false) }
    var action by remember { mutableStateOf("") }
    var actionNotes by remember { mutableStateOf("") }
    var expandedReport by remember { mutableStateOf(false) }
    var retests by remember { mutableStateOf<String?>(null) }

    suspend fun refresh() {
        loading = detail == null
        try {
            detail = api.request("GET", path)
            name = detail?.optString("name").orEmpty().ifBlank { finding.title }
            error = ""
        } catch (e: Exception) { error = e.message ?: "读取发现失败" }
        finally { loading = false }
    }

    fun update(body: JSONObject) {
        scope.launch {
            working = true
            try {
                api.request("PATCH", path, body)
                refresh()
                onChanged()
            } catch (e: Exception) { error = e.message ?: "保存失败" }
            finally { working = false }
        }
    }

    LaunchedEffect(api, finding.id) { refresh() }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
            Text("发现详情", Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = findingInk)
            IconButton(onClick = { scope.launch { refresh() } }) { Icon(Icons.Outlined.Refresh, "刷新") }
        }
        HorizontalDivider(color = findingLine)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 18.dp)) {
            if (loading) CircularProgressIndicator(modifier = Modifier.padding(12.dp))
            if (error.isNotBlank()) {
                Text(error, color = findingDanger, fontSize = 13.sp)
                Spacer(Modifier.height(14.dp))
            }
            val current = detail
            val title = current?.optString("name").orEmpty().ifBlank { finding.title }
            Text(title, color = findingInk, fontSize = 23.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(7.dp))
            Text("发现 #${finding.id}  ·  任务 #${current?.optString("task_id").orEmpty().ifBlank { finding.taskId }}",
                color = findingMuted, fontSize = 12.sp)
            Spacer(Modifier.height(22.dp))
            Text("严重程度", color = findingMuted, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                severities.forEach { (value, label) ->
                    FilterChip(selected = (current?.optString("severity") ?: finding.severity) == value,
                        onClick = { update(JSONObject().put("severity", value)) }, label = { Text(label) }, enabled = !working)
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("处置状态", color = findingMuted, fontSize = 12.sp)
            Spacer(Modifier.height(7.dp))
            androidx.compose.foundation.layout.Box {
                OutlinedButton(onClick = { statusExpanded = true }, enabled = !working) {
                    Text(statuses.firstOrNull { it.first == (current?.optString("status") ?: finding.status) }?.second ?: "选择状态")
                }
                DropdownMenu(expanded = statusExpanded, onDismissRequest = { statusExpanded = false }) {
                    statuses.forEach { (value, label) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = {
                            statusExpanded = false
                            update(JSONObject().put("status", value))
                        })
                    }
                }
            }
            Spacer(Modifier.height(17.dp))
            Text("名称", color = findingMuted, fontSize = 12.sp)
            Spacer(Modifier.height(7.dp))
            OutlinedTextField(name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { update(JSONObject().put("name", name.trim())) }, enabled = !working && name.isNotBlank()) {
                Text("保存名称")
            }
            HorizontalDivider(Modifier.padding(vertical = 20.dp), color = findingLine)
            Text("摘要", color = findingInk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(7.dp))
            Text(current?.optString("summary").orEmpty().ifBlank { finding.summary.ifBlank { "暂无摘要" } },
                color = findingInk, fontSize = 13.sp, lineHeight = 20.sp)
            val evidence = current?.optString("evidence").orEmpty()
            if (evidence.isNotBlank()) {
                Spacer(Modifier.height(19.dp))
                Text("证据", color = findingInk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(7.dp))
                Text(evidence, color = findingInk, fontSize = 13.sp, lineHeight = 20.sp)
            }
            val report = current?.optString("report").orEmpty()
            if (report.isNotBlank()) {
                Spacer(Modifier.height(19.dp))
                Text("报告", color = findingInk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(7.dp))
                Text(if (expandedReport) report else report.take(500), color = findingInk, fontSize = 13.sp, lineHeight = 20.sp)
                if (report.length > 500) TextButton(onClick = { expandedReport = !expandedReport }) {
                    Text(if (expandedReport) "收起报告" else "查看完整报告")
                }
            }
            Spacer(Modifier.height(23.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedButton(onClick = { actionNotes = ""; action = "retest" }) { Text("发起复测") }
                OutlinedButton(onClick = { actionNotes = ""; action = "deepen" }) { Text("深入验证") }
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = {
                scope.launch {
                    try { retests = api.request("GET", "$path/retests").optJSONArray("retests")?.toString(2) ?: "[]" }
                    catch (e: Exception) { error = e.message ?: "读取复测记录失败" }
                }
            }) { Text("查看复测记录") }
            retests?.let { Text(it, color = findingMuted, fontSize = 12.sp, lineHeight = 18.sp) }
            Spacer(Modifier.height(14.dp))
            TextButton(onClick = { action = "delete" }, colors = ButtonDefaults.textButtonColors(contentColor = findingDanger)) {
                Text("删除这条发现")
            }
            Spacer(Modifier.height(35.dp))
        }
    }

    if (action.isNotBlank()) {
        val heading = when (action) { "retest" -> "发起复测"; "deepen" -> "深入验证"; else -> "删除发现" }
        AlertDialog(onDismissRequest = { action = "" }, title = { Text(heading) }, text = {
            if (action == "delete") Text("删除后会移除这条发现及其来源节点。")
            else OutlinedTextField(actionNotes, onValueChange = { actionNotes = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text(if (action == "retest") "复测说明" else "验证要求") }, minLines = 3)
        }, confirmButton = {
            Button(onClick = {
                val selected = action
                action = ""
                scope.launch {
                    working = true
                    try {
                        when (selected) {
                            "retest" -> api.request("POST", "$path/retests", JSONObject().put("notes", actionNotes))
                            "deepen" -> api.request("POST", "$path/deepen", JSONObject().put("description", actionNotes))
                            "delete" -> api.request("DELETE", path)
                        }
                        onChanged()
                        if (selected == "delete") onBack() else refresh()
                    } catch (e: Exception) { error = e.message ?: "操作失败" }
                    finally { working = false }
                }
            }, enabled = !working && (action == "delete" || actionNotes.isNotBlank())) { Text("确认") }
        }, dismissButton = { TextButton(onClick = { action = "" }) { Text("取消") } })
    }
}
