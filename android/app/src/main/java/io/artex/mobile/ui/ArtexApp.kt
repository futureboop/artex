package io.artex.mobile.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ArrowForward
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Logout
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.artex.mobile.data.Activity
import io.artex.mobile.data.Conversation
import io.artex.mobile.data.Finding
import io.artex.mobile.data.Task
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Ink = Color(0xFF172B29)
private val Moss = Color(0xFF3F6658)
private val Olive = Color(0xFF879B80)
private val Cream = Color(0xFFF6F4EF)
private val Sand = Color(0xFFE8E0D0)
private val Muted = Color(0xFF74827B)
private val Line = Color(0xFFE0E6DE)
private val Clay = Color(0xFFBB765F)

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
        BackHandler(enabled = model.selectedTask != null || model.selectedConversation != null) {
            model.closeDetail()
        }
        LaunchedEffect(model.signedIn, model.tab, model.selectedTask?.id, model.selectedConversation?.id) {
            while (model.signedIn) {
                delay(if (model.selectedTask != null || model.selectedConversation != null) 6_000 else 20_000)
                when {
                    model.selectedTask != null -> model.refreshTaskActivity()
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
                    snackbarHost = { SnackbarHost(snackbar) },
                    bottomBar = {
                        if (model.selectedTask == null && model.selectedConversation == null) {
                            BottomBar(model.tab, model::selectTab)
                        }
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).statusBarsPadding()) {
                        when {
                            model.selectedTask != null -> TaskDetail(
                                model.selectedTask!!, model.taskActivity, model.detailBusy, model.sending,
                                model::closeDetail, model::refreshTaskActivity,
                                model::sendTaskMessage, model::stopTaskChat, model::openActivityDetail,
                            )
                            model.selectedConversation != null -> ConversationDetail(
                                model.selectedConversation!!, model.conversationActivity, model.detailBusy, model.sending,
                                model::closeDetail, model::refreshConversationActivity,
                                model::sendConversationMessage, model::stopConversation, model::openActivityDetail,
                            )
                            model.tab == HomeTab.TASKS -> HomeContent(
                                model.tasks, model.serverAddress, model.tasksBusy,
                                model::refreshTasks, model::openTask,
                            )
                            model.tab == HomeTab.FINDINGS -> FindingsContent(
                                model.findings, model.findingsTotal, model.findingsBusy,
                                model.findingSeverity, model::selectFindingSeverity,
                                { model.refreshFindings() }, { model.refreshFindings(true) },
                            )
                            model.tab == HomeTab.CONVERSATIONS -> ConversationsContent(
                                model.conversations, model.conversationsBusy,
                                model::refreshConversations, model::openConversation,
                            )
                            else -> SettingsContent(model)
                        }
                    }
                }
            }
            model.activityDetail?.let { detail ->
                AlertDialog(
                    onDismissRequest = model::closeActivityDetail,
                    title = { Text("完整内容") },
                    text = {
                        Column(Modifier.heightIn(max = 500.dp).verticalScroll(rememberScrollState())) {
                            Text(detail, fontSize = 13.sp, lineHeight = 20.sp)
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
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Cream, Color(0xFFE7EEE8), Color(0xFFF7F1E8))))) {
        if (wallpaper != null) {
            Image(wallpaper.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(Color(0xFFF8F7F2).copy(alpha = dim)))
        } else {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(Color(0xFFDDE8DC).copy(alpha = 0.48f), radius = size.width * 0.53f,
                    center = androidx.compose.ui.geometry.Offset(size.width * 1.02f, size.height * 0.08f))
                drawCircle(Color(0xFFF1E4D2).copy(alpha = 0.55f), radius = size.width * 0.44f,
                    center = androidx.compose.ui.geometry.Offset(-size.width * 0.10f, size.height * 0.79f))
            }
        }
    }
}

