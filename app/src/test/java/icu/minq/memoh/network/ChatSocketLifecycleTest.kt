package icu.minq.memoh.network

import icu.minq.memoh.model.AuthMaterial
import icu.minq.memoh.model.RuntimeState
import icu.minq.memoh.model.ChatAttachment
import icu.minq.memoh.security.AuthStore
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Exercises the actual actor and transport; callbacks are synchronous only in this JVM harness. */
class ChatSocketLifecycleTest {
    @Test fun `text batching preserves every append and flushes the terminal transition`() {
        val callbacks = AtomicInteger()
        val complete = CountDownLatch(1)
        val initial = """{"type":"runtime_snapshot","session_id":"s","epoch":"e","seq":0,"snapshot":{"session_id":"s","epoch":"e","seq":0,"current_run_view":{"run_id":"r","turn_id":"t","status":"running","messages":[]}}}"""
        withSnapshotSocket(initial, afterSubscribe = { peer ->
            repeat(100) { index -> peer.send("""{"type":"runtime_delta","session_id":"s","epoch":"e","seq":${index + 1},"delta":{"message_appends":[{"id":0,"type":"text","content":"x"}]}}""") }
            peer.send("""{"type":"runtime_delta","session_id":"s","epoch":"e","seq":101,"delta":{"run":{"run_id":"r","status":"completed"}}}""")
        }, observe = { value -> callbacks.incrementAndGet(); if (value.run?.status == "completed") complete.countDown() }) { _, _, _, state, _ ->
            assertTrue(complete.await(5, TimeUnit.SECONDS))
            assertEquals("x".repeat(100), state.get().run!!.messages.single().content)
            assertEquals(101L, state.get().seq)
            assertTrue("A burst should not create one UI callback per token", callbacks.get() < 20)
        }
    }

    @Test fun `send after close reports failure without stranding the composer`() {
        val client = OkHttpClient()
        val store = object : AuthStore {
            override fun read(): AuthMaterial? = null
            override fun write(value: AuthMaterial) = Unit
            override fun clear() = Unit
        }
        val socket = ChatSocket(MemohApi(client, Json, store), "b", "s", object : ChatSocketListener {}, callback = { it() })
        val rejected = CountDownLatch(1)
        socket.close()
        socket.sendMessage("must stay in draft", "i") { if (!it) rejected.countDown() }
        assertTrue(rejected.await(2, TimeUnit.SECONDS))
        client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
    }

    @Test fun `reopening ledger restored old session accepts null messages and allows next prompt`() {
        withSnapshotSocket("""{"type":"runtime_snapshot","session_id":"s","seq":0,"snapshot":{"session_id":"s","epoch":"","seq":0,"current_run_view":{"run_id":"r","turn_id":"t","status":"completed","messages":null}}}""") { socket, ready, failure, state, prompts ->
            assertTrue("Old session should become ready", ready.await(3, TimeUnit.SECONDS))
            assertTrue(socket.isUsable())
            assertEquals("completed", state.get().run?.status)
            assertEquals(emptyList<Any>(), state.get().run?.messages)
            socket.sendMessage("next message")
            assertEquals("message", prompts.poll(3, TimeUnit.SECONDS)?.get("type")?.jsonPrimitive?.content)
            assertEquals(1L, failure.count)
        }
    }

    @Test fun `invalid snapshot reports failure instead of remaining connecting`() {
        withSnapshotSocket("""{"type":"runtime_snapshot","session_id":"s","epoch":"e","seq":0,"snapshot":{"session_id":"s","epoch":"e","seq":0,"current_run_view":{"run_id":"r","turn_id":"t","status":42,"messages":{}}}}""") { socket, ready, failure, _, _ ->
            assertTrue("Invalid state should be visible", failure.await(3, TimeUnit.SECONDS))
            assertFalse(socket.isUsable())
            assertEquals(1L, ready.count)
        }
    }

    @Test fun `missing snapshot expires rather than connecting forever`() {
        withSnapshotSocket(null) { socket, ready, failure, _, _ ->
            assertTrue(failure.await(3, TimeUnit.SECONDS))
            assertFalse(socket.isUsable())
            assertEquals(1L, ready.count)
        }
    }

    @Test fun `inconsistent snapshot is rejected without subscribe loop`() {
        withSnapshotSocket("""{"type":"runtime_snapshot","session_id":"s","epoch":"e","seq":0,"snapshot":{"session_id":"s","epoch":"e","seq":1}}""") { socket, _, failure, _, _ ->
            assertTrue(failure.await(3, TimeUnit.SECONDS))
            assertFalse(socket.isUsable())
        }
    }

