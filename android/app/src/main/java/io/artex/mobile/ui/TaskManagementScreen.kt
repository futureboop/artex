package io.artex.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.artex.mobile.data.ArtexApi
import io.artex.mobile.data.DeleteTaskOptions
import io.artex.mobile.data.NewTaskInput
import io.artex.mobile.data.Task
import io.artex.mobile.data.TaskCategory
import io.artex.mobile.data.TaskProfile
import io.artex.mobile.data.archiveTask
import io.artex.mobile.data.controlTask
import io.artex.mobile.data.createTask
import io.artex.mobile.data.deleteTask
import io.artex.mobile.data.pinTask
import io.artex.mobile.data.renameTask
import io.artex.mobile.data.taskCategories
import io.artex.mobile.data.taskProfiles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val WorkInk = Color(0xFF1C2E2A)
private val WorkMuted = Color(0xFF73807A)
private val WorkLine = Color(0xFFE3E6DE)
private val WorkAccent = Color(0xFF3F6658)
private val WorkDanger = Color(0xFFB56D55)

/** Full native task creation using the existing POST /api/tasks contract. */
@Composable
fun CreateTaskScreen(api: ArtexApi, onBack: () -> Unit, onCreated: (Task) -> Unit) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var description by rememberSaveable { mutableStateOf("") }
    var goal by rememberSaveable { mutableStateOf("") }
    var timeoutMinutes by rememberSaveable { mutableStateOf("") }
    var heartbeatMinutes by rememberSaveable { mutableStateOf("") }
    var seedFirstIntent by rememberSaveable { mutableStateOf(false) }
    var coverageEnabled by rememberSaveable { mutableStateOf(true) }
    var categoryId by rememberSaveable { mutableStateOf<Long?>(null) }
    var categories by remember { mutableStateOf<List<TaskCategory>>(emptyList()) }
    var profiles by remember { mutableStateOf<List<TaskProfile>>(emptyList()) }
    val selectedProfiles = remember { mutableStateListOf<Long>() }
    var categoryMenu by remember { mutableStateOf(false) }
    var profileMenu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(api) {
        launch {
            try { categories = api.taskCategories() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* Creating an uncategorized task remains available. */ }
        }
        launch {
            try { profiles = api.taskProfiles() }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* The server's active LLM profile remains available. */ }
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
            Spacer(Modifier.width(4.dp))
            Text("新建任务", color = WorkInk, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }
        HorizontalDivider(color = WorkLine)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 19.dp)) {
            FieldLabel("任务名称")
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("可选，留空时显示任务描述") }, shape = RoundedCornerShape(10.dp))
            Spacer(Modifier.height(17.dp))
            FieldLabel("任务描述")
            OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), minLines = 3, maxLines = 6,
                placeholder = { Text("描述授权范围与要做的工作") }, shape = RoundedCornerShape(10.dp))
            Spacer(Modifier.height(17.dp))
            FieldLabel("目标")
            OutlinedTextField(goal, { goal = it }, Modifier.fillMaxWidth(), minLines = 2, maxLines = 5,
                placeholder = { Text("希望 Agent 达成什么结果") }, shape = RoundedCornerShape(10.dp))
            Spacer(Modifier.height(22.dp))
            HorizontalDivider(color = WorkLine)
            Spacer(Modifier.height(20.dp))
            Text("运行设置", color = WorkInk, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(15.dp))
            FieldLabel("分类")
            Box {
                OutlinedButton(onClick = { categoryMenu = true }, shape = RoundedCornerShape(10.dp)) {
                    Text(categories.firstOrNull { it.id == categoryId }?.name ?: "不分类")
                }
                DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                    DropdownMenuItem(text = { Text("不分类") }, onClick = { categoryId = null; categoryMenu = false })
                    categories.forEach { category ->
                        DropdownMenuItem(text = { Text(category.name) }, onClick = {
                            categoryId = category.id; categoryMenu = false
                        })
                    }
                }
            }
            Spacer(Modifier.height(15.dp))
            FieldLabel("LLM 配置链")
            Text("未选时使用服务器当前配置；按添加顺序故障转移。", color = WorkMuted, fontSize = 12.sp)
            Spacer(Modifier.height(7.dp))
            Box {
                OutlinedButton(onClick = { profileMenu = true }, shape = RoundedCornerShape(10.dp)) {
                    Text("添加配置")
                }
                DropdownMenu(expanded = profileMenu, onDismissRequest = { profileMenu = false }) {
                    profiles.filterNot { it.id in selectedProfiles }.forEach { profile ->
                        DropdownMenuItem(text = { Text("${profile.name} · ${profile.model}", maxLines = 1) }, onClick = {
                            selectedProfiles.add(profile.id); profileMenu = false
                        })
                    }
                    if (profiles.isEmpty()) DropdownMenuItem(text = { Text("没有可选配置") }, onClick = { profileMenu = false })
                }
            }
            if (selectedProfiles.isNotEmpty()) {
                Spacer(Modifier.height(7.dp))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    selectedProfiles.forEachIndexed { index, id ->
                        val profile = profiles.firstOrNull { it.id == id }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}. ${profile?.name ?: "配置 #$id"}", Modifier.weight(1f),
                                color = WorkInk, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            TextButton(onClick = { selectedProfiles.remove(id) }) { Text("移除") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            FieldLabel("超时时间（分钟）")
            OutlinedTextField(timeoutMinutes, { timeoutMinutes = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("留空表示不限时") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(10.dp))
            Spacer(Modifier.height(14.dp))
            FieldLabel("规划心跳（分钟）")
            OutlinedTextField(heartbeatMinutes, { heartbeatMinutes = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("留空使用服务器默认值") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(10.dp))
            Spacer(Modifier.height(11.dp))
            SettingSwitch("立即下发第一条意图", seedFirstIntent) { seedFirstIntent = it }
            SettingSwitch("启用资产覆盖度", coverageEnabled) { coverageEnabled = it }
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error.orEmpty(), color = WorkDanger, fontSize = 12.sp)
            }
            Spacer(Modifier.height(16.dp))
        }
        Surface(color = Color.White.copy(alpha = 0.96f), border = BorderStroke(1.dp, WorkLine)) {
            Button(onClick = {
                if (busy) return@Button
                val timeout = timeoutMinutes.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
                val heartbeat = heartbeatMinutes.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
                error = when {
                    description.isBlank() -> "请输入任务描述"
                    timeoutMinutes.isNotBlank() && (timeout == null || timeout < 0 || timeout > 35_000_000) -> "超时时间无效"
                    heartbeatMinutes.isNotBlank() && (heartbeat == null || heartbeat < 0 || heartbeat > 35_000_000) -> "规划心跳无效"
                    else -> null
                }
                if (error != null) return@Button
                scope.launch {
                    busy = true
                    try {
                        val created = api.createTask(NewTaskInput(name, description, goal, categoryId,
                            selectedProfiles.toList(), (timeout ?: 0) * 60, (heartbeat ?: 0) * 60,
                            seedFirstIntent, coverageEnabled))
                        onCreated(created)
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "创建任务失败" }
                    finally { busy = false }
                }
            }, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp).height(49.dp),
                shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = WorkInk)) {
                if (busy) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp, color = Color.White)
                else Text("创建任务", fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Compact actions for the task detail. Destructive operations require confirmation. */
@Composable
fun TaskOperations(task: Task, api: ArtexApi, onChanged: () -> Unit, onClosed: () -> Unit) {
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var rename by remember { mutableStateOf(false) }
    var archive by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf(false) }
    var title by remember(task.id) { mutableStateOf(task.title) }
    var pinned by remember(task.id) { mutableStateOf(task.pinned) }
    var status by remember(task.id) { mutableStateOf(task.status) }
    var options by remember(task.id) { mutableStateOf(DeleteTaskOptions()) }
    var error by remember { mutableStateOf<String?>(null) }

    fun perform(action: suspend () -> Unit, after: () -> Unit = onChanged) {
        if (busy) return
        scope.launch {
            busy = true
            error = null
            try { action(); after() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: "操作失败" }
            finally { busy = false }
        }
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(onClick = {
            val next = if (status == "paused") "resume" else "pause"
            perform({ api.controlTask(task.id, next) }) { status = if (next == "pause") "paused" else "running"; onChanged() }
        }, enabled = !busy && status in listOf("running", "paused", "queued", "created"),
            shape = RoundedCornerShape(9.dp)) {
            Text(if (status == "paused") "继续任务" else "暂停任务", fontSize = 12.sp)
        }
        Spacer(Modifier.width(8.dp))
        Box {
            IconButton(onClick = { menu = true }, enabled = !busy) { Icon(Icons.Outlined.MoreVert, "更多任务操作") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; rename = true })
                DropdownMenuItem(text = { Text(if (pinned) "取消置顶" else "置顶") }, onClick = {
                    menu = false
                    val next = !pinned
                    perform({ api.pinTask(task.id, next) }) { pinned = next; onChanged() }
                })
                DropdownMenuItem(text = { Text("归档") }, onClick = { menu = false; archive = true })
                DropdownMenuItem(text = { Text("删除任务", color = WorkDanger) }, onClick = { menu = false; delete = true })
            }
        }
        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
    }
    if (error != null) Text(error.orEmpty(), color = WorkDanger, fontSize = 12.sp)

    if (rename) {
        AlertDialog(onDismissRequest = { rename = false }, title = { Text("重命名任务") },
            text = { OutlinedTextField(title, { title = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = {
                if (title.isBlank() || busy) return@TextButton
                perform({ api.renameTask(task.id, title) }) { rename = false; onChanged() }
            }, enabled = title.isNotBlank() && !busy) { Text("保存") } },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("取消") } })
    }
    if (archive) {
        AlertDialog(onDismissRequest = { archive = false }, title = { Text("归档任务 #${task.id}") },
            text = { Text("归档会排队处理并从当前任务列表移出。归档完成后可在归档列表恢复。") },
            confirmButton = { TextButton(onClick = {
                perform({ api.archiveTask(task.id) }) { archive = false; onClosed() }
            }, enabled = !busy) { Text("确认归档") } },
            dismissButton = { TextButton(onClick = { archive = false }) { Text("取消") } })
    }
    if (delete) {
        AlertDialog(onDismissRequest = { delete = false }, title = { Text("删除任务 #${task.id}") },
            text = {
                Column(Modifier.heightIn(max = 410.dp).verticalScroll(rememberScrollState())) {
                    Text("删除任务无法撤销。以下关联数据默认保留；勾选后会同时清理。", fontSize = 13.sp)
                    Spacer(Modifier.height(9.dp))
                    DeleteCheck("关联资产", options.deleteAssets) { options = options.copy(deleteAssets = it) }
                    DeleteCheck("关联流量", options.deleteTraffic) { options = options.copy(deleteTraffic = it) }
                    DeleteCheck("工作文件", options.deleteFiles) { options = options.copy(deleteFiles = it) }
                    DeleteCheck("发现记录", options.deleteFindings) { options = options.copy(deleteFindings = it) }
                    DeleteCheck("LLM 录制", options.deleteLLMRecords) { options = options.copy(deleteLLMRecords = it) }
                }
            },
            confirmButton = { TextButton(onClick = {
                perform({ api.deleteTask(task.id, options) }) { delete = false; onClosed() }
            }, enabled = !busy) { Text("确认删除", color = WorkDanger) } },
            dismissButton = { TextButton(onClick = { delete = false }) { Text("取消") } })
    }
}

@Composable
private fun FieldLabel(label: String) {
    Text(label, color = WorkInk, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    Spacer(Modifier.height(7.dp))
}

@Composable
private fun SettingSwitch(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.heightIn(min = 50.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = WorkInk, fontSize = 13.sp)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun DeleteCheck(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onCheckedChange(!checked) }.heightIn(min = 43.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, color = WorkInk, fontSize = 13.sp)
    }
}