@Composable
private fun BrandMark(size: Int = 42) {
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.31f).dp)).background(Ink),
        contentAlignment = Alignment.Center,
    ) {
        Text("A", color = Color(0xFFE9DCC4), fontSize = (size * 0.58f).sp, fontWeight = FontWeight.SemiBold)
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
        BrandMark(58)
        Spacer(Modifier.height(28.dp))
        Text("ARTEX  /  MOBILE", color = Moss, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Spacer(Modifier.height(12.dp))
        Text("你的工作台，\n随时在手边。", color = Ink, fontSize = 35.sp, lineHeight = 44.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Text("连接自己的服务器，查看任务、发现与对话。", color = Muted, fontSize = 14.sp)
        Spacer(Modifier.height(42.dp))
        GlassCard {
            Column(Modifier.padding(22.dp)) {
                Text("连接服务器", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(20.dp))
                Text("服务器地址", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(7.dp))
                OutlinedTextField(
                    value = address, onValueChange = { address = it }, singleLine = true,
                    placeholder = { Text("例如 192.0.2.10:32451") },
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                Spacer(Modifier.height(17.dp))
                Text("管理员密码", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(7.dp))
                OutlinedTextField(
                    value = password, onValueChange = { password = it }, singleLine = true,
                    placeholder = { Text("输入密码") }, visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                )
                Spacer(Modifier.height(23.dp))
                Button(
                    onClick = { onLogin(address, password) }, enabled = !busy,
                    modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Ink),
                ) {
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                    else { Text("进入工作台", fontWeight = FontWeight.SemiBold); Spacer(Modifier.width(8.dp)); Icon(Icons.Outlined.ArrowForward, null, Modifier.size(18.dp)) }
                }
            }
        }
        Spacer(Modifier.height(18.dp))
        Text("支持 IP:端口 或 HTTPS 地址。公网连接建议使用 HTTPS。", color = Muted, fontSize = 11.sp, lineHeight = 17.sp)
    }
}

@Composable
private fun GlassCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier, shape = RoundedCornerShape(24.dp), color = Color.White.copy(alpha = 0.82f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.85f)), shadowElevation = 2.dp) { content() }
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
    onRefresh: () -> Unit, onOpenTask: (Task) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val shown = remember(tasks, query) {
        tasks.filter { query.isBlank() || it.title.contains(query, true) || it.id.contains(query, true) }
            .sortedWith(compareByDescending<Task> { it.pinned }.thenByDescending { it.lastActivityUnix })
    }
    val running = tasks.count { it.status == "running" }
    val finished = tasks.count { it.status == "done" }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BrandMark()
                Spacer(Modifier.width(11.dp))
                Column {
                    Text("ARTEX", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Text("FIELD CONSOLE", color = Muted, fontSize = 9.sp, letterSpacing = 1.8.sp)
                }
                Spacer(Modifier.weight(1f))
                Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.8f), border = BorderStroke(1.dp, Line)) {
                    Box(Modifier.size(42.dp).clickable { onRefresh() }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Refresh, "刷新", tint = Moss, modifier = Modifier.size(19.dp))
                    }
                }
            }
        }
        item { Spacer(Modifier.height(13.dp)) }
        item {
            Column {
                Text(today(), color = Muted, fontSize = 12.sp, letterSpacing = 0.6.sp)
                Spacer(Modifier.height(7.dp))
                Text("掌握每一步进展。", color = Ink, fontSize = 29.sp, lineHeight = 36.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                Text("${server.removePrefix("https://").removePrefix("http://")}  ·  已连接", color = Moss, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        item { Spacer(Modifier.height(4.dp)) }
        item { OverviewHero(running, tasks.size, finished) }
        item { Spacer(Modifier.height(9.dp)) }
        item {
            Row(verticalAlignment = Alignment.Bottom) {
                Column {
                    Text("进行中的工作", color = Ink, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                    Text("最近更新的任务", color = Muted, fontSize = 12.sp)
                }
                Spacer(Modifier.weight(1f))
                Text("${tasks.size} 项", color = Moss, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("搜索任务编号或名称", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Outlined.Search, null, Modifier.size(19.dp)) },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = Color.White.copy(alpha = 0.66f),
                    focusedContainerColor = Color.White.copy(alpha = 0.86f),
                    unfocusedBorderColor = Color.White, focusedBorderColor = Olive,
                ),
            )
        }
        if (busy && tasks.isEmpty()) item { LoadingRow() }
        if (!busy && shown.isEmpty()) item { EmptyMessage(if (query.isBlank()) "还没有任务" else "没有匹配的任务") }
        items(shown, key = { it.id }) { task -> TaskCard(task) { onOpenTask(task) } }
    }
}

@Composable
private fun OverviewHero(running: Int, total: Int, finished: Int) {
    Box(Modifier.fillMaxWidth().height(180.dp).clip(RoundedCornerShape(28.dp))
        .background(Brush.linearGradient(listOf(Ink, Color(0xFF35584B))))) {
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(Color.White.copy(alpha = 0.055f), size.width * 0.45f,
                androidx.compose.ui.geometry.Offset(size.width * 0.98f, size.height * 0.02f))
            drawCircle(Color.White.copy(alpha = 0.055f), size.width * 0.32f,
                androidx.compose.ui.geometry.Offset(size.width * 0.99f, size.height * 0.02f))
        }
        Column(Modifier.fillMaxSize().padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).background(Color(0xFFB5D6AA), CircleShape))
                Spacer(Modifier.width(8.dp))
                Text("LIVE OVERVIEW", color = Color(0xFFDDEBE1), fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.6.sp)
            }
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                HeroNumber("$running", "正在运行", Modifier.weight(1f))
                Box(Modifier.width(1.dp).height(43.dp).background(Color.White.copy(alpha = 0.17f)))
                HeroNumber("$total", "全部任务", Modifier.weight(1f))
                Box(Modifier.width(1.dp).height(43.dp).background(Color.White.copy(alpha = 0.17f)))
                HeroNumber("$finished", "已完成", Modifier.weight(1f))
            }
        }
    }
}

