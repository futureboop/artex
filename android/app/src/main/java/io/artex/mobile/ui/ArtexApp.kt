package io.artex.mobile.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Logout
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.artex.mobile.data.Activity
import io.artex.mobile.data.ArtexApi
import io.artex.mobile.data.ChatAttachment
import io.artex.mobile.data.ChatUploadScope
import io.artex.mobile.data.Conversation
import io.artex.mobile.data.Finding
import io.artex.mobile.data.MainSession
import io.artex.mobile.data.Task
import io.artex.mobile.data.uploadChatAttachments
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Ink = Color(0xFF1C2E2A)
private val Moss = Color(0xFF3F6658)
private val Olive = Color(0xFF879B80)
private val Cream = Color(0xFFF6F4EF)
private val Muted = Color(0xFF73807A)
private val Line = Color(0xFFE3E6DE)
private val Clay = Color(0xFFB56D55)

private val ArtexColors = lightColorScheme(
    primary = Moss, onPrimary = Color.White,
    background = Cream, onBackground = Ink,
    surface = Color.White, onSurface = Ink,
    outline = Line, secondary = Olive,
)

@Composable
fun ArtexApp(model: ArtexViewModel) {
    MaterialTheme(colorScheme = ArtexColors) {
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(model.error) {
            model.error?.let { snackbar.showSnackbar(it); model.clearError() }
        }
        BackHandler(enabled = model.selectedTask != null || model.selectedConversation != null || model.selectedFinding != null ||
            model.creatingTask || model.creatingConversation || model.managingConversation || model.archivesOpen ||
            model.fullModeOpen || model.feature != null) {
            model.goBack()
        }
        LaunchedEffect(model.signedIn, model.tab, model.selectedTask?.id, model.selectedConversation?.id, model.taskDetailTab) {
            while (model.signedIn) {
                delay(if (model.selectedTask != null || model.selectedConversation != null) 6_000 else 20_000)
                when {
                    model.selectedTask != null -> if (model.taskDetailTab == "chat") model.refreshTaskActivity()
                    model.selectedConversation != null -> model.refreshConversationActivity()
                    model.tab == HomeTab.TASKS -> model.refreshTasks()
                    model.tab == HomeTab.CONVERSATIONS -> model.refreshConversations()
                    else -> Unit
                }
            }
        }
        Box(Modifier.fillMaxSize()) {
            AppBackdrop(model.wallpaper, model.wallpaperDim)
            if (!model.signedIn) {
                LoginScreen(model.serverAddress, model.loginBusy, model::login)
            } else {
                Scaffold(
                    containerColor = Color.Transparent,
                    contentWindowInsets = WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        if (model.selectedTask == null && model.selectedConversation == null && model.selectedFinding == null &&
                            !model.creatingTask && !model.creatingConversation && !model.archivesOpen && !model.fullModeOpen) {
                            BottomBar(model.tab, model::selectTab)
                        }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).statusBarsPadding()) {
                        when {
                            model.creatingTask -> CreateTaskScreen(model.apiClient()!!, model::goBack, model::onTaskCreated)
                            model.creatingConversation -> CreateConversationScreen(model.apiClient()!!, model::goBack, model::onConversationCreated)
                            model.managingConversation && model.selectedConversation != null -> ConversationActionsScreen(
                                model.selectedConversation!!, model.apiClient()!!, model::closeConversationActions,
                                model::refreshConversations, model::onConversationDeleted,
                            )
                            model.archivesOpen -> TaskArchivesScreen(model.apiClient()!!, model::goBack, model::refreshTasks)
                            model.selectedTask != null -> TaskDetail(
                                model.selectedTask!!, model.taskActivity, model.detailBusy, model.sending,
                                model::closeDetail, { model.refreshTaskActivity(manual = true) },
                                model::sendTaskMessage, model::stopTaskChat, model::openActivityDetail,
                                model.apiClient()!!, { model.refreshTasks() }, { model.closeDetail(); model.refreshTasks() },
                                model.taskDetailTab, model::selectTaskDetailTab, model,
                            )
                            model.selectedConversation != null -> ConversationDetail(
                                model.selectedConversation!!, model.conversationActivity, model.detailBusy, model.sending,
                                model::closeDetail, model::refreshConversationActivity,
                                model::sendConversationMessage, model::stopConversation, model::openActivityDetail,
                                model::openConversationActions, model.apiClient()!!, model,
                            )
                            model.selectedFinding != null -> FindingDetailScreen(
                                model.selectedFinding!!, model.apiClient()!!, model::closeDetail, { model.refreshFindings() },
                            )
                            model.feature != null -> when (model.feature!!) {
                                FeatureDestination.DASHBOARD -> DashboardScreen(
                                    model.apiClient()!!, model::goBack, model::openTask, model::openFinding,
                                )
                                FeatureDestination.LLM -> LlmProfilesScreen(model.apiClient()!!, model::goBack)
                                FeatureDestination.SYSTEM_SETTINGS -> SystemSettingsScreen(model.apiClient()!!, model::goBack)
                                FeatureDestination.TRAFFIC, FeatureDestination.COMMANDS,
                                FeatureDestination.LLM_RECORDS, FeatureDestination.ASSETS,
                                FeatureDestination.SYNC, FeatureDestination.WORKSPACE ->
                                    OperationsScreen(model.feature!!, model.apiClient()!!, model::goBack)
                                FeatureDestination.AGENTS, FeatureDestination.MCP,
                                FeatureDestination.SKILLS, FeatureDestination.TOOLS,
                                FeatureDestination.NOTIFY, FeatureDestination.INTERCEPT,
                                FeatureDestination.ASSET_INTERCEPT, FeatureDestination.APPROVALS ->
                                    AdminResourceScreen(model.feature!!, model.apiClient()!!, model::goBack)
                                else -> FeatureExplorer(model.feature!!, model.apiClient()!!, model::goBack)
                            }
                            model.fullModeOpen -> Column {
                                Row(Modifier.fillMaxWidth().padding(start = 12.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = model::goBack) { Icon(Icons.Outlined.ArrowBack, "返回设置") }
                                }
                                Box(Modifier.weight(1f)) { FullModeMenu(model::openFeature) }
                            }
                            model.tab == HomeTab.TASKS -> HomeContent(
                                model.tasks, model.serverAddress, model.tasksBusy,
                                model::refreshTasks, model::openTask, model::openCreateTask,
                            )
                            model.tab == HomeTab.FINDINGS -> FindingsContent(
                                model.findings, model.findingsTotal, model.findingsBusy,
                                model.findingSeverity, model::selectFindingSeverity,
                                { model.refreshFindings() }, { model.refreshFindings(true) }, model::openFinding,
                            )
                            model.tab == HomeTab.CONVERSATIONS -> ConversationsContent(
                                model.conversations, model.conversationsBusy,
                                model::refreshConversations, model::openConversation, model::openCreateConversation,
                            )
                            else -> SettingsContent(model, model::openFullMode, model::openArchives)
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(horizontal = 16.dp).padding(bottom = if (model.signedIn &&
                    model.selectedTask == null && model.selectedConversation == null && model.selectedFinding == null &&
                    !model.creatingTask && !model.creatingConversation && !model.fullModeOpen && !model.archivesOpen) 92.dp else 18.dp))
            model.activityDetail?.let { detail ->
                val readable = remember(detail) { renderChatBody(detail) }
                AlertDialog(
                    onDismissRequest = model::closeActivityDetail,
                    title = { Text("完整内容") },
                    text = {
                        val chunkSize = 6_000
                        LazyColumn(Modifier.heightIn(max = 500.dp)) {
                            items((readable.length + chunkSize - 1) / chunkSize) { index ->
                                val start = index * chunkSize
                                Text(readable.substring(start, minOf(start + chunkSize, readable.length)),
                                    fontSize = 13.sp, lineHeight = 20.sp)
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = model::closeActivityDetail) { Text("关闭") } },
                )
            }
        }
    }
}

@Composable
private fun AppBackdrop(wallpaper: Bitmap?, dim: Float) {
    Box(Modifier.fillMaxSize().background(Cream)) {
        if (wallpaper != null) {
            Image(wallpaper.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Cream.copy(alpha = dim.coerceAtLeast(0.65f))))
        }
    }
}

@Composable
private fun BrandMark(size: Int = 42) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.16f).dp)).background(Ink),
        contentAlignment = Alignment.Center,
    ) {
        Text("A", color = Color.White, fontSize = (size * 0.55f).sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LoginScreen(savedAddress: String, busy: Boolean, onLogin: (String, String) -> Unit) {
    var address by rememberSaveable(savedAddress) { mutableStateOf(savedAddress) }
    var password by remember { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandMark(34)
            Spacer(Modifier.width(11.dp))
            Text("ARTEX", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.2.sp)
        }
        Spacer(Modifier.height(38.dp))
        Text("连接工作台", color = Ink, fontSize = 31.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        Text("输入服务器地址和管理员密码。", color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(36.dp))
        Text("服务器地址", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = address, onValueChange = { address = it }, singleLine = true,
            placeholder = { Text("例如 192.0.2.10:32451") },
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color.White, focusedContainerColor = Color.White),
        )
        Spacer(Modifier.height(19.dp))
        Text("管理员密码", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it }, singleLine = true,
            placeholder = { Text("输入密码") }, visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = Color.White, focusedContainerColor = Color.White),
        )
        Spacer(Modifier.height(26.dp))
        Button(
            onClick = { onLogin(address, password) }, enabled = !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp), shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Ink),
        ) {
            if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
            else Text("连接", fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(20.dp))
        Text("支持 IP:端口 或 HTTPS 地址。公网连接建议使用 HTTPS。", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun ContentPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = Color.White.copy(alpha = 0.93f),
        border = BorderStroke(1.dp, Line)) { content() }
}

