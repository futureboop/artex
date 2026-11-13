package io.artex.mobile.ui

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.artex.mobile.data.Activity
import io.artex.mobile.data.ArtexApi
import io.artex.mobile.data.ChatAttachment
import io.artex.mobile.data.Conversation
import io.artex.mobile.data.Finding
import io.artex.mobile.data.MainSession
import io.artex.mobile.data.SessionStore
import io.artex.mobile.data.Task
import io.artex.mobile.data.TaskHistoryPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.ArrayDeque

enum class HomeTab { TASKS, FINDINGS, CONVERSATIONS, SETTINGS }

class ArtexViewModel(application: Application) : AndroidViewModel(application) {
    private val store = SessionStore(application)
    private var api: ArtexApi? = store.token()?.let { ArtexApi(store.baseUrl, it) }
    private var detailRequestGeneration = 0
    private var findingsRequestGeneration = 0
    private var activityDetailGeneration = 0
    private var taskSessionGeneration = 0
    private var transcriptEpoch = 0
    private var taskHistoryRefreshing = false
    private val inlineDetailSemaphore = Semaphore(3)
    private val inlineDetailInFlight = mutableMapOf<Long, Int>()
    private val inlineDetailOrder = ArrayDeque<Long>()
    private var inlineDetailChars = 0
    private val inlineDetails = mutableStateMapOf<Long, String>()
    private val inlineDetailErrors = mutableStateMapOf<Long, Boolean>()

    var signedIn by mutableStateOf(api != null); private set
    var serverAddress by mutableStateOf(store.baseUrl); private set
    var loginBusy by mutableStateOf(false); private set
    var tasksBusy by mutableStateOf(false); private set
    var findingsBusy by mutableStateOf(false); private set
    var conversationsBusy by mutableStateOf(false); private set
    var detailBusy by mutableStateOf(false); private set
    var sending by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    var tab by mutableStateOf(HomeTab.TASKS); private set

    var tasks by mutableStateOf<List<Task>>(emptyList()); private set
    var findings by mutableStateOf<List<Finding>>(emptyList()); private set
    var findingsTotal by mutableStateOf(0); private set
    var findingsPage by mutableStateOf(0); private set
    var findingSeverity by mutableStateOf("all"); private set
    var conversations by mutableStateOf<List<Conversation>>(emptyList()); private set
    var selectedTask by mutableStateOf<Task?>(null); private set
    var taskDetailTab by mutableStateOf("chat"); private set
    var taskActivity by mutableStateOf<List<Activity>>(emptyList()); private set
    var taskHistoryHasMore by mutableStateOf(false); private set
    var taskHistoryLoadingOlder by mutableStateOf(false); private set
    var taskHistoryEarliestCursor by mutableStateOf(0L); private set
    var mainSessions by mutableStateOf<List<MainSession>>(emptyList()); private set
    var currentMainSeg by mutableStateOf(0); private set
    var selectedMainSeg by mutableStateOf(0); private set
    var selectedConversation by mutableStateOf<Conversation?>(null); private set
    var selectedFinding by mutableStateOf<Finding?>(null); private set
    var conversationActivity by mutableStateOf<List<Activity>>(emptyList()); private set
    var activityDetail by mutableStateOf<String?>(null); private set
    var creatingTask by mutableStateOf(false); private set
    var creatingConversation by mutableStateOf(false); private set
    var managingConversation by mutableStateOf(false); private set
    var archivesOpen by mutableStateOf(false); private set
    var fullModeOpen by mutableStateOf(false); private set
    var feature by mutableStateOf<FeatureDestination?>(null); private set
    var wallpaper by mutableStateOf<Bitmap?>(null); private set
    var wallpaperDim by mutableStateOf(store.wallpaperDim.coerceIn(0.65f, 0.95f)); private set

    init {
        viewModelScope.launch { wallpaper = withContext(Dispatchers.IO) { store.loadWallpaper() } }
        if (signedIn) refreshTasks()
    }

    fun clearError() { error = null }

    fun login(address: String, password: String) {
        if (loginBusy) return
        viewModelScope.launch {
            loginBusy = true
            error = null
            try {
                val url = SessionStore.normalizeServerAddress(address)
                require(password.isNotEmpty()) { "请输入管理员密码" }
                val client = ArtexApi(url)
                val token = client.login(password)
                store.saveSession(url, token)
                api = client
                serverAddress = url
                signedIn = true
                tab = HomeTab.TASKS
                refreshTasks()
            } catch (e: Exception) { error = friendlyError(e) }
            finally { loginBusy = false }
        }
    }