    private fun withSnapshotSocket(frame: String?, afterSubscribe: (WebSocket) -> Unit = {}, observe: (RuntimeState) -> Unit = {}, test: (ChatSocket, CountDownLatch, CountDownLatch, AtomicReference<RuntimeState>, java.util.concurrent.LinkedBlockingQueue<JsonObject>) -> Unit) {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverCertificates.sslSocketFactory(), false); server.start()
            val prompts = java.util.concurrent.LinkedBlockingQueue<JsonObject>()
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (text.contains("runtime_subscribe")) { if (frame != null) webSocket.send(frame); afterSubscribe(webSocket) }
                    else prompts.put(Json.parseToJsonElement(text).jsonObject)
                }
            }))
            val client = OkHttpClient.Builder().sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager).build()
            val auth = AuthMaterial("test", "2099-01-01T00:00:00Z", server.url("/api").newBuilder().host("localhost").build().toString().trimEnd('/'))
            val store = object : AuthStore {
                override fun read() = auth
                override fun write(value: AuthMaterial) = Unit
                override fun clear() = Unit
            }
            val ready = CountDownLatch(1); val failure = CountDownLatch(1)
            val state = AtomicReference(RuntimeState())
            val socket = ChatSocket(MemohApi(client, Json { ignoreUnknownKeys = true; explicitNulls = false }, store), "b", "s", object : ChatSocketListener {
                override fun onRuntime(value: RuntimeState) { state.set(value); ready.countDown(); observe(value) }
                override fun onRejected(invocationId: String?, message: String) { failure.countDown() }
            }, callback = { it() }, snapshotTimeoutMillis = 1_000)
            try { socket.connect(); test(socket, ready, failure, state, prompts) }
            finally { socket.close(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }

    private fun snapshot(seq: Int = 0) = """{"type":"runtime_snapshot","session_id":"s","epoch":"e","seq":$seq,"snapshot":{"session_id":"s","epoch":"e","seq":$seq}}"""
    @Test fun `server closing reconnects once and does not replay a prompt`() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverCertificates.sslSocketFactory(), false); server.start()
            val opens = AtomicInteger(); val messages = AtomicInteger()
            val connectedTwice = CountDownLatch(2)
            val delivered = CountDownLatch(1)
            val payload = AtomicReference<JsonObject>()
            val firstConnected = CountDownLatch(1)
            repeat(3) {
                server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { opens.incrementAndGet() }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (text.contains("runtime_subscribe")) webSocket.send(snapshot())
                        if (text.contains("\"type\":\"message\"")) {
                            payload.set(Json.parseToJsonElement(text).jsonObject)
                            messages.incrementAndGet(); delivered.countDown()
                            webSocket.close(1001, "restart before acceptance")
                        }
                    }
                }))
            }
            val client = OkHttpClient.Builder().sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager).build()
            val auth = AuthMaterial("test", "2099-01-01T00:00:00Z", server.url("/api").newBuilder().host("localhost").build().toString().trimEnd('/'))
            val store = object : AuthStore {
                override fun read() = auth
                override fun write(value: AuthMaterial) = Unit
                override fun clear() = Unit
            }
            val socket = ChatSocket(MemohApi(client, Json { ignoreUnknownKeys = true }, store), "b", "s", object : ChatSocketListener {
                override fun onConnection(connected: Boolean) { if (connected) { firstConnected.countDown(); connectedTwice.countDown() } }
            }, callback = { it() })
            try {
                socket.connect(); socket.connect() // Duplicate connect is coalesced.
                assertTrue(firstConnected.await(5, TimeUnit.SECONDS))
                socket.sendMessage("", "i", listOf(ChatAttachment(name = "notes.txt", mime = "text/plain", base64 = "data:text/plain;base64,aGVsbG8=")), "model-picked", "remote:computer", "high")
                assertTrue(delivered.await(5, TimeUnit.SECONDS))
                assertEquals("model-picked", payload.get()["model_id"]!!.jsonPrimitive.content)
                assertEquals("remote:computer", payload.get()["workspace_target_id"]!!.jsonPrimitive.content)
                assertEquals("high", payload.get()["reasoning_effort"]!!.jsonPrimitive.content)
                assertEquals("", payload.get()["text"]!!.jsonPrimitive.content)
                val attachment = payload.get()["attachments"]!!.jsonArray.single().jsonObject
                assertEquals("file", attachment["type"]!!.jsonPrimitive.content)
                assertEquals("text/plain", attachment["mime"]!!.jsonPrimitive.content)
                assertEquals("notes.txt", attachment["name"]!!.jsonPrimitive.content)
                assertEquals("data:text/plain;base64,aGVsbG8=", attachment["base64"]!!.jsonPrimitive.content)
                assertTrue(connectedTwice.await(8, TimeUnit.SECONDS))
                assertEquals(2, opens.get())
                assertEquals(1, messages.get())
            } finally { socket.close(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }
}