@Composable
private fun BottomBar(current: HomeTab, onSelect: (HomeTab) -> Unit) {
    val options = listOf(
        Triple(HomeTab.TASKS, "任务", Icons.Outlined.Home),
        Triple(HomeTab.FINDINGS, "发现", Icons.Outlined.BugReport),
        Triple(HomeTab.CONVERSATIONS, "对话", Icons.Outlined.Chat),
        Triple(HomeTab.SETTINGS, "设置", Icons.Outlined.Settings),
    )
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 18.dp, end = 18.dp, bottom = 12.dp, top = 6.dp)) {
        Surface(shape = RoundedCornerShape(26.dp), color = Color.White.copy(alpha = 0.94f),
            shadowElevation = 10.dp, border = BorderStroke(1.dp, Color.White)) {
            Row(Modifier.fillMaxWidth().height(66.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                options.forEach { (tab, label, icon) ->
                    val selected = tab == current
                    Column(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(18.dp))
                            .clickable { onSelect(tab) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(icon, label, Modifier.size(22.dp), tint = if (selected) Moss else Muted)
                        Spacer(Modifier.height(3.dp))
                        Text(label, fontSize = 10.sp, color = if (selected) Moss else Muted,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeContent(
    tasks: List<Task>, server: String, busy: Boolean,
    onRefresh: () -> Unit, onOpenTask: (Task) -> Unit, onCreate: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("all") }
    val shown = remember(tasks, query, filter) {
        tasks.filter { task ->
            (filter == "all" || task.status == filter) &&
                (query.isBlank() || task.title.contains(query, true) || task.id.contains(query, true))
        }
            .sortedWith(compareByDescending<Task> { it.pinned }.thenByDescending { it.lastActivityUnix })
    }
    val running = tasks.count { it.status == "running" }
    val finished = tasks.count { it.status == "done" }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 20.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandMark(29)
                Spacer(Modifier.width(9.dp))
                Text("ARTEX", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.weight(1f))
                Surface(shape = CircleShape, color = Ink) {
                    IconButton(onClick = onCreate, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Outlined.Add, "新建任务", tint = Color.White, modifier = Modifier.size(21.dp))
                    }
                }
                Spacer(Modifier.width(7.dp))
                IconButton(onClick = onRefresh, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Outlined.Refresh, "刷新", tint = Muted, modifier = Modifier.size(19.dp))
                }
            }
        }
        item {
            Column(Modifier.padding(top = 21.dp, bottom = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("任务", color = Ink, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text(tasks.size.toString().padStart(2, '0'), color = Moss, fontSize = 29.sp, fontWeight = FontWeight.Light)
                }
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(6.dp).background(Moss, CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(server.removePrefix("https://").removePrefix("http://"), color = Muted, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("搜索任务编号或名称", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(19.dp)) },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = Color.White.copy(alpha = 0.92f),
                    focusedContainerColor = Color.White,
                    unfocusedBorderColor = Line, focusedBorderColor = Moss,
                ),
            )
        }
        item {
            Row(Modifier.fillMaxWidth().padding(top = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                listOf(Triple("all", "全部", tasks.size), Triple("running", "运行中", running),
                    Triple("done", "已完成", finished)).forEach { (value, label, count) ->
                    val selected = filter == value
                    Column(Modifier.weight(1f).clickable { filter = value }, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("$label  $count", Modifier.padding(vertical = 10.dp),
                            color = if (selected) Ink else Muted, fontSize = 13.sp,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
                        Box(Modifier.fillMaxWidth().height(2.dp).background(if (selected) Moss else Color.Transparent))
                    }
                }
            }
            HorizontalDivider(color = Line)
        }
        if (busy && tasks.isEmpty()) item { LoadingRow() }
        if (!busy && shown.isEmpty()) item {
            EmptyMessage(when {
                query.isNotBlank() -> "没有匹配的任务"
                filter == "running" -> "没有运行中的任务"
                filter == "done" -> "没有已完成的任务"
                else -> "还没有任务"
            })
        }
        items(shown, key = { it.id }) { task -> TaskCard(task) { onOpenTask(task) } }
    }
}

@Composable
private fun TaskCard(task: Task, onClick: () -> Unit) {
    val (statusLabel, statusColor) = statusInfo(task.status)
    Surface(Modifier.fillMaxWidth().padding(top = 8.dp).clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp), color = Color.White.copy(alpha = 0.93f),
        border = BorderStroke(1.dp, Line)) {
        Column(Modifier.padding(horizontal = 15.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(statusColor, CircleShape))
                Spacer(Modifier.width(7.dp))
                Text(statusLabel, color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                Spacer(Modifier.width(11.dp))
                Text("#${task.id}", color = Muted, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                if (task.pinned) Text("置顶", color = Muted, fontSize = 11.sp)
            }
            Spacer(Modifier.height(7.dp))
            Text(task.title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 22.sp)
            if (task.description.isNotBlank() && task.description != task.title) {
                Spacer(Modifier.height(4.dp))
                Text(task.description, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${task.findings} 个发现", color = if (task.findings > 0) Clay else Muted, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                Text(formatUnix(task.lastActivityUnix), color = Muted, fontSize = 11.sp)
                Spacer(Modifier.width(5.dp))
                Icon(Icons.Outlined.ArrowForward, null, Modifier.size(15.dp), tint = Muted)
            }
        }
    }
}

private fun statusInfo(status: String): Pair<String, Color> = when (status) {
    "running" -> "运行中" to Moss
    "done" -> "已完成" to Color(0xFF557D6C)
    "paused" -> "已暂停" to Color(0xFFA27948)
    "failed", "timeout" -> "需关注" to Clay
    "queued" -> "排队中" to Color(0xFF7C7FA0)
    else -> "待开始" to Muted
}

@Composable
private fun StatusPill(status: String) {
    val (label, tint) = statusInfo(status)
    Row(Modifier.clip(CircleShape).background(tint.copy(alpha = 0.11f)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).background(tint, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, color = tint, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable private fun LoadingRow() {
    Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(25.dp), color = Moss, strokeWidth = 2.dp)
    }
}

@Composable private fun EmptyMessage(message: String) {
    ContentPanel(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) {
            Text(message, color = Muted, fontSize = 14.sp)
        }
    }
}

private fun formatUnix(seconds: Long): String = if (seconds <= 0) "暂无动态" else
    SimpleDateFormat("MM/dd HH:mm", Locale.CHINA).format(Date(seconds * 1000))

@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "ARTEX Home")
@Composable
fun HomePreview() {
    MaterialTheme(colorScheme = ArtexColors) {
        Box(Modifier.fillMaxSize()) {
            AppBackdrop(null, 0.68f)
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    HomeContent(
                        tasks = listOf(
                            Task("63", "企业资产安全评估 · 第三阶段", "对授权范围进行持续测试", "running", 12, 1794552000, pinned = true),
                            Task("61", "Web 应用漏洞验证", "验证资产中发现的风险", "running", 8, 1794547200),
                            Task("58", "API 接口审计", "检查接口授权与敏感数据暴露", "done", 5, 1794490000),
                        ),
                        server = "192.0.2.10:32451", busy = false, onRefresh = {}, onOpenTask = {}, onCreate = {},
                    )
                }
                BottomBar(HomeTab.TASKS, {})
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "ARTEX Login")
@Composable
fun LoginPreview() {
    MaterialTheme(colorScheme = ArtexColors) {
        Box(Modifier.fillMaxSize()) {
            AppBackdrop(null, 0.68f)
            LoginScreen("", false) { _, _ -> }
        }
    }
}

@Preview(showBackground = true, widthDp = 393, heightDp = 852, name = "ARTEX Full Mode")
@Composable
fun FullModePreview() {
    MaterialTheme(colorScheme = ArtexColors) {
        Box(Modifier.fillMaxSize()) {
            AppBackdrop(null, 0.68f)
            FullModeMenu {}
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String, onRefresh: (() -> Unit)?, onAdd: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Ink, fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            Text(subtitle, color = Muted, fontSize = 12.sp)
        }
        if (onRefresh != null) {
            IconButton(onClick = onRefresh, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Refresh, "刷新", Modifier.size(19.dp), tint = Muted)
            }
        }
        if (onAdd != null) {
            IconButton(onClick = onAdd, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Add, "新建", Modifier.size(22.dp), tint = Moss)
            }
        }
    }
}

@Composable
private fun FindingsContent(
    findings: List<Finding>, total: Int, busy: Boolean,
    selectedSeverity: String, onSeverityChange: (String) -> Unit,
    onRefresh: () -> Unit, onMore: () -> Unit, onOpen: (Finding) -> Unit,
) {
    val severities = listOf("全部" to "all", "严重" to "critical", "高危" to "high", "中危" to "medium", "低危" to "low")
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 21.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        item { SectionHeader("发现", "已登记 $total 项发现", onRefresh) }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                severities.forEach { (label, value) ->
                    val selected = value == selectedSeverity
                    Surface(
                        shape = RoundedCornerShape(8.dp), color = if (selected) Ink else Color.White.copy(alpha = 0.92f),
                        border = BorderStroke(1.dp, if (selected) Ink else Line),
                        modifier = Modifier.clickable { onSeverityChange(value) },
                    ) {
                        Text(label, Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                            color = if (selected) Color.White else Muted, fontSize = 11.sp)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(3.dp)) }
        if (busy && findings.isEmpty()) item { LoadingRow() }
        if (!busy && findings.isEmpty()) item { EmptyMessage("暂无对应的发现") }
        items(findings, key = { it.id }) { finding -> FindingCard(finding) { onOpen(finding) } }
        if (findings.size < total) item {
            OutlinedButton(onClick = onMore, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)) { Text(if (busy) "加载中…" else "加载更多") }
        }
    }
}

@Composable
private fun FindingCard(finding: Finding, onClick: () -> Unit) {
    val tint = when (finding.severity) {
        "critical" -> Color(0xFFB54F4C)
        "high" -> Clay
        "medium" -> Color(0xFFA77D40)
        else -> Moss
    }
    val severityLabel = when (finding.severity) {
        "critical" -> "严重"
        "high" -> "高危"
        "medium" -> "中危"
        else -> "低危"
    }
    ContentPanel(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(tint, CircleShape))
                Spacer(Modifier.width(7.dp))
                Text(severityLabel, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(if (finding.taskId.isBlank()) "全局" else "任务 #${finding.taskId}", color = Muted, fontSize = 11.sp)
            }
            Spacer(Modifier.height(11.dp))
            Text(finding.title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (finding.summary.isNotBlank()) {
                Spacer(Modifier.height(7.dp))
                Text(finding.summary, color = Muted, fontSize = 12.sp, lineHeight = 18.sp,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ConversationsContent(
    conversations: List<Conversation>, busy: Boolean,
    onRefresh: () -> Unit, onOpen: (Conversation) -> Unit, onCreate: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 21.dp, bottom = 20.dp)) {
        item { SectionHeader("对话", "独立 Agent 会话", onRefresh, onCreate) }
        if (busy && conversations.isEmpty()) item { LoadingRow() }
        if (!busy && conversations.isEmpty()) item { EmptyMessage("还没有独立对话") }
        items(conversations, key = { it.id }) { conversation ->
            Column(Modifier.fillMaxWidth().clickable { onOpen(conversation) }) {
                Row(Modifier.padding(vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Chat, null, tint = Moss, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(conversation.title.ifBlank { "未命名对话" }, color = Ink, fontSize = 15.sp,
                            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Text(if (conversation.running) "正在回复" else conversation.agentKey,
                            color = if (conversation.running) Moss else Muted, fontSize = 11.sp)
                    }
                    Icon(Icons.Outlined.ArrowForward, null, tint = Muted, modifier = Modifier.size(17.dp))
                }
                HorizontalDivider(color = Line)
            }
        }
    }
}

@Composable
private fun SettingsContent(model: ArtexViewModel, onOpenFullMode: () -> Unit, onOpenArchives: () -> Unit) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) model.chooseWallpaper(uri)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 21.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionHeader("设置", "外观与连接", null) }
        item {
            ContentPanel(Modifier.fillMaxWidth().clickable(onClick = onOpenFullMode)) {
                Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("完整功能", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        Text("仪表盘、资产、流量及系统管理", color = Muted, fontSize = 12.sp)
                    }
                    Icon(Icons.Outlined.ArrowForward, null, tint = Moss, modifier = Modifier.size(18.dp))
                }
            }
        }
        item {
            ContentPanel(Modifier.fillMaxWidth().clickable(onClick = onOpenArchives)) {
                Row(Modifier.padding(17.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("任务归档", Modifier.weight(1f), color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Icon(Icons.Outlined.ArrowForward, null, tint = Moss, modifier = Modifier.size(18.dp))
                }
            }
        }
        item {
            ContentPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(17.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Photo, null, tint = Moss, modifier = Modifier.size(21.dp))
                        Spacer(Modifier.width(9.dp))
                        Text("自定义背景", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(9.dp))
                    Text("选一张照片作为工作台背景，内容会保持清晰可读。", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                    Spacer(Modifier.height(17.dp))
                    Button(onClick = { picker.launch("image/*") }, shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Ink)) {
                        Icon(Icons.Outlined.Photo, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("选择背景")
                    }
                    if (model.wallpaper != null) {
                        Spacer(Modifier.height(14.dp))
                        Text("背景遮罩  ${(model.wallpaperDim * 100).toInt()}%", color = Muted, fontSize = 12.sp)
                        Slider(value = model.wallpaperDim.coerceIn(0.65f, 0.95f), onValueChange = model::updateWallpaperDim,
                            valueRange = 0.65f..0.95f)
                        TextButton(onClick = model::removeWallpaper) {
                            Icon(Icons.Outlined.Delete, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("恢复默认背景")
                        }
                    }
                }
            }
        }
        item {
            ContentPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(17.dp)) {
                    Text("当前连接", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(9.dp))
                    Text(model.serverAddress, color = Moss, fontSize = 13.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(17.dp))
                    TextButton(onClick = model::logout) {
                        Icon(Icons.Outlined.Logout, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("退出登录 / 切换服务器")
                    }
                }
            }
        }
        item { Text("ARTEX Android  ·  0.2", color = Muted, fontSize = 10.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
    }
}

@Composable
private fun TaskDetail(
    task: Task, activities: List<Activity>, busy: Boolean, sending: Boolean,
    onBack: () -> Unit, onRefresh: () -> Unit, onSend: (String, List<ChatAttachment>, () -> Unit) -> Unit, onStop: () -> Unit,
    onOpenActivity: (Activity) -> Unit,
    api: ArtexApi, onTaskChanged: () -> Unit, onTaskClosed: () -> Unit,
    selectedTab: String, onTabChange: (String) -> Unit, model: ArtexViewModel,
) {
    Column(Modifier.fillMaxSize()) {
        DetailTopBar("任务 #${task.id}", onBack, onRefresh)
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(task.title, color = Ink, fontSize = 24.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 3, lineHeight = 31.sp)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(task.status)
                Spacer(Modifier.width(10.dp))
                Text("${task.findings} 个发现", color = Muted, fontSize = 11.sp)
            }
            Spacer(Modifier.height(10.dp))
            TaskOperations(task, api, onTaskChanged, onTaskClosed)
            Spacer(Modifier.height(10.dp))
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("chat" to "主对话", "agents" to "Agent 状态", "details" to "任务详情").forEach { (value, label) ->
                Column(Modifier.weight(1f).clickable { onTabChange(value) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, Modifier.padding(vertical = 10.dp), color = if (selectedTab == value) Ink else Muted,
                        fontSize = 13.sp, fontWeight = if (selectedTab == value) FontWeight.SemiBold else FontWeight.Normal)
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (selectedTab == value) Moss else Color.Transparent))
                }
            }
        }
        HorizontalDivider(color = Line)
        when (selectedTab) {
            "chat" -> {
                if (model.mainSessions.size > 1) MainSessionSelector(
                    model.mainSessions, model.selectedMainSeg, model.currentMainSeg, model::selectMainSession)
                key(model.selectedMainSeg) {
                    ActivityList(activities, busy, onOpenActivity, model, Modifier.weight(1f),
                        hasOlder = model.taskHistoryHasMore, loadingOlder = model.taskHistoryLoadingOlder,
                        onLoadOlder = model::loadOlderTaskActivity)
                }
                if (model.selectedMainSeg == model.currentMainSeg) {
                    ChatComposer(api, ChatUploadScope.TASK, task.id, sending, onSend, onStop)
                } else {
                    Surface(color = Color.White.copy(alpha = 0.9f)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically) {
                            Text("旧会话仅供查看", Modifier.weight(1f), color = Muted, fontSize = 12.sp)
                            TextButton(onClick = { model.selectMainSession(model.currentMainSeg) }) { Text("返回当前会话") }
                        }
                    }
                }
            }
            "agents" -> Box(Modifier.weight(1f)) { TaskAgentsScreen(task, api) }
            else -> Box(Modifier.weight(1f)) { TaskDetailExtras(task, api) }
        }
    }
}