    fun logout() {
        detailRequestGeneration++
        taskSessionGeneration++
        findingsRequestGeneration++
        activityDetailGeneration++
        resetTranscriptCache()
        store.clearSession()
        api = null
        signedIn = false
        tasks = emptyList()
        findings = emptyList()
        findingsTotal = 0
        findingsPage = 0
        findingSeverity = "all"
        conversations = emptyList()
        selectedTask = null
        selectedConversation = null
        selectedFinding = null
        taskActivity = emptyList()
        taskHistoryHasMore = false
        taskHistoryLoadingOlder = false
        taskHistoryEarliestCursor = 0
        mainSessions = emptyList()
        currentMainSeg = 0
        selectedMainSeg = 0
        taskHistoryRefreshing = false
        conversationActivity = emptyList()
        activityDetail = null
        creatingTask = false
        creatingConversation = false
        managingConversation = false
        archivesOpen = false
        fullModeOpen = false
        feature = null
        tasksBusy = false
        findingsBusy = false
        conversationsBusy = false
        detailBusy = false
        sending = false
        error = null
    }

    fun selectTab(next: HomeTab) {
        selectedTask = null
        selectedConversation = null
        selectedFinding = null
        creatingTask = false
        creatingConversation = false
        managingConversation = false
        archivesOpen = false
        fullModeOpen = false
        feature = null
        tab = next
        when (next) {
            HomeTab.TASKS -> if (tasks.isEmpty()) refreshTasks()
            HomeTab.FINDINGS -> if (findingsPage == 0) refreshFindings()
            HomeTab.CONVERSATIONS -> if (conversations.isEmpty()) refreshConversations()
            HomeTab.SETTINGS -> Unit
        }
    }

    fun refreshTasks() {
        val client = api ?: return
        if (tasksBusy) return
        tasksBusy = true
        viewModelScope.launch {
            try {
                val result = client.tasks()
                if (api === client) {
                    tasks = result
                    selectedTask?.let { selected ->
                        selectedTask = result.firstOrNull { it.id == selected.id } ?: selected
                    }
                }
            }
            catch (e: Exception) { if (api === client) handleError(e) }
            finally { if (api === client) tasksBusy = false }
        }
    }

    fun refreshFindings(loadMore: Boolean = false) {
        val client = api ?: return
        if (findingsBusy || (loadMore && findings.size >= findingsTotal)) return
        val severity = findingSeverity
        val generation = ++findingsRequestGeneration
        findingsBusy = true
        viewModelScope.launch {
            try {
                val page = if (loadMore) findingsPage + 1 else 1
                val result = client.findings(page, 20, severity.takeUnless { it == "all" })
                if (api === client && generation == findingsRequestGeneration && findingSeverity == severity) {
                    findings = if (loadMore) findings + result.items else result.items
                    findingsPage = page
                    findingsTotal = result.total
                }
            } catch (e: Exception) { if (api === client && generation == findingsRequestGeneration) handleError(e) }
            finally { if (api === client && generation == findingsRequestGeneration) findingsBusy = false }
        }
    }

    fun selectFindingSeverity(severity: String) {
        if (severity == findingSeverity) return
        findingsRequestGeneration++
        findingSeverity = severity
        findings = emptyList()
        findingsPage = 0
        findingsTotal = 0
        findingsBusy = false
        refreshFindings()
    }

    fun refreshConversations() {
        val client = api ?: return
        if (conversationsBusy) return
        conversationsBusy = true
        viewModelScope.launch {
            try { val result = client.conversations(); if (api === client) conversations = result }
            catch (e: Exception) { if (api === client) handleError(e) }
            finally { if (api === client) conversationsBusy = false }
        }
    }

    fun openTask(task: Task) {
        detailRequestGeneration++
        taskSessionGeneration++
        activityDetailGeneration++
        resetTranscriptCache()
        selectedTask = task
        taskDetailTab = "chat"
        selectedConversation = null
        selectedFinding = null
        taskActivity = emptyList()
        taskHistoryHasMore = false
        taskHistoryLoadingOlder = false
        taskHistoryEarliestCursor = 0
        mainSessions = emptyList()
        currentMainSeg = 0
        selectedMainSeg = 0
        taskHistoryRefreshing = false
        refreshTaskActivity(manual = true)
    }

    fun closeDetail() {
        detailRequestGeneration++
        taskSessionGeneration++
        activityDetailGeneration++
        resetTranscriptCache()
        selectedTask = null
        selectedConversation = null
        selectedFinding = null
        activityDetail = null
        managingConversation = false
    }

