package icu.minq.memoh

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import icu.minq.memoh.data.*
import icu.minq.memoh.model.AuthMaterial
import icu.minq.memoh.network.MemohApi
import icu.minq.memoh.security.AuthStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class ClientLifecycleTest {
    @Test fun sessionRefreshPreservesChatAndReturningCancelsLoading() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<MemohApplication>()
        val hold = AtomicBoolean(false)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            if (path.endsWith("/sessions") && hold.get()) { started.countDown(); release.await(5, TimeUnit.SECONDS) }
            val body = when {
                path.endsWith("users/me") -> """{"id":"u","username":"test"}"""
                path.endsWith("/bots") -> """{"items":[{"id":"b","name":"test","current_user_permissions":["chat"]}]}"""
                path.endsWith("/settings") -> "{}"
                else -> """{"items":[]}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("test")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val tokens = object : AuthStore {
            private var value: AuthMaterial? = AuthMaterial("test-only", "2099-01-01T00:00:00Z", "https://test.invalid/api", "u")
            override fun read() = value
            override fun write(value: AuthMaterial) { this.value = value }
            override fun clear() { value = null }
        }
        val pending = PendingOperationStore(application, "test_pending_refresh").also { it.clear() }
        val api = MemohApi(client, Json { ignoreUnknownKeys = true }, tokens)
        val viewModels = ViewModelStore()
        lateinit var app: AppState
        try {
            withContext(Dispatchers.Main) {
                app = AppState(application, AppContainer(api, tokens, pending), SavedStateHandle())
                viewModels.put("app", app); app.bootstrap()
            }
            withTimeout(5_000) { app.state.first { it.screen == Screen.Bots && !it.loading } }
            withContext(Dispatchers.Main) { app.selectBot(app.state.value.bots.single()) }
            withTimeout(5_000) { app.state.first { it.screen == Screen.Sessions && !it.loading } }
            withContext(Dispatchers.Main) { app.openSession(icu.minq.memoh.model.Session("s", "b")) }
            withTimeout(5_000) { app.state.first { it.screen == Screen.Chat && !it.loading } }
            withContext(Dispatchers.Main) { app.editDraft("keep this draft"); app.refreshSessions() }
            withTimeout(5_000) { app.state.first { !it.loading } }
            assertEquals(Screen.Chat, app.state.value.screen)
            assertEquals("s", app.state.value.session?.id)
            assertEquals("keep this draft", app.state.value.draft)
            withContext(Dispatchers.Main) { app.back() }
            hold.set(true)
            withContext(Dispatchers.Main) { app.refreshSessions() }
            assertTrue(started.await(3, TimeUnit.SECONDS))
            withContext(Dispatchers.Main) { app.back() }
            assertFalse(app.state.value.loading)
            assertEquals(Screen.Bots, app.state.value.screen)
            release.countDown(); delay(100)
            assertFalse(app.state.value.loading)
        } finally {
            release.countDown()
            withContext(Dispatchers.Main) { viewModels.clear() }
            pending.clear(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

    @Test fun pendingCompletionAndClearAreObservableAndIdentityScoped() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = PendingOperationStore(context, "test_pending_observer")
        store.clear()
        val pending = PendingOperation("b", "s", "i", "account", System.currentTimeMillis())
        try {
            assertTrue(store.start(pending))
            assertFalse(store.start(pending.copy(invocationId = "another")))
            assertFalse(store.clearIfMatches(pending.copy(accountKey = "another")))
            store.update(pending) { it.copy(phase = PendingPhase.COMPLETED) }
            assertFalse(store.changes.value!!.blocksSend)
            assertTrue(store.clearIfMatches(pending))
            assertNull(store.changes.value)
            assertTrue(store.start(pending.copy(invocationId = "new")))
            assertFalse(store.clearIfMatches(pending))
            assertEquals("new", store.changes.value!!.invocationId)
        } finally { store.clear() }
    }

    @Test fun legacyPendingIsExplicitlyUncertainAndCanBeAcknowledged() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("test_legacy_pending", Context.MODE_PRIVATE).edit().clear()
            .putString("bot", "b").putString("session", "s").putString("invocation", "legacy").commit()
        val store = PendingOperationStore(context, "test_legacy_pending")
        val pending = store.read()!!
        assertEquals(PendingPhase.UNKNOWN, pending.phase)
        assertTrue(store.clearIfMatches(pending)); assertNull(store.read())
    }
}
