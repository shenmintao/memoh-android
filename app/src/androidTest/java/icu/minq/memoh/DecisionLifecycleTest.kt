package icu.minq.memoh

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import icu.minq.memoh.data.*
import icu.minq.memoh.model.*
import icu.minq.memoh.network.MemohApi
import icu.minq.memoh.security.AuthStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.mockwebserver.*
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class DecisionLifecycleTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val approval = MessageBlock(1, "tool", name = "exec", approval = Approval("a", "pending", options = listOf(ApprovalOption("once", "Allow once", "allow_once"))))
    private val question = MessageBlock(1, "tool", name = "ask_user", userInput = UserInput("input", "pending", listOf(UserQuestion("q", "Which?", "single_select", listOf(UserOption("one", "One"), UserOption("two", "Two"))))))
    private class Harness(val app: AppState, val wire: AtomicReference<WebSocket>, val requests: LinkedBlockingQueue<JsonObject>, val run: AtomicReference<RuntimeRun>, val subscriptions: AtomicInteger) {
        fun request() = requireNotNull(requests.poll(5, TimeUnit.SECONDS)) { "Missing client frame" }
        fun ack(request: JsonObject, applied: Boolean, code: String = "", session: String = "s", kind: String = request.getValue("type").jsonPrimitive.content) {
            wire.get().send(buildJsonObject {
                put("type", "control_ack"); put("session_id", session); put("run_id", "r"); put("control", kind)
                put("control_id", request.getValue("control_id")); put("applied", applied); if (code.isNotEmpty()) put("code", code)
            }.toString())
        }
    }
    private suspend fun withApp(block: MessageBlock, test: suspend Harness.() -> Unit) {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        val wire = AtomicReference<WebSocket>()
        val requests = LinkedBlockingQueue<JsonObject>()
        val subscriptions = AtomicInteger()
        val run = AtomicReference(RuntimeRun("r", "t", status = "waiting_decision", messages = listOf(block)))
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                if (path.endsWith("/ws")) return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) { wire.set(webSocket) }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val event = json.parseToJsonElement(text).jsonObject
                        if (event["type"]?.jsonPrimitive?.content == "runtime_subscribe") {
                            subscriptions.incrementAndGet()
                            webSocket.send(buildJsonObject {
                                put("type", "runtime_snapshot"); put("session_id", "s"); put("epoch", "e"); put("seq", 1)
                                put("snapshot", json.encodeToJsonElement(RuntimeSnapshot.serializer(), RuntimeSnapshot(session_id = "s", epoch = "e", seq = 1, current_run_view = run.get())))
                            }.toString())
                        } else requests.put(event)
                    }
                })
                val body = when {
                    path.endsWith("/users/me") -> """{"id":"u","username":"fixture"}"""
                    path.endsWith("/bots") -> """{"items":[{"id":"b","name":"Memoh","current_user_permissions":["chat"]}]}"""
                    path.endsWith("/settings") -> "{}"
                    path.endsWith("/models") -> "[]"
                    else -> """{"items":[]}"""
                }
                return MockResponse().setHeader("Content-Type", "application/json").setBody(body)
            }
        }
        server.start()
        val client = OkHttpClient.Builder().sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager).build()
        val auth = AuthMaterial("fixture", "2099-01-01T00:00:00Z", server.url("/api").toString().trimEnd('/'), "u")
        val tokens = object : AuthStore {
            override fun read() = auth
            override fun write(value: AuthMaterial) = Unit
            override fun clear() = Unit
        }
        val application = ApplicationProvider.getApplicationContext<MemohApplication>()
        val pending = PendingOperationStore(application, "test_decision_pending").also { it.clear() }
        val viewModels = ViewModelStore()
        lateinit var app: AppState
        try {
            withContext(Dispatchers.Main) { app = AppState(application, AppContainer(MemohApi(client, json, tokens), tokens, pending), SavedStateHandle()); viewModels.put("app", app); app.bootstrap() }
            withTimeout(8_000) { app.state.first { it.screen == Screen.Bots && !it.loading } }
            withContext(Dispatchers.Main) { app.selectBot(app.state.value.bots.single()) }
            withTimeout(8_000) { app.state.first { it.screen == Screen.Sessions && !it.loading } }
            withContext(Dispatchers.Main) { app.openSession(Session("s", "b")) }
            withTimeout(8_000) { app.state.first { it.connected && it.runtime.run != null && !it.loading } }
            Harness(app, wire, requests, run, subscriptions).test()
        } finally {
            withContext(Dispatchers.Main) { viewModels.clear() }
            pending.clear(); wire.get()?.close(1000, "fixture done"); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown(); server.close()
        }
    }
    @Test fun approvalIsSentOnceAndAckIsScopedAndConfirmedAgainstStaleSnapshots() = runBlocking {
        withApp(approval) {
            withContext(Dispatchers.Main) { app.decide("missing", true, null); app.decide("a", true, "unknown"); app.decide("a", true, "once"); app.decide("a", true, "once") }
            val request = request()
            assertEquals("tool_approval_response", request["type"]!!.jsonPrimitive.content)
            assertEquals("a", request["decision_id"]!!.jsonPrimitive.content)
            assertEquals("once", request["option_id"]!!.jsonPrimitive.content)
            ack(request, true, session = "other")
            ack(request, true, kind = "abort")
            wire.get().send("""{"type":"error","session_id":"other","run_id":"r","message":"other session failed"}""")
            delay(150)
            assertTrue(app.state.value.connected)
            assertNull(app.state.value.connectionFailure)
            assertTrue(app.state.value.pendingControls.isNotEmpty())
            ack(request, true)
            withTimeout(5_000) { app.state.first { it.pendingControls.isEmpty() && "approval:a" in it.resolvedDecisions } }
            withTimeout(5_000) { while (subscriptions.get() < 2) delay(10) }
            delay(100)
            assertTrue(app.state.value.pendingDecisions().isEmpty())
            withContext(Dispatchers.Main) { app.decide("a", true, "once") }
            assertNull(requests.poll(150, TimeUnit.MILLISECONDS))
        }
    }
    @Test fun failedRejectionCanRetryAndAlreadyFinishedAckIsNotAnError() = runBlocking {
        withApp(approval) {
            withContext(Dispatchers.Main) { app.decide("a", false, null, "先说明影响") }
            val first = request()
            assertEquals("先说明影响", first["reason"]!!.jsonPrimitive.content)
            ack(first, false, "tool_approval_operation_failed")
            withTimeout(5_000) { app.state.first { it.pendingControls.isEmpty() && it.error != null } }
            assertTrue(app.state.value.connected)
            assertEquals(1, app.state.value.pendingDecisions().size)
            withContext(Dispatchers.Main) { app.back(); app.openSession(Session("s", "b")) }
            withTimeout(5_000) { app.state.first { it.connected && !it.loading && it.pendingDecisions().size == 1 } }
            withContext(Dispatchers.Main) { app.clearError(); app.decide("a", false, null) }
            val second = request()
            assertNotEquals(first["control_id"], second["control_id"])
            ack(second, false)
            withTimeout(5_000) { app.state.first { it.pendingControls.isEmpty() && it.pendingDecisions().isEmpty() } }
            assertNull(app.state.value.error)
        }
    }
    @Test fun answersUseUserInputProtocolAndDisconnectedRequestsAreNotReplayed() = runBlocking {
        withApp(question) {
            withContext(Dispatchers.Main) { app.answer("input", emptyList(), false) }
            assertNull(requests.poll(100, TimeUnit.MILLISECONDS))
            withContext(Dispatchers.Main) { app.clearError(); app.answer("input", listOf(UserAnswer("q", optionIds = listOf("two"))), false) }
            val request = request()
            assertEquals("user_input_response", request["type"]!!.jsonPrimitive.content)
            assertEquals("two", request["answers"]!!.jsonArray.single().jsonObject["option_ids"]!!.jsonArray.single().jsonPrimitive.content)
            val before = subscriptions.get()
            wire.get().close(1001, "fixture reconnect")
            withTimeout(8_000) { while (subscriptions.get() <= before) delay(20) }
            withTimeout(5_000) { app.state.first { it.connected && it.pendingControls.isEmpty() } }
            assertNull(requests.poll(150, TimeUnit.MILLISECONDS))
            assertEquals(1, app.state.value.pendingDecisions().size)
            withContext(Dispatchers.Main) { app.clearError(); app.answer("input", emptyList(), true) }
            val cancel = request()
            assertTrue(cancel["canceled"]!!.jsonPrimitive.boolean)
            assertEquals("user_canceled", cancel["reason"]!!.jsonPrimitive.content)
            run.set(run.get().copy(status = "completed", messages = listOf(question.copy(userInput = question.userInput!!.copy(status = "canceled")))))
            ack(cancel, true)
            withTimeout(5_000) { app.state.first { it.runtime.run.isTerminal() } }
            assertTrue(app.state.value.pendingDecisions().isEmpty())
        }
    }
    @Test fun runtimeResolutionBeforeAckUnlocksNextDecisionAndControlErrorDoesNotDisconnectChat() = runBlocking {
        withApp(approval) {
            withContext(Dispatchers.Main) { app.decide("a", true, "once") }
            val first = request()
            val next = approval.copy(id = 2, approval = Approval("next", "pending"))
            run.set(run.get().copy(messages = listOf(approval.copy(approval = approval.approval!!.copy(status = "approved")), next)))
            wire.get().send(buildJsonObject {
                put("type", "runtime_snapshot"); put("session_id", "s"); put("epoch", "e"); put("seq", 1)
                put("snapshot", json.encodeToJsonElement(RuntimeSnapshot.serializer(), RuntimeSnapshot(session_id = "s", epoch = "e", seq = 1, current_run_view = run.get())))
            }.toString())
            withTimeout(5_000) { app.state.first { it.pendingControls.isEmpty() && it.pendingDecisions().singleOrNull()?.approval?.approvalId == "next" } }
            ack(first, false, "late_error")
            withContext(Dispatchers.Main) { app.decide("next", true, null) }
            val second = request()
            wire.get().send(buildJsonObject {
                put("type", "error"); put("session_id", "s"); put("run_id", "r"); put("control_id", second.getValue("control_id")); put("message", "fixture error")
            }.toString())
            withTimeout(5_000) { app.state.first { it.pendingControls.isEmpty() && it.error != null } }
            assertTrue(app.state.value.connected)
            assertNull(app.state.value.connectionFailure)
        }
    }
}
