package io.pickpoint

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RoutingTest {
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
    fun routing400Throws() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"message":"bad","errorCode":400}"""))
        val c = Client(Config(apiKey = "k", baseUrl = server.url("/").toString().trimEnd('/')))
        val ex = assertThrows(APIException::class.java) {
            c.route(emptyMap<String, Any>())
        }
        assertEquals(400, ex.status)
        assertEquals("CLIENT_ERROR", ex.code)
    }
}
