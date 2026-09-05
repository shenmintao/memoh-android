package icu.minq.memoh

import android.Manifest
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
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
import java.io.File

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
            val manager = application.getSystemService(NotificationManager::class.java)
            awaitCondition { manager.activeNotifications.any { it.id == 1001 } }
            val ongoing = manager.activeNotifications.first { it.id == 1001 }.notification
            assertBranding(application, ongoing)
            assertEquals(Notification.VISIBILITY_SECRET, ongoing.visibility)
            captureNotification("notification-waiting")
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
            assertBranding(application, result.notification)
            assertEquals(Notification.VISIBILITY_PRIVATE, result.notification.visibility)
            assertNotNull(result.notification.publicVersion)
            assertBranding(application, result.notification.publicVersion)
            assertEquals("有新的状态更新", result.notification.publicVersion.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            captureNotification("notification-result")
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
            val result = application.getSystemService(NotificationManager::class.java).activeNotifications
                .first { it.id == 2000 + (pending.invocationId.hashCode() and 0x0fffffff) }.notification
            assertBranding(application, result)
        }
    }

    @Suppress("DEPRECATION")
    private fun assertBranding(context: Context, notification: Notification) {
        assertEquals(R.drawable.ic_stat_memoh, notification.smallIcon.resId)
        assertEquals(0xff7948ff.toInt(), notification.color)
        assertNull("The right-hand duplicate logo must be absent", notification.getLargeIcon())
        assertNull(notification.extras.get(Notification.EXTRA_LARGE_ICON))
        assertEquals(R.mipmap.ic_memoh_launcher, context.applicationInfo.icon)
        // OEM notification centers read the application icon for their left-hand identity icon.
        val bitmap = context.packageManager.getApplicationIcon(context.packageName).toBitmap(128, 128)
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        assertTrue("Logo must retain its light purple", pixels.count { it == 0xffbd69ff.toInt() } > 20)
        assertTrue("Logo must retain its dark purple", pixels.count { it == 0xff7948ff.toInt() } > 20)
        val small = notification.smallIcon.loadDrawable(context)!!.toBitmap(128, 128)
        val official = ContextCompat.getDrawable(context, R.drawable.ic_memoh)!!.toBitmap(128, 128)
        val mask = IntArray(128 * 128); val reference = IntArray(128 * 128)
        small.getPixels(mask, 0, 128, 0, 0, 128, 128)
        official.getPixels(reference, 0, 128, 0, 0, 128, 128)
        assertTrue("Status-bar icon must retain the official Memoh silhouette", mask.indices.count { (mask[it] ushr 24) != (reference[it] ushr 24) } < mask.size / 100)
    }

    private suspend fun captureNotification(name: String) {
        val arguments = InstrumentationRegistry.getArguments()
        if (arguments.getString("captureNotificationScreenshots") != "true") return
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        try {
            // Foreground-service cards can be deferred by System UI; capture after that window.
            if (name.endsWith("waiting")) delay(10_000)
            val context = ApplicationProvider.getApplicationContext<Context>()
            val dir = arguments.getString("additionalTestOutputDir")?.let(::File) ?: context.getExternalFilesDir("qa")!!
            dir.mkdirs()
            if (name.endsWith("waiting")) {
                val statusBar = requireNotNull(automation.takeScreenshot())
                File(dir, "statusbar-waiting.png").outputStream().use { statusBar.compress(Bitmap.CompressFormat.PNG, 100, it) }
                statusBar.recycle()
            }
            automation.executeShellCommand("cmd statusbar expand-notifications").use { descriptor ->
                java.io.FileInputStream(descriptor.fileDescriptor).readBytes()
            }
            delay(5_000)
            val screenshot = requireNotNull(automation.takeScreenshot())
            File(dir, "$name.png").outputStream().use { screenshot.compress(Bitmap.CompressFormat.PNG, 100, it) }
            screenshot.recycle()
        } finally {
            automation.executeShellCommand("cmd statusbar collapse").close()
            delay(1_500)
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