    fun selectTaskDetailTab(value: String) {
        if (value in setOf("chat", "agents", "details")) taskDetailTab = value
    }

    fun closeActivityDetail() { activityDetailGeneration++; activityDetail = null }

    fun openActivityDetail(activity: Activity) {
        val client = api ?: return
        val task = selectedTask
        val conversation = selectedConversation
        if (task == null && conversation == null) return
        val generation = ++activityDetailGeneration
        val sessionGeneration = taskSessionGeneration
        inlineActivityText(activity)?.let { activityDetail = it; return }
        activityDetail = "正在读取完整内容…"
        viewModelScope.launch {
            try {
                val full = if (task != null) client.taskActivityDetail(task.id, activity.seq)
                    else client.conversationActivityDetail(conversation!!.id, activity.seq)
                if (api === client && generation == activityDetailGeneration &&
                    taskSessionGeneration == sessionGeneration && selectedTask?.id == task?.id &&
                    selectedConversation?.id == conversation?.id) {
                    activityDetail = full.ifBlank { activity.summary }
                    if (activity.kind in setOf("user", "result", "text")) cacheInlineDetail(activity.seq, activityDetail!!)
                }
            } catch (e: Exception) {
                if (api === client && generation == activityDetailGeneration &&
                    taskSessionGeneration == sessionGeneration) {
                    activityDetail = null
                    handleError(e)
                }
            }
        }
    }

    private fun taskSessionKey(): String =
        if (selectedMainSeg == currentMainSeg) "main" else "main:$selectedMainSeg"

    fun selectMainSession(seq: Int) {
        if (seq == selectedMainSeg || mainSessions.none { it.seq == seq }) return
        detailRequestGeneration++
        taskSessionGeneration++
        activityDetailGeneration++
        activityDetail = null
        resetTranscriptCache()
        selectedMainSeg = seq
        taskActivity = emptyList()
        taskHistoryHasMore = false
        taskHistoryLoadingOlder = false
        taskHistoryEarliestCursor = 0
        taskHistoryRefreshing = false
        refreshTaskActivity(manual = true)
    }

    fun refreshTaskActivity(manual: Boolean = false) {
        val client = api ?: return
        val task = selectedTask ?: return
        if (!manual && selectedMainSeg != currentMainSeg) return // past segments are immutable
        if (taskHistoryRefreshing && !manual) return
        val session = taskSessionKey()
        val generation = ++detailRequestGeneration
        taskHistoryRefreshing = true
        viewModelScope.launch {
            detailBusy = taskActivity.isEmpty()
            try {
                val page = visibleTaskHistoryPage(client, task.id, session)
                if (api === client && generation == detailRequestGeneration && selectedTask?.id == task.id &&
                    taskSessionKey() == session) {
                    val segmentChanged = session == "main" && page.currentMainSeg != null &&
                        page.currentMainSeg != currentMainSeg
                    if (segmentChanged) {
                        taskSessionGeneration++
                        activityDetailGeneration++
                        activityDetail = null
                        resetTranscriptCache()
                        taskHistoryLoadingOlder = false
                    }
                    val hadEarlierPage = !segmentChanged && taskActivity.isNotEmpty() && page.items.isNotEmpty() &&
                        taskActivity.first().seq < page.items.first().seq
                    if (session == "main") {
                        page.currentMainSeg?.let { currentMainSeg = it; selectedMainSeg = it }
                        if (page.mainSessions.isNotEmpty()) mainSessions = page.mainSessions
                    }
                    taskActivity = if (segmentChanged) page.items else mergeActivities(taskActivity, page.items)
                    taskHistoryHasMore = if (hadEarlierPage) taskHistoryHasMore else page.hasMore
                    taskHistoryEarliestCursor = taskActivity.firstOrNull()?.seq ?: page.earliestCursor
                }
            } catch (e: Exception) { if (api === client && generation == detailRequestGeneration) handleError(e) }
            finally {
                if (api === client && generation == detailRequestGeneration) {
                    detailBusy = false
                    taskHistoryRefreshing = false
                }
            }
        }
    }

    fun loadOlderTaskActivity() {
        val client = api ?: return
        val task = selectedTask ?: return
        if (!taskHistoryHasMore || taskHistoryLoadingOlder || taskHistoryEarliestCursor <= 0) return
        val session = taskSessionKey()
        val epoch = taskSessionGeneration
        val before = taskHistoryEarliestCursor
        taskHistoryLoadingOlder = true
        viewModelScope.launch {
            try {
                val page = visibleTaskHistoryPage(client, task.id, session, before)
                if (api === client && selectedTask?.id == task.id && taskSessionGeneration == epoch &&
                    taskSessionKey() == session) {
                    taskActivity = mergeActivities(taskActivity, page.items)
                    taskHistoryHasMore = page.hasMore && page.items.isNotEmpty()
                    taskHistoryEarliestCursor = taskActivity.firstOrNull()?.seq ?: 0
                }
            } catch (e: Exception) {
                if (api === client && taskSessionGeneration == epoch) handleError(e)
            } finally {
                if (api === client && taskSessionGeneration == epoch) taskHistoryLoadingOlder = false
            }
        }
    }

