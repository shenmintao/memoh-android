package icu.minq.memoh.network

import icu.minq.memoh.model.AuthMaterial
import icu.minq.memoh.security.AuthStore
import kotlinx.coroutines.*
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AuthLifecycleTest {
    private class Store(@Volatile var value: AuthMaterial?) : AuthStore {
        override fun read() = value
        override fun write(value: AuthMaterial) { this.value = value }
        override fun clear() { value = null }
    }
    private fun material(server: MockWebServer) = AuthMaterial("old-token", Instant.now().minusSeconds(1).toString(), server.url("/api").toString().trimEnd('/'), "old-user")

    @Test fun `503 refresh preserves credentials`() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val store = Store(material(server)); val original = store.read()
            val api = MemohApi(OkHttpClient(), Json, store)
            server.enqueue(MockResponse().setResponseCode(503).setBody("{}"))
            assertTrue(runCatching { api.freshAuth() }.exceptionOrNull() is ApiException)
            assertEquals(original, store.read())
        }
    }
    @Test fun `late refresh cannot resurrect logout`() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val store = Store(material(server)); val api = MemohApi(OkHttpClient(), Json, store)
            server.enqueue(MockResponse().setBodyDelay(300, TimeUnit.MILLISECONDS).setBody("{\"access_token\":\"late-token\",\"expires_at\":\"${Instant.now().plusSeconds(3600)}\"}"))
            val work = async(Dispatchers.Default) { runCatching { api.freshAuth() } }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)); api.clearAuth()
            assertTrue(work.await().exceptionOrNull() is CancellationException)
            assertNull(store.read())
        }
    }
    @Test fun `cancel old refresh preserves a replacement token and cancels call promptly`() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val store = Store(material(server)); val client = OkHttpClient(); val api = MemohApi(client, Json, store)
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val work = launch(Dispatchers.Default) { api.freshAuth() }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            work.cancel()
            val replacement = AuthMaterial("new-token", Instant.now().plusSeconds(3600).toString(), "https://new.invalid/api", "new-user")
            store.write(replacement)
            withTimeout(2_000) { work.join() }
            assertEquals(replacement, store.read())
        }
    }
    @Test fun `late rejected refresh cannot delete a replacement login`() = runBlocking {
        MockWebServer().use { server ->
            server.start(); val store = Store(material(server)); val api = MemohApi(OkHttpClient(), Json, store)
            val release = CountDownLatch(1)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    check(release.await(5, TimeUnit.SECONDS))
                    return MockResponse().setResponseCode(401).setBody("{}")
                }
            }
            val work = async(Dispatchers.Default) { runCatching { api.freshAuth() } }
            try {
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS)); api.clearAuth()
                val replacement = AuthMaterial("new-token", "2099-01-01T00:00:00Z", "https://new.invalid/api", "new-user")
                store.write(replacement); release.countDown(); work.await()
                assertEquals(replacement, store.read())
            } finally { release.countDown() }
        }
    }
}
