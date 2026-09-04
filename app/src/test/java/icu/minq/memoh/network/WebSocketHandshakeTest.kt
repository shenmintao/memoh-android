package icu.minq.memoh.network

import org.junit.Assert.*
import org.junit.Test

class WebSocketHandshakeTest {
    @Test fun `header handshake is preferred and keeps token out of url`() {
        val request = WebSocketHandshake.request("https://example.invalid/api", "bot-1", "a+b /?", WebSocketAuthMode.HEADER)
        assertEquals("Bearer a+b /?", request.header("Authorization"))
        assertNull(request.url.queryParameter("token"))
    }

    @Test fun `query fallback encodes token and is allowed only once`() {
        val request = WebSocketHandshake.request("https://example.invalid/api", "bot-1", "a+b /?", WebSocketAuthMode.QUERY_FALLBACK)
        assertNull(request.header("Authorization"))
        assertEquals("a+b /?", request.url.queryParameter("token"))
        assertTrue(request.url.toString().contains("token=a%2Bb%20%2F%3F"))
        assertEquals(WebSocketAuthMode.QUERY_FALLBACK, WebSocketHandshake.fallbackAfter401(WebSocketAuthMode.HEADER))
        assertNull(WebSocketHandshake.fallbackAfter401(WebSocketAuthMode.QUERY_FALLBACK))
    }
}
