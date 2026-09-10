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
class SteeringLifecycleTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val approval = MessageBlock(1, "tool", name = "exec", approval = Approval("a", "pending", options = listOf(ApprovalOption("once", "Allow once", "allow_once"))))
    private val question = MessageBlock(1, "tool", name = "ask_user", userInput = UserInput("input", "pending", listOf(UserQuestion("q", "Which?", "single_select", listOf(UserOption("one", "One"), UserOption("two", "Two"))))))
    private class Harness(val app: AppState, val wire: AtomicReference<WebSocket>, val requests: LinkedBlockingQueue<JsonObject>, val run: AtomicReference<RuntimeRun>, val subscriptions: AtomicInteger, val scenario: androidx.test.core.app.ActivityScenario<MainActivity>?) {
        fun request() = requireNotNull(requests.poll(5, TimeUnit.SECONDS)) { "Missing client frame" }
        fun ack(request: JsonObject, applied: Boolean, code: String = "", session: String = "s", kind: String = request.getValue("type").jsonPrimitive.content) {
            wire.get().send(buildJsonObject {
                put("type", "control_ack"); put("session_id", session); put("run_id", "r"); put("control", kind)
                put("control_id", request.getValue("control_id")); put("applied", applied); if (code.isNotEmpty()) put("code", code)
            }.toString())
        }
    }
    private suspend fun withApp(block: MessageBlock, ledgerOnly: Boolean = false, useActivity: Boolean = false, queueSupported: Boolean = false, test: suspend Harness.() -> Unit) {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCertificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientCertificates = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        val wire = AtomicReference<WebSocket>()
        val requests = LinkedBlockingQueue<JsonObject>()
        val subscriptions = AtomicInteger()
        val run = AtomicReference(RuntimeRun("r", "t", invocation_id = "fixture-invocation", status = if (ledgerOnly) "completed" else "running", messages = if (ledgerOnly) emptyList() else listOf(block)))
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
                                put("steer_queue_supported", queueSupported); put("steer_supported", true); put("type", "runtime_snapshot"); put("session_id", "s"); put("epoch", "e"); put("seq", 1)
                                val snapshot = json.encodeToJsonElement(RuntimeSnapshot.serializer(), RuntimeSnapshot(session_id = "s", epoch = "e", seq = 1, current_run_view = run.get())).jsonObject
                                put("snapshot", if (!ledgerOnly) snapshot else JsonObject(snapshot.toMutableMap().apply {
                                    put("current_run_view", JsonObject(snapshot.getValue("current_run_view").jsonObject.toMutableMap().apply { put("messages", JsonNull) }))
                                }))
                            }.toString())
                        } else requests.put(event)
                    }
                })
                val body = when {
                    path.endsWith("/users/me") -> """{"id":"u","username":"fixture"}"""
                    path.endsWith("/bots") -> """{"items":[{"id":"b","name":"Memoh","current_user_permissions":["chat"]}]}"""
                    path.endsWith("/messages") && ledgerOnly -> """{"items":[{"turn_id":"t","role":"user","text":"previous question"},{"turn_id":"t","role":"assistant","text":"saved reply"}]}"""
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
        val pending = PendingOperationStore(application, "test_steering_pending").also { it.clear() }
        val original = application.container
        val steering = icu.minq.memoh.security.EncryptedSteeringStore(application, "test_steering_lifecycle")
        val storedKey = steeringKey(PendingPolicy.accountKey(auth.apiBase,"u"), "b", "s")
        steering.write(storedKey, null)
        val container = AppContainer(MemohApi(client, json, tokens), tokens, pending, steeringStore = steering)
        application.container = container
        val scenario = if (ledgerOnly || useActivity) androidx.test.core.app.ActivityScenario.launch<MainActivity>(android.content.Intent(application, MainActivity::class.java)) else null
        val viewModels = ViewModelStore()
        lateinit var app: AppState
        try {
            if (useActivity) scenario!!.onActivity { app = androidx.lifecycle.ViewModelProvider(it)[AppState::class.java] }
            else withContext(Dispatchers.Main) { app = AppState(application, container, SavedStateHandle()); viewModels.put("app", app); app.bootstrap() }
            withTimeout(8_000) { app.state.first { it.screen == Screen.Bots && !it.loading } }
            withContext(Dispatchers.Main) { app.selectBot(app.state.value.bots.single()) }
            withTimeout(8_000) { app.state.first { it.screen == Screen.Sessions && !it.loading } }
            withContext(Dispatchers.Main) { app.openSession(Session("s", "b")) }
            withTimeout(8_000) { app.state.first { it.connected && it.runtime.run != null && !it.loading && !it.composer.modelsLoading && !it.composer.modelChanging } }
            Harness(app, wire, requests, run, subscriptions, scenario).test()
        } finally {
            withContext(Dispatchers.Main) { viewModels.clear() }
            application.stopService(android.content.Intent(application, icu.minq.memoh.service.PendingReplyService::class.java))
            withTimeout(5000) { while (serviceRunning(application)) delay(40) }
            pending.clear()
            scenario?.close()
            steering.write(storedKey, null)
            application.container = original
            wire.get()?.close(1000, "fixture done"); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown(); server.close()
        }
    }
    private fun applicationPending() = ApplicationProvider.getApplicationContext<MemohApplication>().container.pendingStore.read()

    @Test fun selectedImageArrivesOnWireWithImageTypeAndUnchangedBytes() = runBlocking {
        withApp(MessageBlock(type="text"), ledgerOnly=true) {
            val resolver=ApplicationProvider.getApplicationContext<MemohApplication>().contentResolver
            val bitmap=android.graphics.Bitmap.createBitmap(16,16,android.graphics.Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLUE)
            val bytes=java.io.ByteArrayOutputStream().also { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }.toByteArray()
            bitmap.recycle()
            val uri=resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME,"memoh-vision-${java.util.UUID.randomUUID()}.png")
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE,"image/png")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,"Download/Memoh-test")
            })!!
            try {
                resolver.openOutputStream(uri)!!.use { it.write(bytes) }
                withContext(Dispatchers.Main) { assertTrue(app.beginFileSelection());app.attachFiles(listOf(uri)) }
                withTimeout(5000) { app.state.first { it.attachments.singleOrNull()?.payload != null } }
                withContext(Dispatchers.Main) { app.send("") }
                val frame=request()
                val attachment=frame.getValue("attachments").jsonArray.single().jsonObject
                assertEquals("image",attachment.getValue("type").jsonPrimitive.content)
                assertEquals("image/png",attachment.getValue("mime").jsonPrimitive.content)
                assertArrayEquals(bytes,java.util.Base64.getDecoder().decode(attachment.getValue("base64").jsonPrimitive.content.substringAfter(',')))
            } finally { resolver.delete(uri,null,null) }
        }
    }

    @Suppress("DEPRECATION")
    private fun serviceRunning(application: MemohApplication) = application.getSystemService(android.app.ActivityManager::class.java)
        .getRunningServices(100).any { it.service.className == icu.minq.memoh.service.PendingReplyService::class.java.name }

    @Test fun consecutiveSupplementsPersistAndReconcileEveryReceiptWithoutReplay() = runBlocking {
        withApp(MessageBlock(1,"text",content="working"), useActivity=true, queueSupported=true) {
            val texts = listOf("first requirement", "second requirement", "third requirement")
            withContext(Dispatchers.Main) { texts.forEach { app.editDraft(it); app.steer() } }
            val frames = texts.map { request() }
            val ids = frames.map { it.getValue("control_id").jsonPrimitive.content }
            assertEquals(3,ids.toSet().size)
            frames.forEachIndexed { index, frame ->
                assertTrue(frame.getValue("queue").jsonPrimitive.boolean)
                assertEquals(texts[index],frame.getValue("text").jsonPrimitive.content)
            }
            assertEquals(texts,app.state.value.steeringQueue.map { it.text })
            // Acknowledgements may arrive out of order and must update their own record.
            frames.reversed().forEach { ack(it,true) }
            withTimeout(5000) { app.state.first { it.steeringQueue.size==3 && it.steeringQueue.all { item -> item.status=="queued" } } }
            val container=ApplicationProvider.getApplicationContext<MemohApplication>().container
            val key=steeringKey(PendingPolicy.accountKey(container.api.currentAuth()!!.apiBase,"u"),"b","s")
            val reopened=icu.minq.memoh.security.EncryptedSteeringStore(ApplicationProvider.getApplicationContext(),"test_steering_lifecycle")
            assertEquals(texts,reopened.readQueue(key).map { it.text })
            scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            run.set(run.get().copy(steer_queue=ids.mapIndexed { index,id -> SteerState(id,"applied",texts[index]) }))
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            withTimeout(8000) { app.state.first { it.connected && it.steeringQueue.size==3 && it.steeringQueue.all { item -> item.status=="applied" } } }
            assertTrue(reopened.readQueue(key).all { it.status=="applied" })
            assertNull(requests.poll(200,TimeUnit.MILLISECONDS))
            withContext(Dispatchers.Main) { app.dismissSteering(ids[1]) }
            assertEquals(listOf(ids[0],ids[2]),app.state.value.steeringQueue.map { it.id })
        }
    }

    @Test fun ledgerOnlyOldSessionReconnectsPreservesHistoryAndCanSubmit() = runBlocking {
        withApp(MessageBlock(type="text"), ledgerOnly = true) {
            assertEquals("completed", app.state.value.runtime.run?.status)
            assertEquals(2, reconciledHistory(app.state.value.history, app.state.value.runtime.run).size)
            withContext(Dispatchers.Main) { app.editDraft("continue old session"); app.send(app.state.value.draft) }
            assertNull("Unexpected send rejection: ${app.state.value.error}", app.state.value.error)
            val frame = request()
            assertEquals("message", frame["type"]!!.jsonPrimitive.content)
            assertEquals("continue old session", frame["text"]!!.jsonPrimitive.content)
            val sent = requireNotNull(applicationPending())
            withTimeout(5000) { while (!icu.minq.memoh.service.PendingReplyService.isMonitoring(sent)) delay(40) }
        }
    }
    @Test fun activityForegroundRefreshesMissedReceiptWithoutResendingSupplement() = runBlocking {
        withApp(MessageBlock(1,"text",content="working"), useActivity = true) {
            withContext(Dispatchers.Main) { app.editDraft("supplement"); app.steer() }
            val request = request()
            val id = request.getValue("control_id").jsonPrimitive.content
            ack(request,true)
            withTimeout(5000) { app.state.first { it.steering?.status == "queued" } }
            scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            // The server consumes the supplement while no delivery event reaches the old socket.
            val oldWire = wire.get()
            run.set(run.get().copy(steer=SteerState(id,"applied","supplement")))
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            withTimeout(8000) { app.state.first { it.connected && it.steering?.status == "applied" } }
            assertNotSame(oldWire,wire.get())
            assertNull(requests.poll(200,TimeUnit.MILLISECONDS))
            assertEquals("",app.state.value.draft)
        }
    }

    @Test fun backgroundMonitorSavesConsumptionBeforeFinishingAndReopeningChat() = runBlocking {
        withApp(MessageBlock(1,"text",content="working"), useActivity = true) {
            val application=ApplicationProvider.getApplicationContext<MemohApplication>()
            val container=application.container
            val accountKey=PendingPolicy.accountKey(container.api.currentAuth()!!.apiBase,"u")
            val key=steeringKey(accountKey,"b","s")
            withContext(Dispatchers.Main) { app.editDraft("supplement"); app.steer() }
            val request=request()
            val id=request.getValue("control_id").jsonPrimitive.content
            ack(request,true)
            withTimeout(5000) { app.state.first { it.steering?.status=="queued" } }
            val previousSubscriptions=subscriptions.get()
            val pending=PendingOperation("b","s","fixture-invocation",accountKey,System.currentTimeMillis(),PendingPhase.ACCEPTED,runId="r")
            scenario!!.onActivity {
                container.pendingStore.start(pending)
                androidx.core.content.ContextCompat.startForegroundService(it,icu.minq.memoh.service.PendingReplyService.intent(it,pending))
                app.back()
            }
            withTimeout(8000) { while(subscriptions.get()<=previousSubscriptions || !icu.minq.memoh.service.PendingReplyService.isMonitoring(pending)) delay(40) }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            run.set(run.get().copy(status="completed",steer=SteerState(id,"applied","supplement")))
            wire.get().send(buildJsonObject {
                put("type","runtime_snapshot");put("session_id","s");put("epoch","e");put("seq",2)
                put("snapshot",json.encodeToJsonElement(RuntimeSnapshot.serializer(),RuntimeSnapshot(session_id="s",epoch="e",seq=2,current_run_view=run.get())))
            }.toString())
            withTimeout(8000) { while(serviceRunning(application) || container.pendingStore.read()!=null) delay(40) }
            val reopenedStore=icu.minq.memoh.security.EncryptedSteeringStore(application,"test_steering_lifecycle")
            assertEquals("applied",reopenedStore.read(key)?.status)
            // A later ledger-only snapshot cannot erase the receipt saved by the monitor.
            run.set(run.get().copy(steer=null,messages=emptyList()))
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            withContext(Dispatchers.Main) { app.openSession(Session("s","b")) }
            withTimeout(8000) { app.state.first { it.connected && !it.loading && it.steering?.status=="applied" } }
            assertNull(requests.poll(200,TimeUnit.MILLISECONDS))
        }
    }

    @Test fun confirmedSharedReceiptSurvivesLateQueuedAckAndSnapshot() = runBlocking {
        withApp(MessageBlock(1,"text",content="working")) {
            withContext(Dispatchers.Main) { app.editDraft("supplement"); app.steer() }
            val frame=request()
            val id=frame.getValue("control_id").jsonPrimitive.content
            val container=ApplicationProvider.getApplicationContext<MemohApplication>().container
            val key=steeringKey(PendingPolicy.accountKey(container.api.currentAuth()!!.apiBase,"u"),"b","s")
            withContext(Dispatchers.Main) { container.steeringStore.observe(key,run.get().copy(steer=SteerState(id,"applied"))) }
            withTimeout(5000) { app.state.first { it.steering?.status=="applied" } }
            ack(frame,true)
            run.set(run.get().copy(steer=SteerState(id,"queued")))
            withContext(Dispatchers.Main) { app.onForeground() }
            withTimeout(5000) { app.state.first { it.connected && it.runtime.run?.steer?.status=="queued" } }
            assertEquals("applied",app.state.value.steering?.status)
            assertEquals("applied",container.steeringStore.read(key)?.status)
            assertNull(requests.poll(200,TimeUnit.MILLISECONDS))
        }
    }

    @Test fun supplementWaitsForConsumptionAndIsNeverReplayedOnReconnect() = runBlocking {
        withApp(MessageBlock(1, "text", content = "working")) {
            withContext(Dispatchers.Main) { app.editDraft("请加上中文说明"); app.steer(); app.steer() }
            val frame = request()
            assertEquals("steer", frame["type"]!!.jsonPrimitive.content)
            assertEquals("r", frame["run_id"]!!.jsonPrimitive.content)
            assertEquals("请加上中文说明", frame["text"]!!.jsonPrimitive.content)
            val id = frame["control_id"]!!.jsonPrimitive.content
            assertEquals("pending", app.state.value.steering!!.status)
            assertEquals("", app.state.value.draft)
            ack(frame, true)
            withTimeout(5000) { app.state.first { it.steering?.status == "queued" } }
            run.set(run.get().copy(steer = SteerState(id, "applied", "请加上中文说明")))
            wire.get().close(1000, "reconnect fixture")
            withTimeout(8000) { app.state.first { it.steering?.status == "applied" && it.connected } }
            assertNull(requests.poll(200, TimeUnit.MILLISECONDS))
            withContext(Dispatchers.Main) { app.dismissSteering() }
            assertNull(app.state.value.steering)
        }
    }
    @Test fun rejectedSupplementCanBeRecoveredWithoutLosingTheNewDraft() = runBlocking {
        withApp(MessageBlock(1, "text", content = "working")) {
            withContext(Dispatchers.Main) { app.editDraft("supplement"); app.steer(); app.editDraft("next draft") }
            ack(request(), false, "steer_rejected")
            withTimeout(5000) { app.state.first { it.steering?.status == "rejected" } }
            withContext(Dispatchers.Main) { app.recoverSteering() }
            assertEquals("next draft\nsupplement", app.state.value.draft)
            assertTrue(app.state.value.connected)
        }
    }
    @Test fun legacyEncryptedSupplementMigratesToQueueWithoutLosingContent() {
        val context=ApplicationProvider.getApplicationContext<MemohApplication>()
        val storageName="test_steering_migration"
        val store=icu.minq.memoh.security.EncryptedSteeringStore(context,storageName)
        val key="migration:bot:session"
        val original=StoredSteering("old","r","saved before upgrade","queued")
        try {
            store.write(key,original) // Creates the same key used by v0.2.9.
            val slot=java.security.MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }
            val secret=java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.getKey("memoh.steering.$storageName.aes.v1",null)
            val cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply { init(javax.crypto.Cipher.ENCRYPT_MODE,secret);updateAAD(slot.toByteArray()) }
            val encrypted=cipher.doFinal(Json.encodeToString(StoredSteering.serializer(),original).toByteArray())
            val encoded=android.util.Base64.encodeToString(cipher.iv,android.util.Base64.NO_WRAP)+":"+android.util.Base64.encodeToString(encrypted,android.util.Base64.NO_WRAP)
            check(context.getSharedPreferences(storageName,0).edit().putString(slot,encoded).commit())
            assertEquals(listOf(original),store.readQueue(key))
            val queue=store.readQueue(key)+StoredSteering("new","r","new supplement")
            store.writeQueue(key,queue)
            assertEquals(queue,icu.minq.memoh.security.EncryptedSteeringStore(context,storageName).readQueue(key))
        } finally { store.write(key,null) }
    }
    @Test fun encryptedRecordSurvivesNewStoreAndIsIsolatedByAccountAndSession() {
        val application = ApplicationProvider.getApplicationContext<MemohApplication>()
        val store = icu.minq.memoh.security.EncryptedSteeringStore(application, "test_steering_store")
        val value = StoredSteering("id", "run", "sensitive supplement", "queued")
        try {
            store.write("account:bot:session", value)
            assertEquals(value, icu.minq.memoh.security.EncryptedSteeringStore(application, "test_steering_store").read("account:bot:session"))
            assertNull(store.read("other:bot:session"))
            assertFalse(application.getSharedPreferences("test_steering_store", 0).all.values.toString().contains(value.text))
        } finally { store.write("account:bot:session", null) }
    }
}
