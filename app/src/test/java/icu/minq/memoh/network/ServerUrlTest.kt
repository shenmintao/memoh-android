package icu.minq.memoh.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ServerUrlTest {
    @Test fun `empty server is rejected instead of using a default`() {
        assertThrows(IllegalArgumentException::class.java) { ServerUrl.normalize("  ") }
    }

    @Test fun `generic HTTPS host gets api path`() {
        assertEquals("https://chat.example.org/api", ServerUrl.normalize("https://chat.example.org"))
    }

    @Test fun `api is not appended twice`() {
        assertEquals("https://chat.example.org/api", ServerUrl.normalize("https://chat.example.org/api/"))
    }

    @Test fun `existing deployment prefix is preserved`() {
        assertEquals("https://example.org/memoh/api", ServerUrl.normalize("example.org/memoh"))
    }

    @Test fun `cleartext is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { ServerUrl.normalize("http://example.org") }
    }
}