@Composable
private fun MainSessionSelector(
    sessions: List<MainSession>, selected: Int, current: Int, onSelect: (Int) -> Unit,
) {
    LazyRow(contentPadding = PaddingValues(horizontal = 17.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        items(sessions, key = { it.seq }) { session ->
            val label = if (session.seq == current) "当前会话" else if (session.seq == 0) "初始会话" else "会话 ${session.seq}"
            FilterChip(selected = session.seq == selected, onClick = { onSelect(session.seq) },
                label = { Text(label, fontSize = 11.sp) })
        }
    }
}

@Composable
private fun ConversationDetail(
    conversation: Conversation, activities: List<Activity>, busy: Boolean, sending: Boolean,
    onBack: () -> Unit, onRefresh: () -> Unit, onSend: (String, List<ChatAttachment>, () -> Unit) -> Unit, onStop: () -> Unit,
    onOpenActivity: (Activity) -> Unit,
    onManage: () -> Unit, api: ArtexApi, model: ArtexViewModel,
) {
    Column(Modifier.fillMaxSize()) {
        DetailTopBar("独立对话", onBack, onRefresh, onManage)
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(conversation.title.ifBlank { "未命名对话" }, color = Ink, fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 2)
            Spacer(Modifier.height(4.dp))
            Text(conversation.agentKey, color = Muted, fontSize = 12.sp)
        }
        ActivityList(activities, busy, onOpenActivity, model, Modifier.weight(1f))
        ChatComposer(api, ChatUploadScope.SESSION, "conv-${conversation.id}", sending, onSend, onStop)
    }
}

