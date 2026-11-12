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
}
