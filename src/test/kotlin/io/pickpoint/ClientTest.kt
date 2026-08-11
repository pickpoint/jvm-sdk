package io.pickpoint

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class ClientTest {
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
    fun invalidConfig() {
        assertThrows(APIException::class.java) { Client(Config()) }
        assertThrows(APIException::class.java) {
            Client(Config(apiKey = "a", accessToken = "b"))
        }
    }

    @Test
    fun forwardAndSearchShareApiKey() {
        val sawKey = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.getHeader("x-api-key") == "secret") sawKey.incrementAndGet()
                return when {
                    request.path!!.contains("/geocode/forward") ->
                        MockResponse().setBody("""[{"display_name":"Berlin"}]""")
                    request.path!!.contains("/address/search") ->
                        MockResponse().setBody("""{"type":"FeatureCollection","features":[]}""")
                    request.path == "/v2/devices" ->
                        MockResponse().setBody("""{"data":[{"uid":"d1","name":"A","type":"car"}],"total":1}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val c = Client(Config(apiKey = "secret", baseUrl = baseUrl()))
        assertEquals(1, c.forward(mapOf("q" to "Berlin")).size)
        c.search(mapOf("q" to "Berlin"))
        assertEquals(1, c.devices.list().total)
        assertEquals(3, sawKey.get())
    }

    @Test
    fun geocodeSoftEmptyOn4xx() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"nope"}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"message":"bad"}"""))
        val c = Client(Config(apiKey = "k", baseUrl = baseUrl()))
        assertEquals(0, c.forward(mapOf("q" to "x")).size)
        assertEquals(null, c.reverse(mapOf("lat" to "0", "lon" to "0")))
    }

    @Test
    fun searchHardErrorOn4xx() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"missing"}"""))
        val c = Client(Config(apiKey = "k", baseUrl = baseUrl()))
        val ex = assertThrows(APIException::class.java) {
            c.search(mapOf("q" to "x"))
        }
        assertEquals("NOT_FOUND", ex.code)
    }

    @Test
    fun bearerAccessToken() {
        server.enqueue(MockResponse().setBody("[]"))
        val c = Client(Config(accessToken = "tok", baseUrl = baseUrl()))
        c.forward(mapOf("q" to "Berlin"))
        assertEquals("Bearer tok", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun javaBuilderWorks() {
        val cfg = Config.builder().apiKey("k").baseUrl(baseUrl()).build()
        server.enqueue(MockResponse().setBody("[]"))
        Client.create(cfg).forward(mapOf("q" to "x"))
    }
}