@Composable private fun HeroNumber(value: String, label: String, modifier: Modifier) {
    Column(modifier.padding(start = 11.dp)) {
        Text(value, color = Color.White, fontSize = 29.sp, fontWeight = FontWeight.SemiBold, lineHeight = 31.sp)
        Spacer(Modifier.height(6.dp))
        Text(label, color = Color(0xFFC8D7CD), fontSize = 11.sp)
    }
}

@Composable
private fun TaskCard(task: Task, onClick: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusPill(task.status)
                Spacer(Modifier.weight(1f))
                Text("#${task.id}", color = Muted, fontSize = 12.sp)
            }
            Spacer(Modifier.height(12.dp))
            Text(task.title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 22.sp)
            if (task.description.isNotBlank() && task.description != task.title) {
                Spacer(Modifier.height(5.dp))
                Text(task.description, color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = Line.copy(alpha = 0.7f))
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.BugReport, null, Modifier.size(15.dp), tint = Clay)
                Spacer(Modifier.width(5.dp))
                Text("${task.findings} 个发现", color = Muted, fontSize = 11.sp)
                Spacer(Modifier.weight(1f))
                Text(formatUnix(task.lastActivityUnix), color = Muted, fontSize = 11.sp)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Outlined.ArrowForward, null, Modifier.size(15.dp), tint = Moss)
            }
        }
    }
}

@Composable
private fun StatusPill(status: String) {
    val (label, tint) = when (status) {
        "running" -> "运行中" to Moss
        "done" -> "已完成" to Color(0xFF557D6C)
        "paused" -> "已暂停" to Color(0xFFA27948)
        "failed", "timeout" -> "需关注" to Clay
        "queued" -> "排队中" to Color(0xFF7C7FA0)
        else -> "待开始" to Muted
    }
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
    GlassCard(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) {
            Text(message, color = Muted, fontSize = 14.sp)
        }
    }
}

private fun today(): String = SimpleDateFormat("yyyy年M月d日 · EEEE", Locale.CHINA).format(Date())
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
                        server = "192.0.2.10:32451", busy = false, onRefresh = {}, onOpenTask = {},
                    )
                }
                BottomBar(HomeTab.TASKS, {})
            }
        }
    }
}

