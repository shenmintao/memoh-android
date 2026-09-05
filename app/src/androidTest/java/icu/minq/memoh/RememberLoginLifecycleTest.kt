package icu.minq.memoh

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import icu.minq.memoh.data.*
import icu.minq.memoh.model.AuthMaterial
import icu.minq.memoh.network.MemohApi
import icu.minq.memoh.security.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class RememberLoginLifecycleTest {
    @Test fun remembersOnlySuccessfulLoginAndLogoutRestoresTheForm() = runBlocking {
        val application = ApplicationProvider.getApplicationContext<MemohApplication>()
        val reject = AtomicBoolean(false)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            val denied = path.endsWith("auth/login") && reject.get()
            val body = when {
                denied -> """{"message":"invalid credentials"}"""
                path.endsWith("auth/login") -> """{"access_token":"test-token","expires_at":"2099-01-01T00:00:00Z"}"""
                path.endsWith("users/me") -> """{"id":"u","username":"test"}"""
                else -> """{"items":[]}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(if (denied) 401 else 200).message("test")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val tokens = object : AuthStore {
            private var value: AuthMaterial? = null
            override fun read() = value
            override fun write(value: AuthMaterial) { this.value = value }
            override fun clear() { value = null }
        }
        val saved = EncryptedLoginStore(application, "test_login_lifecycle")
        val pending = PendingOperationStore(application, "test_login_lifecycle_pending")
        val models = ViewModelStore()
        lateinit var app: AppState
        saved.clear(); pending.clear()
        try {
            withContext(Dispatchers.Main) {
                app = AppState(application, AppContainer(MemohApi(client, Json { ignoreUnknownKeys = true }, tokens), tokens, pending, saved), SavedStateHandle())
                models.put("test", app)
                app.login("https://test.invalid", "test", "valid-secret", true)
            }
            withTimeout(5_000) { app.state.first { it.screen == Screen.Bots && !it.loading } }
            assertEquals("valid-secret", saved.read()?.password)
            withContext(Dispatchers.Main) { app.logout() }
            assertEquals("https://test.invalid", app.state.value.rememberedLogin?.server)
            assertEquals("test", app.state.value.rememberedLogin?.username)
            assertEquals("valid-secret", app.state.value.rememberedLogin?.password)
            reject.set(true)
            withContext(Dispatchers.Main) { app.login("https://test.invalid", "test", "wrong-secret", true) }
            withTimeout(5_000) { app.state.first { it.error != null && !it.loading } }
            assertEquals("valid-secret", saved.read()?.password)
            withContext(Dispatchers.Main) { app.forgetLogin() }
            assertNull(saved.read()); assertNull(app.state.value.rememberedLogin)
            reject.set(false)
            withContext(Dispatchers.Main) { app.login("https://test.invalid", "test", "temporary-secret", false) }
            withTimeout(5_000) { app.state.first { it.screen == Screen.Bots && !it.loading } }
            assertNull(saved.read())
            withContext(Dispatchers.Main) { app.logout() }
            assertNull(app.state.value.rememberedLogin)
        } finally {
            withContext(Dispatchers.Main) { models.clear() }
            saved.clear(); pending.clear(); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }
}
