package icu.minq.memoh

import android.content.ContentValues
import android.content.Intent
import android.content.ClipData
import android.app.Activity
import android.app.Instrumentation
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import icu.minq.memoh.data.*
import icu.minq.memoh.model.*
import icu.minq.memoh.network.MemohApi
import icu.minq.memoh.security.AuthStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer

@RunWith(AndroidJUnit4::class)
class ComposerLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun systemPickerContractDeliversMultipleDocumentsToTheActualActivity() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        withApp { application, _, _ ->
            val resolver = application.contentResolver
            val names = listOf("选择测试一-${UUID.randomUUID()}.txt", "选择测试二-${UUID.randomUUID()}.txt")
            val uris = names.map { name ->
                resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Memoh-test")
                })!!.also { uri -> resolver.openOutputStream(uri)!!.use { it.write("fixture".toByteArray()) } }
            }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            var picked: Intent? = null
            val monitor = object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if (intent.action != Intent.ACTION_OPEN_DOCUMENT) return null
                    picked = intent
                    return Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().apply {
                        clipData = ClipData.newRawUri("fixtures", uris[0]).apply { addItem(ClipData.Item(uris[1])) }
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    })
                }
            }
            instrumentation.addMonitor(monitor)
            try {
                ActivityScenario.launch<MainActivity>(Intent(application, MainActivity::class.java)
                    .putExtra("bot_id", "b").putExtra("session_id", "s")).use {
                    compose.waitUntil(8_000) { compose.onAllNodesWithContentDescription("添加文件").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithContentDescription("添加文件").performClick()
                    compose.waitUntil(5_000) { compose.onAllNodesWithText(names[0]).fetchSemanticsNodes().isNotEmpty() }
                    assertEquals(Intent.ACTION_OPEN_DOCUMENT, picked?.action)
                    assertEquals("*/*", picked?.type)
                    assertTrue(picked?.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false) == true)
                    assertTrue(picked?.categories?.contains(Intent.CATEGORY_OPENABLE) == true)
                    compose.onNodeWithText(names[0]).assertExists()
                    compose.onNodeWithContentDescription("移除附件 ${names[0]}").performClick()
                    compose.waitUntil(5_000) { compose.onAllNodesWithText(names[1]).fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithText(names[1]).assertExists()
                    compose.onAllNodesWithText("文件将随消息发送 · 最多 10 个，合计 8 MB").assertCountEquals(1)
                }
            } finally {
                instrumentation.removeMonitor(monitor)
                uris.forEach { resolver.delete(it, null, null) }
            }
        }
    }
    @Test fun filesAndSelectionsStayWithTheirSessionAndStalePickerCannotAttachElsewhere() = runBlocking {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        withApp { application, app, _ ->
            val resolver = application.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "memoh-lifecycle-${UUID.randomUUID()}.txt")
                put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Memoh-test")
            })!!
            try {
                resolver.openOutputStream(uri)!!.use { it.write("attachment fixture".toByteArray()) }
                open(app, Session("s1", "b"))
                withContext(Dispatchers.Main) {
                    app.selectModel("m2"); app.selectReasoning("high"); app.selectDevice("remote:online")
                    app.selectDevice("remote:offline")
                    assertTrue(app.beginFileSelection()); app.attachFiles(listOf(uri))
                }
                withTimeout(5_000) { app.state.first { it.attachments.singleOrNull()?.payload != null } }
                assertEquals("remote:online", app.state.value.composer.targetId)
                open(app, Session("s2", "b"))
                assertTrue(app.state.value.attachments.isEmpty())
                assertEquals("", app.state.value.composer.modelId)
                assertEquals("", app.state.value.composer.effectiveReasoning())
                assertEquals("native", app.state.value.composer.targetId)
                withContext(Dispatchers.Main) { assertTrue(app.beginFileSelection()) }
                open(app, Session("s3", "b"))
                withContext(Dispatchers.Main) { app.attachFiles(listOf(uri)) }
                assertTrue(app.state.value.attachments.isEmpty())
                open(app, Session("s1", "b"))
                assertNotNull(app.state.value.attachments.single().payload)
                assertEquals("m2", app.state.value.composer.modelId)
                assertEquals("high", app.state.value.composer.effectiveReasoning())
                assertEquals("remote:online", app.state.value.composer.targetId)
                withContext(Dispatchers.Main) { app.logout() }
                assertTrue(app.state.value.attachments.isEmpty())
                assertEquals(Screen.Login, app.state.value.screen)
            } finally { resolver.delete(uri, null, null) }
        }
    }

    @Test fun ACPModelChangeRequiresServerConfirmationAndCanRecoverAfterFailure() = runBlocking {
        withApp { _, app, failPatch ->
            open(app, Session("s", "b", runtimeType = "acp_agent"))
            assertEquals("m1", app.state.value.composer.modelId)
            withContext(Dispatchers.Main) { app.selectModel("m2") }
            withTimeout(5_000) { app.state.first { it.composer.modelUncertain && !it.composer.modelChanging } }
            assertEquals("m1", app.state.value.composer.modelId)
            withContext(Dispatchers.Main) { app.refreshModels() }
            withTimeout(5_000) { app.state.first { !it.composer.modelsLoading && !it.composer.modelUncertain } }
            failPatch.set(false)
            withContext(Dispatchers.Main) { app.selectModel("m2") }
            withTimeout(5_000) { app.state.first { it.composer.modelId == "m2" && !it.composer.modelChanging } }
            assertNull(app.state.value.composer.modelsError)
            assertTrue(app.state.value.composer.targetId.isBlank())
        }
    }

    private suspend fun open(app: AppState, session: Session) {
        withContext(Dispatchers.Main) { app.openSession(session) }
        withTimeout(8_000) { app.state.first { it.session?.id == session.id && !it.loading && !it.composer.modelsLoading && !it.composer.targetsLoading } }
    }

    @Test fun ACPReasoningRequiresConfirmationAndRefreshesAfterFailure() = runBlocking {
        withApp { _, app, failPatch ->
            open(app, Session("s", "b", runtimeType = "acp_agent"))
            assertEquals("medium", app.state.value.composer.effectiveReasoning())
            withContext(Dispatchers.Main) { app.selectReasoning("high") }
            withTimeout(5_000) { app.state.first { it.composer.modelUncertain && !it.composer.modelChanging } }
            assertEquals("medium", app.state.value.composer.effectiveReasoning())
            withContext(Dispatchers.Main) { app.refreshModels() }
            withTimeout(5_000) { app.state.first { !it.composer.modelsLoading && !it.composer.modelUncertain } }
            failPatch.set(false)
            withContext(Dispatchers.Main) { app.selectReasoning("high") }
            withTimeout(5_000) { app.state.first { it.composer.effectiveReasoning() == "high" && !it.composer.modelChanging } }
            assertNull(app.state.value.composer.modelsError)
        }
    }

    @Test fun modelPreferenceSurvivesProcessRestart() = runBlocking {
        val phase = InstrumentationRegistry.getArguments().getString("modelPersistencePhase") ?: "roundtrip"
        val application = ApplicationProvider.getApplicationContext<MemohApplication>()
        val name = if (phase == "roundtrip") "test_model_${UUID.randomUUID()}" else "test_model_process"
        fun disk() = PreferencesModelSelectionStore(application, name)
        try {
            if (phase != "read") withApp(modelStore = disk()) { _, app, _ ->
                open(app, Session("persisted", "b"))
                withContext(Dispatchers.Main) { app.selectModel("m2"); app.selectReasoning("high") }
                assertEquals("m2", app.state.value.composer.modelId)
                assertEquals("high", app.state.value.composer.effectiveReasoning())
            }
            if (phase != "write") withApp(modelStore = disk()) { _, app, _ ->
                open(app, Session("persisted", "b"))
                assertEquals("m2", app.state.value.composer.modelId)
                assertEquals("high", app.state.value.composer.effectiveReasoning())
                assertEquals("模型二 · 高", app.state.value.composer.composerLabel())
                withContext(Dispatchers.Main) { app.refreshModels() }
                withTimeout(5_000) { app.state.first { !it.composer.modelsLoading } }
                assertEquals("m2", app.state.value.composer.modelId)
            }
        } finally { if (phase != "write") application.getSharedPreferences(name, 0).edit().clear().commit() }
    }

    @Test fun savedSelectionIsIsolatedAndFollowingDefaultIsRemembered() = runBlocking {
        val store = MemoryModelSelectionStore()
        val session = Session("persisted", "b")
        withApp(modelStore = store) { _, app, _ ->
            open(app, session)
            withContext(Dispatchers.Main) { app.selectModel("m2"); app.selectReasoning("high"); app.logout() }
        }
        withApp(modelStore = store, userId = "other") { _, app, _ -> open(app, session); assertEquals("", app.state.value.composer.modelId) }
        withApp(modelStore = store, base = "https://other.invalid/api") { _, app, _ -> open(app, session); assertEquals("", app.state.value.composer.modelId) }
        withApp(modelStore = store) { _, app, _ ->
            open(app, session); assertEquals("m2", app.state.value.composer.modelId)
            open(app, session.copy(id = "other")); assertEquals("", app.state.value.composer.modelId)
            open(app, session)
            withContext(Dispatchers.Main) { app.selectModel("") }
        }
        withApp(modelStore = store) { _, app, _ -> open(app, session); assertEquals("", app.state.value.composer.modelId); assertEquals("模型一", app.state.value.composer.modelLabel()) }
    }

    @Test fun unavailableSavedModelFallsBackAndDiskFailureDoesNotClaimSuccess() = runBlocking {
        val store = MemoryModelSelectionStore()
        val session = Session("persisted", "b")
        withApp(modelStore = store) { _, app, _ -> open(app, session); withContext(Dispatchers.Main) { app.selectModel("m2") } }
        withApp(modelStore = store, secondModelAvailable = false) { _, app, _ ->
            open(app, session)
            assertEquals("", app.state.value.composer.modelId)
            assertNotNull(app.state.value.composer.modelsError)
        }
        withApp(modelStore = store) { _, app, _ -> open(app, session); assertEquals("", app.state.value.composer.modelId) }
        val failing = object : ModelSelectionStore {
            override fun read(key: String): SavedModelSelection? = null
            override fun write(key: String, selection: SavedModelSelection) { throw java.io.IOException("fixture") }
        }
        withApp(modelStore = failing) { _, app, _ ->
            open(app, session)
            withContext(Dispatchers.Main) { app.selectModel("m2") }
            assertEquals("", app.state.value.composer.modelId)
            assertEquals("无法记住模型设置，请重试", app.state.value.error)
        }
    }

    @Test fun directAndACPSelectionsRestoreAfterClientAndRuntimeRecreation() = runBlocking {
        val store = MemoryModelSelectionStore()
        val direct = Session("direct", "b", runtimeType = "codex", botAgentId = "agent")
        val acp = Session("acp", "b", runtimeType = "acp_agent")
        withApp(modelStore = store) { _, app, failPatch ->
            open(app, direct)
            withContext(Dispatchers.Main) { app.selectModel("m2"); app.selectReasoning("high") }
            open(app, acp); failPatch.set(false)
            withContext(Dispatchers.Main) { app.selectModel("m2") }
            withTimeout(5_000) { app.state.first { !it.composer.modelChanging && it.composer.modelId == "m2" } }
            withContext(Dispatchers.Main) { app.selectReasoning("high") }
            withTimeout(5_000) { app.state.first { !it.composer.modelChanging && it.composer.effectiveReasoning() == "high" } }
        }
        withApp(modelStore = store) { _, app, failPatch ->
            open(app, direct)
            assertEquals("m2", app.state.value.composer.modelId)
            assertEquals("high", app.state.value.composer.effectiveReasoning())
            open(app, acp)
            assertTrue(app.state.value.composer.modelUncertain)
            failPatch.set(false)
            withContext(Dispatchers.Main) { app.refreshModels() }
            withTimeout(5_000) { app.state.first { !it.composer.modelsLoading && !it.composer.modelUncertain } }
            assertEquals("m2", app.state.value.composer.modelId)
            assertEquals("high", app.state.value.composer.effectiveReasoning())
        }
    }

    private suspend fun withApp(modelStore: ModelSelectionStore = MemoryModelSelectionStore(), userId: String = "u",
        base: String = "https://test.invalid/api", secondModelAvailable: Boolean = true,
        test: suspend (MemohApplication, AppState, AtomicBoolean) -> Unit) {
        val application = ApplicationProvider.getApplicationContext<MemohApplication>()
        val failPatch = AtomicBoolean(true)
        val acpModel = AtomicReference("m1")
        val acpEffort = AtomicReference("medium")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            val isPatch = chain.request().method == "PATCH"
            val code = if (path.endsWith("/ws")) 503 else if (isPatch && failPatch.get()) 503 else 200
            if (isPatch && code == 200) {
                val buffer = Buffer().also { chain.request().body!!.writeTo(it) }
                val input = Json.parseToJsonElement(buffer.readUtf8()).jsonObject
                input["model_id"]?.jsonPrimitive?.content?.let(acpModel::set)
                input["reasoning_effort"]?.jsonPrimitive?.content?.let(acpEffort::set)
            }
            val body = when {
                path.endsWith("/users/me") -> """{"id":"$userId","username":"fixture"}"""
                path.endsWith("/bots") -> """{"items":[{"id":"b","name":"Memoh","current_user_permissions":["manage"]}]}"""
                path.endsWith("/settings") -> """{"chat_model_id":"m1"}"""
                path.endsWith("/sessions/s") -> """{"id":"s","bot_id":"b"}"""
                path.contains("/agents/") && path.endsWith("/models") -> """{"configured_model_id":"m1","models":[{"id":"m1"},{"id":"m2","reasoning_efforts":[{"id":"medium"},{"id":"high"}],"default_reasoning_effort":"medium"}]}"""
                path.endsWith("/models") -> if (secondModelAvailable) """[{"id":"m1","name":"模型一","type":"chat"},{"id":"m2","name":"模型二","type":"chat","reasoning":{"supported":true,"efforts":["medium","high"],"default_effort":"medium"}}]""" else """[{"id":"m1","name":"模型一","type":"chat"}]"""
                path.endsWith("/workspace-targets") -> """{"targets":[{"target_id":"native","kind":"native","primary":true},{"target_id":"remote:online","kind":"remote","name":"办公电脑","online":true,"status":"online"},{"target_id":"remote:offline","kind":"remote","online":false}]}"""
                path.endsWith("/acp-runtime") || path.contains("/acp-runtime/") -> """{"runtime_id":"r","models":{"supported":true,"current_model_id":"${acpModel.get()}","available_models":[{"id":"m1","name":"模型一"},{"id":"m2","name":"模型二"}]},"reasoning":{"supported":true,"current_effort":"${acpEffort.get()}","available_efforts":[{"id":"medium"},{"id":"high"}]}}"""
                else -> """{"items":[]}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val tokens = object : AuthStore {
            private var value: AuthMaterial? = AuthMaterial("fixture", "2099-01-01T00:00:00Z", base, userId)
            override fun read() = value
            override fun write(value: AuthMaterial) { this.value = value }
            override fun clear() { value = null }
        }
        val pending = PendingOperationStore(application, "test_composer_pending").also { it.clear() }
        val original = application.container
        val container = AppContainer(MemohApi(client, Json { ignoreUnknownKeys = true }, tokens), tokens, pending, modelSelectionStore = modelStore)
        application.container = container
        val viewModels = ViewModelStore()
        lateinit var app: AppState
        try {
            withContext(Dispatchers.Main) {
                app = AppState(application, container, SavedStateHandle())
                viewModels.put("app", app); app.bootstrap()
            }
            withTimeout(5_000) { app.state.first { it.screen == Screen.Bots && !it.loading } }
            withContext(Dispatchers.Main) { app.selectBot(app.state.value.bots.single()) }
            withTimeout(5_000) { app.state.first { it.screen == Screen.Sessions && !it.loading } }
            test(application, app, failPatch)
        } finally {
            withContext(Dispatchers.Main) { viewModels.clear() }
            application.container = original
            pending.clear(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }
}