@Composable
private fun SectionHeader(eyebrow: String, title: String, subtitle: String, onRefresh: (() -> Unit)?) {
    Row(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(eyebrow, color = Moss, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Spacer(Modifier.height(7.dp))
            Text(title, color = Ink, fontSize = 29.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(5.dp))
            Text(subtitle, color = Muted, fontSize = 12.sp)
        }
        if (onRefresh != null) {
            Surface(shape = CircleShape, color = Color.White.copy(alpha = 0.86f), border = BorderStroke(1.dp, Line)) {
                Box(Modifier.size(42.dp).clickable { onRefresh() }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Refresh, "刷新", Modifier.size(18.dp), tint = Moss)
                }
            }
        }
    }
}

@Composable
private fun FindingsContent(
    findings: List<Finding>, total: Int, busy: Boolean,
    selectedSeverity: String, onSeverityChange: (String) -> Unit,
    onRefresh: () -> Unit, onMore: () -> Unit,
) {
    val severities = listOf("全部" to "all", "严重" to "critical", "高危" to "high", "中危" to "medium", "低危" to "low")
    LazyColumn(
        Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { SectionHeader("SECURITY FINDINGS", "发现", "已登记 $total 项发现", onRefresh) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                severities.forEach { (label, value) ->
                    val selected = value == selectedSeverity
                    Surface(
                        shape = CircleShape, color = if (selected) Ink else Color.White.copy(alpha = 0.82f),
                        border = BorderStroke(1.dp, if (selected) Ink else Line),
                        modifier = Modifier.clickable { onSeverityChange(value) },
                    ) {
                        Text(label, Modifier.padding(horizontal = 13.dp, vertical = 8.dp),
                            color = if (selected) Color.White else Muted, fontSize = 11.sp)
                    }
                }
            }
        }
        if (busy && findings.isEmpty()) item { LoadingRow() }
        if (!busy && findings.isEmpty()) item { EmptyMessage("暂无对应的发现") }
        items(findings, key = { it.id }) { finding -> FindingCard(finding) }
        if (findings.size < total) item {
            OutlinedButton(onClick = onMore, enabled = !busy, modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)) { Text(if (busy) "加载中…" else "加载更多") }
        }
    }
}

@Composable
private fun FindingCard(finding: Finding) {
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
    GlassCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp)) {
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
    onRefresh: () -> Unit, onOpen: (Conversation) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { SectionHeader("AGENT CONVERSATIONS", "对话", "与 Agent 保持联系", onRefresh) }
        if (busy && conversations.isEmpty()) item { LoadingRow() }
        if (!busy && conversations.isEmpty()) item { EmptyMessage("还没有独立对话") }
        items(conversations, key = { it.id }) { conversation ->
            GlassCard(Modifier.fillMaxWidth().clickable { onOpen(conversation) }) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(43.dp).background(Color(0xFFE5EEE5), RoundedCornerShape(14.dp)),
                        contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Chat, null, tint = Moss, modifier = Modifier.size(21.dp))
                    }
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text(conversation.title.ifBlank { "未命名对话" }, color = Ink, fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Text(if (conversation.running) "正在回复" else conversation.agentKey,
                            color = if (conversation.running) Moss else Muted, fontSize = 11.sp)
                    }
                    Icon(Icons.Outlined.ArrowForward, null, tint = Moss, modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

@Composable
private fun SettingsContent(model: ArtexViewModel) {
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) model.chooseWallpaper(uri)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(15.dp)) {
        item { SectionHeader("PERSONAL SPACE", "设置", "把工作台调整成喜欢的样子", null) }
        item {
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Photo, null, tint = Moss, modifier = Modifier.size(21.dp))
                        Spacer(Modifier.width(9.dp))
                        Text("自定义背景", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(9.dp))
                    Text("选一张照片作为工作台背景，内容会保持清晰可读。", color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
                    Spacer(Modifier.height(17.dp))
                    Button(onClick = { picker.launch("image/*") }, shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Ink)) {
                        Icon(Icons.Outlined.Photo, null, Modifier.size(17.dp))
                        Spacer(Modifier.width(7.dp))
                        Text("选择背景")
                    }
                    if (model.wallpaper != null) {
                        Spacer(Modifier.height(14.dp))
                        Text("背景遮罩  ${(model.wallpaperDim * 100).toInt()}%", color = Muted, fontSize = 12.sp)
                        Slider(value = model.wallpaperDim, onValueChange = model::updateWallpaperDim,
                            valueRange = 0.25f..0.9f)
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
            GlassCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
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
        item { Text("ARTEX MOBILE  ·  0.1", color = Muted, fontSize = 10.sp, letterSpacing = 1.2.sp,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
    }
}

@Composable
private fun TaskDetail(
    task: Task, activities: List<Activity>, busy: Boolean, sending: Boolean,
    onBack: () -> Unit, onRefresh: () -> Unit, onSend: (String, () -> Unit) -> Unit, onStop: () -> Unit,
    onOpenActivity: (Activity) -> Unit,
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
            Spacer(Modifier.height(14.dp))
            Text("主 Agent 对话", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        ActivityList(activities, busy, onOpenActivity, Modifier.weight(1f))
        ChatComposer(sending, onSend, onStop)
    }
}

@Composable
private fun ConversationDetail(
    conversation: Conversation, activities: List<Activity>, busy: Boolean, sending: Boolean,
    onBack: () -> Unit, onRefresh: () -> Unit, onSend: (String, () -> Unit) -> Unit, onStop: () -> Unit,
    onOpenActivity: (Activity) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        DetailTopBar("独立对话", onBack, onRefresh)
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(conversation.title.ifBlank { "未命名对话" }, color = Ink, fontSize = 24.sp,
                fontWeight = FontWeight.SemiBold, maxLines = 2)
            Spacer(Modifier.height(4.dp))
            Text(conversation.agentKey, color = Muted, fontSize = 12.sp)
        }
        ActivityList(activities, busy, onOpenActivity, Modifier.weight(1f))
        ChatComposer(sending, onSend, onStop)
    }
}

