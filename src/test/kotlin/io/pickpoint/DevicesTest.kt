package io.pickpoint

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DevicesTest {
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
    fun devices404() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Device not found"}"""))
        val c = Client(Config(apiKey = "k", baseUrl = baseUrl()))
        val ex = assertThrows(APIException::class.java) {
            c.devices.get("missing")
        }
        assertTrue(ex.isNotFound())
    }

    @Test
    fun devicesConflict409() {
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"message":"device offline"}"""))
        val c = Client(Config(apiKey = "k", baseUrl = baseUrl()))
        val ex = assertThrows(APIException::class.java) {
            c.devices.command("u1", "x".toByteArray())
        }
        assertTrue(ex.isConflict())
    }

    @Test
    fun commandBase64() {
        server.enqueue(MockResponse().setBody("""{"delivered":1}"""))
        val c = Client(Config(apiKey = "k", baseUrl = baseUrl()))
        val out = c.devices.command("uid-1", "hi".toByteArray())
        assertEquals(1, out.delivered)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains(""""payload":"aGk=""""))
    }
}