    fun inlineActivityText(activity: Activity): String? =
        activity.detail.takeIf { it.isNotBlank() } ?: inlineDetails[activity.seq]

    fun inlineActivityError(seq: Long): Boolean = inlineDetailErrors[seq] == true

    /** Called from a visible row's coroutine so scrolling away cancels queued detail requests. */
    suspend fun loadInlineActivityDetail(activity: Activity) {
        if (activity.kind !in setOf("user", "result", "text", "thinking", "tool_use", "tool_result") ||
            activity.seq <= 0 ||
            inlineActivityText(activity) != null) return
        val client = api ?: return
        val taskId = selectedTask?.id
        val conversationId = selectedConversation?.id
        if (taskId == null && conversationId == null) return
        val epoch = transcriptEpoch
        if (inlineDetailInFlight.putIfAbsent(activity.seq, epoch) != null) return
        try {
            val full = inlineDetailSemaphore.withPermit {
                if (api !== client || transcriptEpoch != epoch) null
                else if (taskId != null) client.taskActivityDetail(taskId, activity.seq)
                else client.conversationActivityDetail(conversationId!!, activity.seq)
            }
            if (full != null && api === client && transcriptEpoch == epoch &&
                selectedTask?.id == taskId && selectedConversation?.id == conversationId) {
                cacheInlineDetail(activity.seq, full.ifBlank { activity.summary })
                inlineDetailErrors.remove(activity.seq)
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            if (api === client && transcriptEpoch == epoch) inlineDetailErrors[activity.seq] = true
        } finally {
            if (inlineDetailInFlight[activity.seq] == epoch) inlineDetailInFlight.remove(activity.seq)
        }
    }

    private fun cacheInlineDetail(seq: Long, full: String) {
        val old = inlineDetails.put(seq, full)
        if (old != null) {
            inlineDetailChars -= old.length
            inlineDetailOrder.remove(seq)
        }
        inlineDetailChars += full.length
        inlineDetailOrder.addLast(seq)
        while (inlineDetailOrder.size > 80 || (inlineDetailChars > 1_500_000 && inlineDetailOrder.size > 1)) {
            val oldest = inlineDetailOrder.removeFirst()
            inlineDetailChars -= inlineDetails.remove(oldest)?.length ?: 0
        }
    }

    private fun resetTranscriptCache() {
        transcriptEpoch++
        inlineDetails.clear()
        inlineDetailErrors.clear()
        inlineDetailInFlight.clear()
        inlineDetailOrder.clear()
        inlineDetailChars = 0
    }

    private fun mergeActivities(existing: List<Activity>, incoming: List<Activity>): List<Activity> =
        (existing + incoming).associateBy { it.seq }.values.sortedBy { it.seq }

    /** Usage events have no body; skip empty windows without making the user tap through them. */
    private suspend fun visibleTaskHistoryPage(
        client: ArtexApi, taskId: String, session: String, before: Long = 0,
    ): TaskHistoryPage {
        var page = client.taskHistory(taskId, session = session, before = before, limit = 60)
        var attempts = 0
        while (page.items.none { it.kind != "usage" } && page.hasMore && page.earliestCursor > 0 &&
            attempts++ < 4) {
            val older = client.taskHistory(taskId, session = session, before = page.earliestCursor, limit = 60)
            if (older.items.isEmpty()) return page.copy(hasMore = false)
            page = page.copy(items = older.items + page.items, hasMore = older.hasMore,
                earliestCursor = older.earliestCursor)
        }
        return page
    }

    fun sendTaskMessage(message: String, attachments: List<ChatAttachment>, onAccepted: () -> Unit) {
        val client = api ?: return
        val task = selectedTask ?: return
        if (sending || (message.isBlank() && attachments.isEmpty())) return
        viewModelScope.launch {
            sending = true
            try {
                client.sendTaskMessage(task.id, message.trim(), attachments)
                if (api === client && selectedTask?.id == task.id) { onAccepted(); refreshTaskActivity(manual = true) }
            } catch (e: Exception) { if (api === client) handleError(e) }
            finally { if (api === client) sending = false }
        }
    }

    fun stopTaskChat() {
        val client = api ?: return
        val task = selectedTask ?: return
        viewModelScope.launch {
            try { client.stopTaskChat(task.id); refreshTaskActivity(manual = true) }
            catch (e: Exception) { handleError(e) }
        }
    }

    fun openConversation(conversation: Conversation) {
        detailRequestGeneration++
        taskSessionGeneration++
        activityDetailGeneration++
        resetTranscriptCache()
        selectedConversation = conversation
        selectedTask = null
        selectedFinding = null
        conversationActivity = emptyList()
        refreshConversationActivity()
    }

    fun openFinding(finding: Finding) {
        selectedFinding = finding
        selectedTask = null
        selectedConversation = null
    }

    fun apiClient(): ArtexApi? = api

    fun openCreateTask() { creatingTask = true }
    fun onTaskCreated(task: Task) {
        creatingTask = false
        refreshTasks()
        openTask(task)
    }

    fun openCreateConversation() { creatingConversation = true }
    fun onConversationCreated(conversation: Conversation) {
        creatingConversation = false
        refreshConversations()
        openConversation(conversation)
    }
    fun openConversationActions() { managingConversation = true }
    fun closeConversationActions() { managingConversation = false }
    fun onConversationDeleted() {
        managingConversation = false
        selectedConversation = null
        refreshConversations()
    }

    fun openArchives() { archivesOpen = true }
    fun openFullMode() { fullModeOpen = true }
    fun openFeature(destination: FeatureDestination) { feature = destination }
    fun goBack() {
        when {
            creatingTask -> creatingTask = false
            creatingConversation -> creatingConversation = false
            managingConversation -> managingConversation = false
            selectedTask != null || selectedConversation != null || selectedFinding != null -> closeDetail()
            feature != null -> feature = null
            archivesOpen -> archivesOpen = false
            fullModeOpen -> fullModeOpen = false
            else -> closeDetail()
        }
    }

    fun refreshConversationActivity() {
        val client = api ?: return
        val conversation = selectedConversation ?: return
        val generation = ++detailRequestGeneration
        viewModelScope.launch {
            detailBusy = conversationActivity.isEmpty()
            try {
                val result = client.conversationMessages(conversation.id, 80)
                if (api === client && generation == detailRequestGeneration && selectedConversation?.id == conversation.id) conversationActivity = result
            } catch (e: Exception) { if (api === client && generation == detailRequestGeneration) handleError(e) }
            finally { if (api === client && generation == detailRequestGeneration) detailBusy = false }
        }
    }

    fun sendConversationMessage(message: String, attachments: List<ChatAttachment>, onAccepted: () -> Unit) {
        val client = api ?: return
        val conversation = selectedConversation ?: return
        if (sending || (message.isBlank() && attachments.isEmpty())) return
        viewModelScope.launch {
            sending = true
            try {
                client.sendConversationMessage(conversation.id, message.trim(), attachments)
                if (api === client && selectedConversation?.id == conversation.id) { onAccepted(); refreshConversationActivity() }
            } catch (e: Exception) { if (api === client) handleError(e) }
            finally { if (api === client) sending = false }
        }
    }

    fun stopConversation() {
        val client = api ?: return
        val conversation = selectedConversation ?: return
        viewModelScope.launch {
            try { client.stopConversation(conversation.id); refreshConversationActivity() }
            catch (e: Exception) { handleError(e) }
        }
    }

    fun chooseWallpaper(uri: Uri) {
        viewModelScope.launch {
            try { wallpaper = store.saveWallpaper(uri) }
            catch (e: Exception) { error = friendlyError(e) }
        }
    }

    fun removeWallpaper() { store.clearWallpaper(); wallpaper = null }
    fun updateWallpaperDim(value: Float) {
        wallpaperDim = value.coerceIn(0.65f, 0.95f)
        store.setWallpaperDim(wallpaperDim)
    }

    private fun handleError(e: Exception) {
        if (e is io.artex.mobile.data.ApiException && e.status == 401) {
            logout()
            error = "登录已过期，请重新登录"
        } else error = friendlyError(e)
    }

    private fun friendlyError(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "连接超时，请检查服务器地址和网络"
        is java.net.ConnectException, is java.net.UnknownHostException -> "无法连接服务器，请检查 IP 和端口"
        is io.artex.mobile.data.ApiException -> if (e.status == 401) "密码错误或登录已过期" else (e.message ?: "服务器返回 ${e.status}")
        else -> e.message ?: "操作失败，请稍后重试"
    }
}