@Composable
private fun DetailTopBar(label: String, onBack: () -> Unit, onRefresh: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回", tint = Ink) }
        Text(label, Modifier.weight(1f), color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        IconButton(onClick = onRefresh) { Icon(Icons.Outlined.Refresh, "刷新", tint = Moss) }
    }
}

@Composable
private fun ActivityList(activities: List<Activity>, busy: Boolean, onOpen: (Activity) -> Unit, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(activities.size) {
        if (activities.isNotEmpty()) listState.scrollToItem(activities.lastIndex)
    }
    LazyColumn(modifier, state = listState,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (busy && activities.isEmpty()) item { LoadingRow() }
        if (!busy && activities.isEmpty()) item { EmptyMessage("暂无消息") }
        items(activities, key = { it.seq }) { activity ->
            val fromUser = activity.kind == "user"
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
                        Text(activity.summary.ifBlank { "（无摘要）" },
                            color = if (fromUser) Color.White else Ink,
                            fontSize = 13.sp, lineHeight = 20.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatComposer(sending: Boolean, onSend: (String, () -> Unit) -> Unit, onStop: () -> Unit) {
    var draft by rememberSaveable { mutableStateOf("") }
    Surface(color = Color.White.copy(alpha = 0.88f), shadowElevation = 8.dp) {
        Row(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = onStop, modifier = Modifier.size(44.dp)) {
                Icon(Icons.Outlined.Stop, "停止执行", tint = Clay)
            }
            OutlinedTextField(
                value = draft, onValueChange = { draft = it },
                placeholder = { Text("发送消息…", fontSize = 13.sp) },
                modifier = Modifier.weight(1f), shape = RoundedCornerShape(18.dp),
                maxLines = 4,
            )
            Spacer(Modifier.width(7.dp))
            FilledIconButton(
                onClick = { val text = draft.trim(); if (text.isNotEmpty()) onSend(text) { draft = "" } },
                enabled = draft.isNotBlank() && !sending,
                modifier = Modifier.size(44.dp),
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = Ink),
            ) {
                if (sending) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                else Icon(Icons.Outlined.Send, "发送", Modifier.size(19.dp))
            }
        }
    }
}
