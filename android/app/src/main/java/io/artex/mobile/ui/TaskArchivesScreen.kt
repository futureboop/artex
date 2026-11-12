package io.artex.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import io.artex.mobile.data.TaskArchiveSummary
import io.artex.mobile.data.deleteTaskArchive
import io.artex.mobile.data.restoreTaskArchive
import io.artex.mobile.data.taskArchives
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val ArchiveInk = Color(0xFF1C2E2A)
private val ArchiveMuted = Color(0xFF73807A)
private val ArchiveLine = Color(0xFFE3E6DE)
private val ArchiveDanger = Color(0xFFB56D55)

/** Native archive browser: list, search, restore and queued deletion. */
@Composable
fun TaskArchivesScreen(api: ArtexApi, onBack: () -> Unit, onChanged: () -> Unit) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    var draft by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var page by rememberSaveable { mutableStateOf(1) }
    var reload by remember { mutableStateOf(0) }
    var archives by remember { mutableStateOf<List<TaskArchiveSummary>>(emptyList()) }
    var total by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var actionBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var pendingDelete by remember { mutableStateOf<TaskArchiveSummary?>(null) }

    LaunchedEffect(api, page, query, reload) {
        busy = true
        try {
            val response = api.taskArchives(page, query)
            archives = response.items
            total = response.total
            error = null
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = e.message ?: "读取归档失败" }
        finally { busy = false }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(58.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
            Spacer(Modifier.width(4.dp))
            Text("任务归档", Modifier.weight(1f), color = ArchiveInk, fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold)
            IconButton(onClick = { reload++ }) { Icon(Icons.Outlined.Refresh, "刷新") }
        }
        HorizontalDivider(color = ArchiveLine)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(draft, { draft = it }, Modifier.weight(1f), singleLine = true,
                        placeholder = { Text("搜索任务名称或编号") }, shape = RoundedCornerShape(10.dp))
                    Spacer(Modifier.width(6.dp))
                    TextButton(onClick = { query = draft.trim(); page = 1 }) { Text("搜索") }
                }
            }
            item { Text("共 $total 项归档", color = ArchiveMuted, fontSize = 12.sp) }
            if (busy && archives.isEmpty()) item {
                Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }
            if (error != null) item { Text(error.orEmpty(), color = ArchiveDanger, fontSize = 12.sp) }
            if (!busy && archives.isEmpty() && error == null) item {
                Text("没有归档任务", Modifier.padding(vertical = 24.dp), color = ArchiveMuted, fontSize = 13.sp)
            }
            items(archives, key = { it.id }) { archive ->
                ArchiveRow(archive, actionBusy, onRestore = {
                    if (!actionBusy) scope.launch {
                        actionBusy = true
                        try {
                            api.restoreTaskArchive(archive.id)
                            onChanged()
                            reload++
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { error = e.message ?: "恢复归档失败" }
                        finally { actionBusy = false }
                    }
                }, onDelete = { pendingDelete = archive })
            }
            if (total > 20) item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { page-- }, enabled = page > 1 && !busy) { Text("上一页") }
                    Text("$page / ${(total + 19) / 20}", color = ArchiveMuted, fontSize = 12.sp)
                    OutlinedButton(onClick = { page++ }, enabled = page * 20 < total && !busy) { Text("下一页") }
                }
            }
        }
    }

    pendingDelete?.let { archive ->
        AlertDialog(onDismissRequest = { pendingDelete = null },
            title = { Text("删除归档 #${archive.taskId}") },
            text = { Text("归档包删除后无法再恢复该任务。") },
            confirmButton = { TextButton(onClick = {
                if (!actionBusy) scope.launch {
                    actionBusy = true
                    try {
                        api.deleteTaskArchive(archive.id)
                        pendingDelete = null
                        onChanged()
                        reload++
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { error = e.message ?: "删除归档失败" }
                    finally { actionBusy = false }
                }
            }, enabled = !actionBusy) { Text("确认删除", color = ArchiveDanger) } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } })
    }
}

@Composable
private fun ArchiveRow(archive: TaskArchiveSummary, busy: Boolean, onRestore: () -> Unit, onDelete: () -> Unit) {
    val label = when (archive.state) {
        "ready" -> "已归档"
        "archive_queued", "archiving" -> "归档中"
        "restore_queued", "restoring" -> "恢复中"
        "delete_queued", "deleting" -> "删除中"
        "archive_failed" -> "归档失败"
        "restore_failed" -> "恢复失败"
        "delete_failed" -> "删除失败"
        else -> archive.state
    }
    Surface(shape = RoundedCornerShape(11.dp), color = Color.White.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, ArchiveLine)) {
        Column(Modifier.fillMaxWidth().padding(15.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("#${archive.taskId}", color = ArchiveMuted, fontSize = 11.sp)
                Spacer(Modifier.width(9.dp))
                Text(label, color = if (archive.state.endsWith("failed")) ArchiveDanger else ArchiveMuted, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                if (archive.state !in listOf("ready", "archive_failed", "restore_failed", "delete_failed"))
                    Text("${archive.progress}%", color = ArchiveMuted, fontSize = 11.sp)
            }
            Spacer(Modifier.height(9.dp))
            Text(archive.name.ifBlank { archive.description.ifBlank { "任务 #${archive.taskId}" } },
                color = ArchiveInk, fontSize = 15.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (archive.error.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(archive.error, color = ArchiveDanger, fontSize = 11.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis)
            }
            if (archive.state in listOf("ready", "restore_failed", "delete_failed")) {
                Spacer(Modifier.height(9.dp))
                Row {
                    if (archive.state != "delete_failed") TextButton(onClick = onRestore, enabled = !busy) { Text("恢复任务") }
                    TextButton(onClick = onDelete, enabled = !busy) { Text("删除归档", color = ArchiveDanger) }
                }
            }
        }
    }
}
