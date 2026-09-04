package icu.minq.memoh.network

import icu.minq.memoh.model.AuthMaterial
import icu.minq.memoh.security.AuthStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant

class MemohApiAuthTest {
    private lateinit var server: MockWebServer
    private lateinit var store: MemoryAuthStore
    private lateinit var api: MemohApi

    @Before fun setUp() {
        server = MockWebServer().also { it.start() }
        store = MemoryAuthStore(AuthMaterial("stale-token", Instant.now().plusSeconds(3600).toString(), server.url("/api").toString().removeSuffix("/")))
        api = MemohApi(OkHttpClient(), Json { ignoreUnknownKeys = true }, store)
    }

    @After fun tearDown() = server.shutdown()

    @Test fun `authenticated 401 refreshes once and retries with new bearer`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"access_token\":\"fresh token\",\"expires_at\":\"${Instant.now().plusSeconds(7200)}\"}"))
        server.enqueue(MockResponse().setResponseCode(200).setBody("{\"id\":\"u1\",\"username\":\"alice\"}"))

        assertEquals("u1", api.me().id)
        assertEquals("Bearer stale-token", server.takeRequest().getHeader("Authorization"))
        assertEquals("/api/auth/refresh", server.takeRequest().path)
        assertEquals("Bearer fresh token", server.takeRequest().getHeader("Authorization"))
        assertEquals("fresh token", store.read()?.accessToken)
    }

    @Test fun `failed refresh clears installed authentication`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))
        server.enqueue(MockResponse().setResponseCode(401).setBody("{}"))

        val failure = runCatching { api.me() }.exceptionOrNull()
        assertTrue(failure is ApiException)
        assertNull(store.read())
        assertEquals(2, server.requestCount)
    }

    private class MemoryAuthStore(initial: AuthMaterial?) : AuthStore {
        private var value = initial
        override fun read() = value
        override fun write(value: AuthMaterial) { this.value = value }
        override fun clear() { value = null }
    }
}
