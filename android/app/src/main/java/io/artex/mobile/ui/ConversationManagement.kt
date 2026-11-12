package io.artex.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.artex.mobile.data.ArtexApi
import io.artex.mobile.data.Conversation
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private data class Choice(val id: String, val label: String)

private fun JSONArray?.choices(idKey: String, nameKey: String): List<Choice> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { i ->
        optJSONObject(i)?.let { Choice(it.optString(idKey), it.optString(nameKey).ifBlank { it.optString(idKey) }) }
    }

@Composable
private fun ConversationFormHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.Outlined.ArrowBack, "返回") }
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    }
    HorizontalDivider()
}

@Composable
private fun ChoiceField(label: String, current: String, choices: List<Choice>, onChoose: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Text(label, fontSize = 12.sp)
    Spacer(Modifier.height(6.dp))
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(choices.firstOrNull { it.id == current }?.label ?: "请选择", modifier = Modifier.weight(1f))
            Text("⌄")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(text = { Text(choice.label) }, onClick = { expanded = false; onChoose(choice.id) })
            }
        }
    }
}

@Composable
fun CreateConversationScreen(api: ArtexApi, onBack: () -> Unit, onCreated: (Conversation) -> Unit) {
    val scope = rememberCoroutineScope()
    var agents by remember(api) { mutableStateOf<List<Choice>>(emptyList()) }
    var profiles by remember(api) { mutableStateOf<List<Choice>>(emptyList()) }
    var agent by remember { mutableStateOf("") }
    var profile by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    LaunchedEffect(api) {
        try {
            agents = api.request("GET", "/api/agents").optJSONArray("agents").choices("key", "name")
            profiles = api.request("GET", "/api/llm/profiles").optJSONArray("profiles").choices("id", "name")
            if (agent.isBlank()) agent = agents.firstOrNull()?.id.orEmpty()
        } catch (e: Exception) { error = e.message ?: "无法读取 Agent 列表" }
    }

    Column(Modifier.fillMaxSize()) {
        ConversationFormHeader("新建对话", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text("创建独立 Agent 会话", fontSize = 23.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(22.dp))
            if (error.isNotBlank()) { Text(error, color = MaterialTheme.colorScheme.error); Spacer(Modifier.height(12.dp)) }
            ChoiceField("Agent", agent, agents) { agent = it }
            Spacer(Modifier.height(18.dp))
            OutlinedTextField(title, onValueChange = { title = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("对话标题") }, singleLine = true)
            Spacer(Modifier.height(18.dp))
            ChoiceField("LLM 配置", profile, listOf(Choice("", "跟随默认配置")) + profiles) { profile = it }
            Spacer(Modifier.height(27.dp))
            Button(onClick = {
                scope.launch {
                    busy = true
                    try {
                        val body = JSONObject().put("agent_key", agent).put("title", title.trim())
                        if (profile.isNotBlank()) body.put("llm_profile_id", profile.toIntOrNull())
                        val result = api.request("POST", "/api/conversations", body)
                        onCreated(Conversation(result.optLong("id"), result.optString("title"),
                            result.optString("agent_key"), result.optBoolean("running")))
                    } catch (e: Exception) { error = e.message ?: "创建失败" }
                    finally { busy = false }
                }
            }, enabled = !busy && agent.isNotBlank(), modifier = Modifier.fillMaxWidth().height(50.dp)) {
                if (busy) CircularProgressIndicator(Modifier.size(19.dp), strokeWidth = 2.dp)
                else Text("创建对话")
            }
        }
    }
}

@Composable
fun ConversationActionsScreen(conversation: Conversation, api: ArtexApi, onBack: () -> Unit,
    onChanged: () -> Unit, onDeleted: () -> Unit) {
    val scope = rememberCoroutineScope()
    var title by remember(conversation.id) { mutableStateOf(conversation.title) }
    var pinned by remember(conversation.id) { mutableStateOf(false) }
    var profile by remember(conversation.id) { mutableStateOf("") }
    var profiles by remember(api) { mutableStateOf<List<Choice>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(api, conversation.id) {
        try {
            val all = api.request("GET", "/api/conversations").optJSONArray("conversations")
            val current = (0 until (all?.length() ?: 0)).mapNotNull { all?.optJSONObject(it) }
                .firstOrNull { it.optLong("id") == conversation.id }
            pinned = current?.optBoolean("pinned") ?: false
            profile = current?.optString("llm_profile_id")?.takeUnless { it == "null" }.orEmpty()
            profiles = api.request("GET", "/api/llm/profiles").optJSONArray("profiles").choices("id", "name")
        } catch (e: Exception) { error = e.message ?: "读取对话配置失败" }
    }

    fun mutate(path: String, body: JSONObject, after: () -> Unit = {}) {
        scope.launch {
            busy = true
            try { api.request("PATCH", path, body); onChanged(); after(); error = "" }
            catch (e: Exception) { error = e.message ?: "保存失败" }
            finally { busy = false }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ConversationFormHeader("对话设置", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Text("${conversation.agentKey} · #${conversation.id}", fontSize = 12.sp)
            Spacer(Modifier.height(20.dp))
            if (error.isNotBlank()) { Text(error, color = MaterialTheme.colorScheme.error); Spacer(Modifier.height(12.dp)) }
            OutlinedTextField(title, onValueChange = { title = it }, modifier = Modifier.fillMaxWidth(),
                label = { Text("对话标题") }, singleLine = true)
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { mutate("/api/conversations/${conversation.id}", JSONObject().put("title", title.trim())) },
                enabled = !busy && title.isNotBlank()) { Text("保存标题") }
            Spacer(Modifier.height(20.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("置顶", Modifier.weight(1f))
                Switch(checked = pinned, onCheckedChange = { next ->
                    mutate("/api/conversations/${conversation.id}", JSONObject().put("pinned", next)) { pinned = next }
                }, enabled = !busy)
            }
            Spacer(Modifier.height(17.dp))
            ChoiceField("LLM 配置", profile, listOf(Choice("", "跟随默认配置")) + profiles) { next ->
                val body = JSONObject().put("llm_profile_id", next.toIntOrNull() ?: JSONObject.NULL)
                mutate("/api/conversations/${conversation.id}/profile", body) { profile = next }
            }
            Spacer(Modifier.height(36.dp))
            TextButton(onClick = { confirmDelete = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Text("删除对话")
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text("删除对话") },
        text = { Text("这会删除该独立对话及其记录。") }, confirmButton = {
            Button(onClick = {
                confirmDelete = false
                scope.launch {
                    busy = true
                    try { api.request("DELETE", "/api/conversations/${conversation.id}"); onDeleted() }
                    catch (e: Exception) { error = e.message ?: "删除失败" }
                    finally { busy = false }
                }
            }, enabled = !busy) { Text("删除") }
        }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } })
}
