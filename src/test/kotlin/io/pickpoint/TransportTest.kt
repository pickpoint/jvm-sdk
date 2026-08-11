package io.pickpoint

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.TimeUnit

class TransportTest {
    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun baseUrl() = server.url("/").toString().trimEnd('/')

    @Test
    fun retries5xxThenSucceeds() {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("""[{"ok":1}]"""))
        val c = Client(
            Config(
                apiKey = "k",
                baseUrl = baseUrl(),
                maxRetries = 3,
                retryBase = Duration.ofMillis(1),
            ),
        )
        assertEquals(1, c.forward(mapOf("q" to "x")).size)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun conflict() {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"message":"dup"}"""))
        val c = Client(Config(apiKey = "k", baseUrl = baseUrl()))
        val ex = assertThrows(APIException::class.java) {
            c.devices.create(DeviceInput(name = "a", type = "car"))
        }
        assertEquals("CONFLICT", ex.code)
    }

    @Test
    fun requestTimesOut() {
        server.enqueue(MockResponse().setBody("[]").setBodyDelay(2, TimeUnit.SECONDS))
        val c = Client(
            Config(
                apiKey = "k",
                baseUrl = baseUrl(),
                timeout = Duration.ofMillis(100),
                maxRetries = 1,
                retryBase = Duration.ofMillis(1),
            ),
        )
        val ex = assertThrows(APIException::class.java) {
            c.search(mapOf("q" to "x"))
        }
        assertEquals("NETWORK", ex.code)
        assertTrue(ex.cause != null)
    }
}
