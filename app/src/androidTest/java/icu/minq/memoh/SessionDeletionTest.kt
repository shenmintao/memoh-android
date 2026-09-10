package icu.minq.memoh

import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import icu.minq.memoh.data.*
import icu.minq.memoh.model.*
import icu.minq.memoh.network.MemohApi
import icu.minq.memoh.security.AuthStore
import icu.minq.memoh.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SessionDeletionTest {
    @get:Rule val compose = createComposeRule()
    private val first = Session("s1", "b", title = "删除测试会话")
    private val second = Session("s2", "b", title = "保留会话")

    @Test fun confirmationCancelsAndDeletesOnlySelectedSession() {
        var deleted: Session? = null
        val state = UiState(screen = Screen.Chat, bot = Bot("b", "Memoh", currentUserPermissions = listOf("manage")), session = first, sessions = listOf(first, second))
        compose.setContent { MemohTheme { MemohShell(state, UiActions(deleteSession = { deleted = it })) } }
        compose.onNodeWithContentDescription("删除当前会话").performClick()
        compose.onNodeWithText("删除会话？").assertIsDisplayed()
        compose.onNodeWithText("取消").performClick()
        compose.runOnIdle { assertNull(deleted) }
        compose.onNodeWithContentDescription("删除当前会话").performClick()
        capture("delete-session-confirmation.png")
        compose.onNodeWithText("删除", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(first, deleted) }
    }

    @Test fun readonlyAccountHasNoDeleteEntry() {
        val state = UiState(screen = Screen.Chat, bot = Bot("b", "Memoh", currentUserPermissions = listOf("read")), session = first)
        compose.setContent { MemohTheme { MemohShell(state) } }
        compose.onNodeWithContentDescription("删除当前会话").assertDoesNotExist()
    }

    @Test fun successfulDeleteClosesCurrentChatClearsDraftAndIgnoresStaleList() = runBlocking {
        withApp { app, _, _, _ ->
            open(app, first)
            withContext(Dispatchers.Main) { app.editDraft("仅属于待删除会话"); app.deleteSession(first) }
            withTimeout(5_000) { app.state.first { it.deletingSessionId == null && it.session == null } }
            assertEquals(Screen.Sessions, app.state.value.screen)
            assertEquals(listOf(second.id), app.state.value.sessions.map { it.id })
            assertEquals("", app.state.value.draft)
            withContext(Dispatchers.Main) { app.refreshSessions() }
            withTimeout(5_000) { app.state.first { !it.loading } }
            assertEquals(listOf(second.id), app.state.value.sessions.map { it.id })
        }
    }

    @Test fun failurePreservesChatHistoryAndDraft() = runBlocking {
        withApp { app, status, _, _ ->
            open(app, first); status.set(503)
            withContext(Dispatchers.Main) { app.editDraft("不能丢失"); app.deleteSession(first) }
            withTimeout(5_000) { app.state.first { it.deletingSessionId == null && it.error != null } }
            assertTrue(app.state.value.error.orEmpty().contains("fixture failure"))
            assertEquals(first.id, app.state.value.session?.id)
            assertEquals("不能丢失", app.state.value.draft)
            assertEquals(2, app.state.value.sessions.size)
        }
    }

    @Test fun delayedDeleteDoesNotCloseAnotherChatAndDuplicateClickSendsOnce() = runBlocking {
        withApp(delayed = true) { app, _, release, requests ->
            open(app, first)
            withContext(Dispatchers.Main) { app.deleteSession(first); app.deleteSession(first) }
            withTimeout(5_000) { while (requests.get() == 0) delay(10) }
            open(app, second)
            release.countDown()
            withTimeout(5_000) { app.state.first { it.deletingSessionId == null } }
            assertEquals(second.id, app.state.value.session?.id)
            assertEquals(1, requests.get())
            assertEquals(listOf(second.id), app.state.value.sessions.map { it.id })
        }
    }

    private suspend fun open(app: AppState, session: Session) {
        withContext(Dispatchers.Main) { app.openSession(session) }
        withTimeout(5_000) { app.state.first { it.session?.id == session.id && !it.loading } }
    }

    private suspend fun withApp(delayed: Boolean = false, test: suspend (AppState, AtomicInteger, CountDownLatch, AtomicInteger) -> Unit) {
        val application = ApplicationProvider.getApplicationContext<MemohApplication>()
        val status = AtomicInteger(204)
        val release = CountDownLatch(if (delayed) 1 else 0)
        val requests = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            val path = request.url.encodedPath
            val deleting = request.method == "DELETE"
            if (deleting) {
                check(path == "/api/bots/b/sessions/s1")
                check(request.header("Authorization") == "Bearer fixture")
                requests.incrementAndGet()
                check(release.await(10, TimeUnit.SECONDS))
            }
            val code = if (deleting) status.get() else if (path.endsWith("/ws")) 503 else 200
            val body = when {
                deleting -> if (code == 204) "" else """{"message":"fixture failure"}"""
                path.endsWith("/users/me") -> """{"id":"u","username":"fixture"}"""
                path.endsWith("/bots") -> """{"items":[{"id":"b","name":"Memoh","current_user_permissions":["manage"]}]}"""
                path.endsWith("/settings") -> "{}"
                path.endsWith("/sessions") -> """{"items":[{"id":"s1","bot_id":"b","title":"删除测试会话"},{"id":"s2","bot_id":"b","title":"保留会话"}]}"""
                path.endsWith("/models") || path.endsWith("/providers") -> "[]"
                else -> """{"items":[]}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("fixture").body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val tokens = object : AuthStore {
            private var value: AuthMaterial? = AuthMaterial("fixture", "2099-01-01T00:00:00Z", "https://test.invalid/api", "u")
            override fun read() = value
            override fun write(value: AuthMaterial) { this.value = value }
            override fun clear() { value = null }
        }
        val pending = PendingOperationStore(application, "test_delete_pending").also { it.clear() }
        val container = AppContainer(MemohApi(client, Json { ignoreUnknownKeys = true }, tokens), tokens, pending)
        val models = ViewModelStore()
        lateinit var app: AppState
        try {
            withContext(Dispatchers.Main) { app = AppState(application, container, SavedStateHandle()); models.put("app", app); app.bootstrap() }
            withTimeout(5_000) { app.state.first { it.screen == Screen.Bots && !it.loading } }
            withContext(Dispatchers.Main) { app.selectBot(app.state.value.bots.single()) }
            withTimeout(5_000) { app.state.first { it.screen == Screen.Sessions && !it.loading } }
            test(app, status, release, requests)
        } finally {
            release.countDown()
            withContext(Dispatchers.Main) { models.clear() }
            pending.clear(); client.connectionPool.evictAll(); client.dispatcher.executorService.shutdown()
        }
    }

    private fun capture(name: String) {
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val directory = instrumentation.targetContext.getExternalFilesDir("qa")!!
        directory.mkdirs()
        compose.waitForIdle()
        compose.onNode(isDialog()).captureToImage().asAndroidBitmap().let { bitmap -> java.io.File(directory, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
    }
}
