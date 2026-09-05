package icu.minq.memoh.network

import icu.minq.memoh.model.*
import icu.minq.memoh.security.AuthStore
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ComposerApiTest {
    @Test fun `official model and device catalogs and ACP change use authenticated endpoints`() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val auth = AuthMaterial("fixture", "2099-01-01T00:00:00Z", server.url("/api").toString().trimEnd('/'))
            val store = object : AuthStore {
                override fun read() = auth
                override fun write(value: AuthMaterial) = Unit
                override fun clear() = Unit
            }
            val client = OkHttpClient()
            val api = MemohApi(client, Json { ignoreUnknownKeys = true }, store)
            try {
                server.enqueue(MockResponse().setBody("""[{"id":"chat","name":"Chat","type":"chat"},{"id":"embedding","type":"embedding"},{"id":"disabled","type":"chat","enable":false}]"""))
                assertEquals(listOf("chat"), api.models().map { it.id })
                assertEquals("/api/models", server.takeRequest().path)
                server.enqueue(MockResponse().setBody("""{"targets":[{"target_id":"native","kind":"native"},{"target_id":"remote:r","kind":"remote","online":true,"status":"online"}]}"""))
                assertEquals(2, api.workspaceTargets("b").size)
                assertEquals("/api/bots/b/workspace-targets", server.takeRequest().path)
                server.enqueue(MockResponse().setBody("""{"configured_model_id":"direct","models":[{"id":"direct","name":"Direct model"}]}"""))
                assertEquals("direct", api.agentModels("b", "a").configuredModelId)
                assertEquals("/api/bots/b/agents/a/models", server.takeRequest().path)
                val status = """{"runtime_id":"r","models":{"current_model_id":"new","available_models":[{"id":"new","name":"New model"}]}}"""
                server.enqueue(MockResponse().setBody(status))
                assertEquals("new", api.ensureACPRuntime("b", "s").models?.currentModelId)
                val prepare = server.takeRequest()
                assertEquals("POST", prepare.method)
                assertEquals("/api/bots/b/sessions/s/acp-runtime", prepare.path)
                server.enqueue(MockResponse().setBody(status))
                assertEquals("new", api.setACPModel("b", "s", "new").models?.currentModelId)
                val change = server.takeRequest()
                assertEquals("PATCH", change.method)
                assertEquals("/api/bots/b/sessions/s/acp-runtime/model", change.path)
                assertEquals("Bearer fixture", change.getHeader("Authorization"))
                assertEquals("""{"model_id":"new"}""", change.body.readUtf8())
            } finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }
}
