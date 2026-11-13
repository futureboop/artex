package io.artex.mobile.data

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ArtexApiTest {
    @Test
    fun addressAcceptsIpAndRejectsEmbeddedCredentialsOrPaths() {
        assertEquals("http://192.0.2.10:32451", SessionStore.normalizeServerAddress("192.0.2.10:32451"))
        assertEquals("https://example.org", SessionStore.normalizeServerAddress("https://example.org/"))
        assertThrows(IllegalArgumentException::class.java) {
            SessionStore.normalizeServerAddress("https://user:secret@example.org")
        }
        assertThrows(IllegalArgumentException::class.java) {
            SessionStore.normalizeServerAddress("https://example.org/unexpected/path")
        }
    }

    @Test
    fun loginUsesExistingApiAndTasksSendBearerToken() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"token":"test-jwt"}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"tasks":[{"id":"63","name":"示例任务","status":"running","findings":{"high":2}}],"active":"63"}"""
        ))
        server.start()
        try {
            val api = ArtexApi(server.url("/").toString())
            assertEquals("test-jwt", api.login("sample-password"))
            val tasks = api.tasks()
            val login = server.takeRequest()
            assertEquals("POST", login.method)
            assertEquals("/api/auth/login", login.path)
            val body = JSONObject(login.body.readUtf8())
            assertEquals("ARTEX", body.getString("username"))
            assertEquals("sample-password", body.getString("password"))
            val list = server.takeRequest()
            assertEquals("GET", list.method)
            assertEquals("/api/tasks", list.path)
            assertEquals("Bearer test-jwt", list.getHeader("Authorization"))
            assertEquals(1, tasks.size)
            assertEquals("示例任务", tasks.single().title)
            assertEquals(2, tasks.single().findings)
        } finally { server.shutdown() }
    }

    @Test
    fun bearerTokenIsNotForwardedToRedirectDestination() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/redirected"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.start()
        try {
            val api = ArtexApi(server.url("/").toString(), "test-jwt")
            val error = assertThrows(ApiException::class.java) { runBlocking { api.tasks() } }
            assertEquals(302, error.status)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test
    fun findingsSeverityIsSentToServerBeforePagination() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"items":[],"total":0,"page":2,"page_size":20}"""
        ))
        server.start()
        try {
            val api = ArtexApi(server.url("/").toString(), "test-jwt")
            api.findings(page = 2, limit = 20, severity = "critical")
            assertEquals("/api/exploration/findings?page=2&limit=20&severity=critical", server.takeRequest().path)
        } finally { server.shutdown() }
    }

    @Test
    fun emptyCompanyArrayAcceptsBackendNullResponse() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("null"))
        server.start()
        try {
            val api = ArtexApi(server.url("/").toString(), "test-jwt")
            assertEquals(0, api.requestArray("/api/companies").length())
            val request = server.takeRequest()
            assertEquals("/api/companies", request.path)
            assertEquals("Bearer test-jwt", request.getHeader("Authorization"))
        } finally { server.shutdown() }
    }

    @Test
    fun taskAndConversationMessagesCarryUploadedAttachmentReferences() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.start()
        try {
            val api = ArtexApi(server.url("/").toString(), "test-jwt")
            val attachment = ChatAttachment("report.txt", "uploads/report.txt", 42)
            api.sendTaskMessage("63", "检查附件", listOf(attachment))
            api.sendConversationMessage(7, "", listOf(attachment))
            val taskRequest = server.takeRequest()
            assertEquals("/api/chat?task=63", taskRequest.path)
            assertEquals("Bearer test-jwt", taskRequest.getHeader("Authorization"))
            val taskBody = JSONObject(taskRequest.body.readUtf8())
            assertEquals("检查附件", taskBody.getString("message"))
            assertEquals("uploads/report.txt", taskBody.getJSONArray("attachments")
                .getJSONObject(0).getString("path"))
            val conversationRequest = server.takeRequest()
            assertEquals("/api/conversations/7/messages", conversationRequest.path)
            val conversationBody = JSONObject(conversationRequest.body.readUtf8())
            assertEquals("", conversationBody.getString("message"))
            assertEquals("report.txt", conversationBody.getJSONArray("attachments")
                .getJSONObject(0).getString("name"))
        } finally { server.shutdown() }
    }

    @Test
    fun mainAgentHistoryPreservesSessionAndOlderPageWhileDetailLoadsFullText() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"items":[{"seq":51,"kind":"result","worker":"mainagent","summary":"只显示第一行"}],"has_more":true,"earliest_cursor":51,"current_main_seg":2,"main_sessions":[{"seq":2,"created_at":"2026-11-13T01:00:00Z"},{"seq":0,"created_at":""}]}"""
        ))
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"items":[{"seq":12,"kind":"result","worker":"mainagent","summary":"旧会话摘要"}],"has_more":false,"earliest_cursor":12}"""
        ))
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"detail":"第一行\n第二行是完整正文，不能被摘要截掉。"}"""
        ))
        server.start()
        try {
            val api = ArtexApi(server.url("/").toString(), "test-jwt")
            val latest = api.taskHistory("56", limit = 60)
            assertEquals(2, latest.currentMainSeg)
            assertEquals(listOf(2, 0), latest.mainSessions.map { it.seq })
            assertEquals(true, latest.hasMore)
            assertEquals(51L, latest.earliestCursor)
            assertEquals("只显示第一行", latest.items.single().summary)
            assertEquals("/api/exploration/activity/history?task=56&session=main&limit=60", server.takeRequest().path)

            val older = api.taskHistory("56", session = "main:0", before = 51, limit = 60)
            assertEquals("旧会话摘要", older.items.single().summary)
            assertEquals(false, older.hasMore)
            assertEquals("/api/exploration/activity/history?task=56&session=main%3A0&limit=60&before=51", server.takeRequest().path)

            assertEquals("第一行\n第二行是完整正文，不能被摘要截掉。", api.taskActivityDetail("56", 51))
            assertEquals("/api/exploration/activity/51?task=56", server.takeRequest().path)
        } finally { server.shutdown() }
    }

    @Test
    fun mainAgentSessionSwitchUsesItsOwnCursorAndCurrentSessionCanStillRefresh() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(
            """{"items":[{"seq":205,"kind":"result","summary":"当前回复"}],"has_more":true,"earliest_cursor":205,"current_main_seg":3,"main_sessions":[{"seq":3},{"seq":1}]}"""
        ))
        server.enqueue(MockResponse().setBody(
            """{"items":[{"seq":39,"kind":"result","summary":"旧段最新回复"}],"has_more":true,"earliest_cursor":39}"""
        ))
        server.enqueue(MockResponse().setBody(
            """{"items":[{"seq":18,"kind":"user","summary":"旧段更早提问"}],"has_more":false,"earliest_cursor":18}"""
        ))
        server.enqueue(MockResponse().setBody(
            """{"items":[{"seq":206,"kind":"result","summary":"当前新回复"}],"has_more":true,"earliest_cursor":206,"current_main_seg":3,"main_sessions":[{"seq":3},{"seq":1}]}"""
        ))
        server.start()
        try {
            val api = ArtexApi(server.url("/").toString(), "test-jwt")
            val current = api.taskHistory("56")
            assertEquals(3, current.currentMainSeg)
            assertEquals(listOf(3, 1), current.mainSessions.map { it.seq })
            assertEquals(205L, current.earliestCursor)
            assertEquals("/api/exploration/activity/history?task=56&session=main&limit=60", server.takeRequest().path)

            val historical = api.taskHistory("56", session = "main:1")
            assertEquals(39L, historical.earliestCursor)
            assertEquals(true, historical.hasMore)
            assertEquals(null, historical.currentMainSeg)
            assertEquals("/api/exploration/activity/history?task=56&session=main%3A1&limit=60", server.takeRequest().path)

            val olderHistorical = api.taskHistory("56", session = "main:1", before = historical.earliestCursor)
            assertEquals(18L, olderHistorical.items.single().seq)
            assertEquals(false, olderHistorical.hasMore)
            assertEquals("/api/exploration/activity/history?task=56&session=main%3A1&limit=60&before=39", server.takeRequest().path)

            val refreshedCurrent = api.taskHistory("56")
            assertEquals(206L, refreshedCurrent.items.single().seq)
            assertEquals(3, refreshedCurrent.currentMainSeg)
            assertEquals("/api/exploration/activity/history?task=56&session=main&limit=60", server.takeRequest().path)
        } finally { server.shutdown() }
    }

    @Test
    fun fullMainAgentDetailCanRetryAfterServerErrorWithoutLosingCharacters() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(503).setBody("""{"error":"暂时不可用"}"""))
        server.enqueue(MockResponse().setBody(
            """{"detail":"第一行完整中文。\n第二行含有 emoji 🧪 和标点，结尾不能少字。"}"""
        ))
        server.start()
        try {
            val api = ArtexApi(server.url("/").toString(), "test-jwt")
            val failure = assertThrows(ApiException::class.java) {
                runBlocking { api.taskActivityDetail("56", 77) }
            }
            assertEquals(503, failure.status)
            assertEquals("/api/exploration/activity/77?task=56", server.takeRequest().path)

            val full = api.taskActivityDetail("56", 77)
            assertEquals("第一行完整中文。\n第二行含有 emoji 🧪 和标点，结尾不能少字。", full)
            val retry = server.takeRequest()
            assertEquals("/api/exploration/activity/77?task=56", retry.path)
            assertEquals("Bearer test-jwt", retry.getHeader("Authorization"))
        } finally { server.shutdown() }
    }
}
