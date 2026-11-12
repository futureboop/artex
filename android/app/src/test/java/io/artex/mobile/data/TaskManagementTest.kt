package io.artex.mobile.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskManagementTest {
    @Test
    fun createTaskSendsAdvancedOptionsToExistingEndpoint() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(201).setBody(
            """{"id":"64","name":"外网评估","description":"测试授权资产","goal":"确认风险","status":"created"}"""
        ))
        server.start()
        try {
            val api = ArtexApi(server.url("/").toString(), "token")
            val created = api.createTask(NewTaskInput(
                name = "外网评估", description = "测试授权资产", goal = "确认风险",
                categoryId = 3, llmProfileIds = listOf(7, 9), timeoutSeconds = 3600,
                planHeartbeatSeconds = 600, seedFirstIntent = true, coverageEnabled = false,
            ))
            assertEquals("64", created.id)
            val request = server.takeRequest()
            assertEquals("POST", request.method)
            assertEquals("/api/tasks", request.path)
            assertEquals("Bearer token", request.getHeader("Authorization"))
            val body = JSONObject(request.body.readUtf8())
            assertEquals(3L, body.getLong("category_id"))
            assertEquals(7L, body.getJSONArray("llm_profile_ids").getLong(0))
            assertEquals(9L, body.getJSONArray("llm_profile_ids").getLong(1))
            assertEquals(3600, body.getInt("timeout_seconds"))
            assertTrue(body.getBoolean("seed_first_intent"))
            assertFalse(body.getBoolean("coverage_enabled"))
        } finally { server.shutdown() }
    }

    @Test
    fun patchAndDeleteUseSupportedMethodsAndConservativeCleanupDefaults() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        server.start()
        try {
            val api = ArtexApi(server.url("/").toString(), "token")
            api.renameTask("64", "更新名称")
            val patch = server.takeRequest()
            assertEquals("PATCH", patch.method)
            assertEquals("/api/tasks/64", patch.path)
            assertEquals("更新名称", JSONObject(patch.body.readUtf8()).getString("name"))

            api.deleteTask("64", DeleteTaskOptions())
            val delete = server.takeRequest()
            assertEquals("DELETE", delete.method)
            assertEquals("/api/tasks/64", delete.path)
            val body = JSONObject(delete.body.readUtf8())
            assertFalse(body.getBoolean("delete_assets"))
            assertFalse(body.getBoolean("delete_traffic"))
            assertFalse(body.getBoolean("delete_files"))
            assertFalse(body.getBoolean("delete_findings"))
            assertFalse(body.getBoolean("delete_llm_records"))
        } finally { server.shutdown() }
    }
}
