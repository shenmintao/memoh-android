package icu.minq.memoh

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import icu.minq.memoh.data.*
import icu.minq.memoh.model.*
import icu.minq.memoh.network.MemohApi
import icu.minq.memoh.security.AuthStore
import icu.minq.memoh.service.PendingReplyService
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PendingServiceTest {
    @Test fun failedResultIsGenericAndStaleStartDoesNotStrandService() = runBlocking {
        withService { application, store, scenario ->
            val pending = PendingOperation("b", "s", "test-service-i", PendingPolicy.accountKey("https://test.invalid/api", "u"), System.currentTimeMillis(), PendingPhase.ACCEPTED)
            scenario.onActivity {
                store.start(pending)
                ContextCompat.startForegroundService(it, PendingReplyService.intent(it, pending))
            }
            awaitCondition { PendingReplyService.isMonitoring(pending) }
            scenario.onActivity { ContextCompat.startForegroundService(it, PendingReplyService.intent(it, pending.copy(invocationId = "stale-id"))) }
            delay(150)
            assertTrue(PendingReplyService.isMonitoring(pending))
            withContext(Dispatchers.Main) {
                store.observe(pending.accountKey, "b", "s", RuntimeRun("r", "t", invocation_id = pending.invocationId, status = "errored", error = "PRIVATE_TOKEN_AND_COMMAND_MUST_NOT_APPEAR"))
            }
            awaitCondition { store.read() == null && !serviceRunning(application) }
            val notifications = application.getSystemService(NotificationManager::class.java).activeNotifications
            val result = notifications.firstOrNull { it.id == 2000 + (pending.invocationId.hashCode() and 0x0fffffff) }
            assertNotNull(result)
            assertEquals("Memoh 回复出错或已停止，请打开应用查看", result!!.notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            assertFalse(result.notification.extras.toString().contains("PRIVATE_TOKEN"))
        }
    }

    @Test fun expiredMonitorStopsAndLeavesActionableUnknownStatus() = runBlocking {
        withService { application, store, scenario ->
            val pending = PendingOperation("b", "s", "test-timeout-i", PendingPolicy.accountKey("https://test.invalid/api", "u"), System.currentTimeMillis() - PendingPolicy.MONITOR_TIMEOUT_MS - 1, PendingPhase.ACCEPTED)
            scenario.onActivity {
                store.start(pending)
                ContextCompat.startForegroundService(it, PendingReplyService.intent(it, pending))
            }
            awaitCondition { store.read()?.phase == PendingPhase.UNKNOWN && !serviceRunning(application) }
            assertEquals(pending.invocationId, store.read()?.invocationId)
            assertFalse(store.read()!!.monitoring)
        }
    }

    private suspend fun withService(test: suspend (MemohApplication, PendingOperationStore, ActivityScenario<MainActivity>) -> Unit) {
        val app = ApplicationProvider.getApplicationContext<MemohApplication>()
        val original = app.container
        val store = PendingOperationStore(app, "test_service_pending").also { it.clear() }
        val auth = object : AuthStore {
            private var value: AuthMaterial? = AuthMaterial("test-only", "2099-01-01T00:00:00Z", "https://test.invalid/api", "u")
            override fun read() = value
            override fun write(value: AuthMaterial) { this.value = value }
            override fun clear() { value = null }
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            val code = if (path.endsWith("/ws")) 503 else 200
            val body = if (path.endsWith("users/me")) """{"id":"u","username":"test"}""" else """{"items":[]}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        if (Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        app.container = AppContainer(MemohApi(client, Json { ignoreUnknownKeys = true }, auth), auth, store)
        val scenario = ActivityScenario.launch<MainActivity>(Intent(app, MainActivity::class.java))
        try {
            delay(250)
            test(app, store, scenario)
        } finally {
            app.stopService(Intent(app, PendingReplyService::class.java))
            awaitCondition { !serviceRunning(app) }
            scenario.close()
            store.clear()
            app.getSystemService(NotificationManager::class.java).cancelAll()
            app.container = original
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }
    private suspend fun awaitCondition(condition: () -> Boolean) = withTimeout(8_000) { while (!condition()) delay(40) }
    @Suppress("DEPRECATION") private fun serviceRunning(context: Context) = context.getSystemService(ActivityManager::class.java)
        .getRunningServices(100).any { it.service.className == PendingReplyService::class.java.name }
}