@Composable
private fun DetailTopBar(label: String, onBack: () -> Unit, onRefresh: () -> Unit, onManage: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回", tint = Ink) }
        Text(label, Modifier.weight(1f), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        if (onManage != null) IconButton(onClick = onManage) { Icon(Icons.Outlined.MoreVert, "对话设置", tint = Ink) }
        IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, "刷新", tint = Moss) }
    }
}

/** The history endpoint intentionally returns short summaries; visible turns fetch their full detail. */
@Composable
private fun ActivityList(
    activities: List<Activity>, busy: Boolean, onOpen: (Activity) -> Unit,
    model: ArtexViewModel, modifier: Modifier = Modifier,
    hasOlder: Boolean = false, loadingOlder: Boolean = false, onLoadOlder: (() -> Unit)? = null,
) {
    val displayActivities = remember(activities) { activities.filter { it.kind != "usage" } }
    val listState = rememberLazyListState()
    var previousFirst by remember { mutableLongStateOf(0L) }
    var previousLast by remember { mutableLongStateOf(0L) }
    LaunchedEffect(displayActivities.firstOrNull()?.seq, displayActivities.lastOrNull()?.seq, displayActivities.size) {
        val first = displayActivities.firstOrNull()?.seq ?: 0L
        val last = displayActivities.lastOrNull()?.seq ?: 0L
        val initial = previousLast == 0L
        val prepended = previousFirst != 0L && first < previousFirst && last == previousLast
        val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        val nearEnd = lastVisible >= listState.layoutInfo.totalItemsCount - 3
        previousFirst = first
        previousLast = last
        if (displayActivities.isNotEmpty() && !prepended && (initial || (last > 0 && nearEnd))) {
            listState.scrollToItem(displayActivities.lastIndex + if (hasOlder || loadingOlder) 1 else 0)
        }
    }
    LazyColumn(modifier, state = listState,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (hasOlder || loadingOlder) item(key = "older") {
            TextButton(onClick = { onLoadOlder?.invoke() }, enabled = hasOlder && !loadingOlder,
                modifier = Modifier.fillMaxWidth()) {
                if (loadingOlder) CircularProgressIndicator(Modifier.size(15.dp), color = Moss, strokeWidth = 2.dp)
                else Text("加载更早记录")
            }
        }
        if (busy && displayActivities.isEmpty()) item { LoadingRow() }
        if (!busy && displayActivities.isEmpty() && !hasOlder) item { EmptyMessage("暂无消息") }
        items(displayActivities, key = { it.seq }) { activity ->
            val rowScope = rememberCoroutineScope()
            val fromUser = activity.kind == "user"
            val full = model.inlineActivityText(activity)
            val needsFull = activity.kind in setOf("user", "result", "text", "thinking", "tool_use", "tool_result") &&
                activity.seq > 0
            val fullError = model.inlineActivityError(activity.seq)
            LaunchedEffect(activity.seq, model) {
                if (needsFull && full == null) model.loadInlineActivityDetail(activity)
            }
            val shown = if (fromUser) renderChatBody(full ?: activity.summary) else full ?: activity.summary
            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (fromUser) Arrangement.End else Arrangement.Start) {
                Surface(
                    modifier = Modifier.fillMaxWidth(if (fromUser) 0.86f else 0.94f).clickable { onOpen(activity) },
                    color = if (fromUser) Ink else Color.White.copy(alpha = 0.86f),
                    shape = RoundedCornerShape(18.dp),
                    border = if (fromUser) null else BorderStroke(1.dp, Color.White),
                ) {
                    Column(Modifier.padding(15.dp)) {
                        Text(if (fromUser) "你" else activity.worker.ifBlank { "Agent" },
                            color = if (fromUser) Color(0xFFBFD4C5) else Moss,
                            fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        Text((if (shown.length > 3_000) shown.take(3_000) + "…" else shown).ifBlank { "（无内容）" },
                            color = if (fromUser) Color.White else Ink,
                            fontSize = 13.sp, lineHeight = 20.sp)
                        if (shown.length > 3_000) {
                            Spacer(Modifier.height(7.dp))
                            Text("正文较长 · 点按阅读全文", color = if (fromUser) Color(0xFFBFD4C5) else Moss,
                                fontSize = 11.sp)
                        }
                        if (needsFull && full == null) {
                            Spacer(Modifier.height(7.dp))
                            TextButton(onClick = { rowScope.launch { model.loadInlineActivityDetail(activity) } },
                                contentPadding = PaddingValues(0.dp)) {
                                Text(if (fullError) "全文读取失败 · 点击重试" else "正在读取全文…",
                                    color = if (fromUser) Color(0xFFBFD4C5) else Moss, fontSize = 11.sp)
                            }
                        } else if (!needsFull) {
                            Spacer(Modifier.height(7.dp))
                            Text("点按查看完整记录", color = if (fromUser) Color(0xFFBFD4C5) else Muted,
                                fontSize = 10.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun renderChatBody(raw: String): String {
    val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return raw
    val attachments = obj.optJSONArray("attachments") ?: return raw
    if (!obj.has("text")) return raw
    val names = (0 until attachments.length()).mapNotNull { attachments.optJSONObject(it)?.optString("name") }
        .filter { it.isNotBlank() }
    return buildString {
        append(obj.optString("text"))
        if (names.isNotEmpty()) {
            if (isNotEmpty()) append("\n")
            append(names.joinToString("\n") { "附件：$it" })
        }
    }
}

@Composable
private fun ChatComposer(
    api: ArtexApi, uploadScope: ChatUploadScope, uploadId: String, sending: Boolean,
    onSend: (String, List<ChatAttachment>, () -> Unit) -> Unit, onStop: () -> Unit,
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var draft by rememberSaveable(uploadId) { mutableStateOf("") }
    var attachments by remember(api, uploadId) { mutableStateOf<List<ChatAttachment>>(emptyList()) }
    var uploading by remember(api, uploadId) { mutableStateOf(false) }
    var uploadError by remember(api, uploadId) { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) coroutineScope.launch {
            uploading = true
            uploadError = ""
            try { attachments = attachments + api.uploadChatAttachments(context, uris, uploadScope, uploadId) }
            catch (e: Exception) { uploadError = e.message ?: "上传附件失败" }
            finally { uploading = false }
        }
    }
    Surface(color = Color.White.copy(alpha = 0.88f), shadowElevation = 8.dp) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp)) {
            if (attachments.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    attachments.forEachIndexed { index, attachment ->
                        TextButton(onClick = { attachments = attachments.filterIndexed { i, _ -> i != index } }) {
                            Text("${attachment.name} ×", maxLines = 1, color = Moss, fontSize = 11.sp)
                        }
                    }
                }
            }
            if (uploadError.isNotBlank()) Text(uploadError, color = Clay, fontSize = 11.sp)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                IconButton(onClick = onStop, modifier = Modifier.size(44.dp)) {
                    Icon(Icons.Outlined.Stop, "停止执行", tint = Clay)
                }
                IconButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !uploading,
                    modifier = Modifier.size(44.dp)) {
                    if (uploading) CircularProgressIndicator(Modifier.size(18.dp), color = Moss, strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.AttachFile, "添加附件", Modifier.size(20.dp), tint = Moss)
                }
                OutlinedTextField(
                    value = draft, onValueChange = { draft = it },
                    placeholder = { Text("发送消息…", fontSize = 13.sp) },
                    modifier = Modifier.weight(1f), shape = RoundedCornerShape(18.dp), maxLines = 4,
                )
                Spacer(Modifier.width(7.dp))
                FilledIconButton(
                    onClick = {
                        val text = draft.trim()
                        if (text.isNotEmpty() || attachments.isNotEmpty()) onSend(text, attachments) {
                            draft = ""
                            attachments = emptyList()
                        }
                    },
                    enabled = (draft.isNotBlank() || attachments.isNotEmpty()) && !sending && !uploading,
                    modifier = Modifier.size(44.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = Ink),
                ) {
                    if (sending) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                    else Icon(Icons.Outlined.Send, "发送", Modifier.size(19.dp))
                }
            }
        }
    }
}
