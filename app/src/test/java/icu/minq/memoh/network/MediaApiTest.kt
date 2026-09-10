package icu.minq.memoh.network

import icu.minq.memoh.model.AuthMaterial
import icu.minq.memoh.security.AuthStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class MediaApiTest {
    private class Store(var auth: AuthMaterial?) : AuthStore {
        override fun read() = auth
        override fun write(value: AuthMaterial) { auth = value }
        override fun clear() { auth = null }
    }

    @Test fun `protected media refreshes auth and keeps arbitrary binary bytes intact`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val store = Store(AuthMaterial("old", "2099-01-01T00:00:00Z", server.url("/api").toString().trimEnd('/')))
            val client = OkHttpClient()
            val api = MemohApi(client, Json { ignoreUnknownKeys = true }, store)
            val bytes = ByteArray(4096) { (it % 256).toByte() }
            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody("""{"access_token":"fresh","expires_at":"2099-01-01T00:00:00Z"}"""))
            server.enqueue(MockResponse().setHeader("Content-Type", "image/png").setBody(Buffer().write(bytes)))
            assertArrayEquals(bytes, api.mediaBytes("bot-1", "a".repeat(64)))
            val first = server.takeRequest()
            assertEquals("/api/bots/bot-1/media/" + "a".repeat(64), first.path)
            assertEquals("Bearer old", first.getHeader("Authorization"))
            assertEquals("/api/auth/refresh", server.takeRequest().path)
            assertEquals("Bearer fresh", server.takeRequest().getHeader("Authorization"))
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }

    @Test fun `media redirects are not followed and external images receive no token`() = runTest {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val serverTLS = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTLS = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        MockWebServer().use { server -> MockWebServer().use { redirect ->
            server.useHttps(serverTLS.sslSocketFactory(), false); server.start(); redirect.start()
            val client = OkHttpClient.Builder().sslSocketFactory(clientTLS.sslSocketFactory(), clientTLS.trustManager).build()
            val local = server.url("/").newBuilder().host("localhost").build()
            val api = MemohApi(client, Json, Store(AuthMaterial("secret", "2099-01-01T00:00:00Z", local.resolve("/api")!!.toString().trimEnd('/'))))
            server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(1, 2, 3))))
            assertArrayEquals(byteArrayOf(1, 2, 3), api.publicImageBytes(local.resolve("/public.png")!!.toString()))
            assertNull(server.takeRequest().getHeader("Authorization"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", redirect.url("/stolen")))
            assertEquals(302, (runCatching { api.mediaBytes("b", "b".repeat(64)) }.exceptionOrNull() as ApiException).status)
            assertEquals(0, redirect.requestCount)
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        } }
    }

    @Test fun `oversized and invalid media references are rejected`() = runTest {
        MockWebServer().use { server ->
            server.start()
            val client = OkHttpClient()
            val api = MemohApi(client, Json, Store(AuthMaterial("test", "2099-01-01T00:00:00Z", server.url("/api").toString().trimEnd('/'))))
            assertTrue(runCatching { api.mediaBytes("../other", "c".repeat(64)) }.isFailure)
            assertTrue(runCatching { api.mediaBytes("b", "../../") }.isFailure)
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setBody("x").setHeader("Content-Length", MemohApi.MAX_IMAGE_BYTES + 1))
            assertEquals(413, (runCatching { api.mediaBytes("b", "c".repeat(64)) }.exceptionOrNull() as ApiException).status)
            client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll()
        }
    }
}
