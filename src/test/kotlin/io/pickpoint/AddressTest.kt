package io.pickpoint

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AddressTest {
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

    @Test
    fun addressSearch400Throws() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"message":"bad"}"""))
        val c = Client(Config(apiKey = "k", baseUrl = server.url("/").toString().trimEnd('/')))
        val ex = assertThrows(APIException::class.java) {
            c.search(mapOf("q" to "x"))
        }
        assertEquals(400, ex.status)
        assertEquals("CLIENT_ERROR", ex.code)
    }
}
