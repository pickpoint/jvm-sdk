package io.pickpoint.tracking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UrlTest {
    @Test
    fun buildWsUrlDevice() {
        val url = buildWsUrl(
            Config(
                endpoint = "wss://tracking.pickpoint.io",
                device = DeviceAuth("id", "secret"),
            ),
        )
        assertEquals("https", url.scheme)
        assertEquals("/v2/ws", url.encodedPath)
        assertEquals("id", url.queryParameter("client-id"))
        assertEquals("secret", url.queryParameter("client-secret"))
    }

    @Test
    fun buildWsUrlListener() {
        val url = buildWsUrl(
            Config(endpoint = "https://example.com", listener = ListenerAuth("tok")),
        )
        assertEquals("https", url.scheme)
        assertEquals("tok", url.queryParameter("access-token"))
    }

    @Test
    fun bareHost() {
        val url = buildWsUrl(
            Config(endpoint = "localhost:3100", device = DeviceAuth("a", "b")),
        )
        assertEquals("http", url.scheme)
        assertTrue(url.host == "localhost")
        assertEquals(3100, url.port)
    }
}
