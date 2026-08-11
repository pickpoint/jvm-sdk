package io.pickpoint

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class GeocodingTest {
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
    fun geocodeEmptyOn400() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"message":"bad"}"""))
        val c = Client(Config(apiKey = "k", baseUrl = baseUrl()))
        assertTrue(c.forward(mapOf("q" to "x")).isEmpty())
    }

    @Test
    fun forwardBatchRespectsConcurrency() {
        val inflight = AtomicInteger()
        val max = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val cur = inflight.incrementAndGet()
                max.updateAndGet { old -> maxOf(old, cur) }
                Thread.sleep(20)
                inflight.decrementAndGet()
                return MockResponse().setBody("""[{"ok":true}]""")
            }
        }

        val c = Client(Config(apiKey = "k", baseUrl = baseUrl(), concurrency = 4))
        val qs = List(12) { mapOf("q" to "x") }
        val out = c.forwardBatch(qs)
        assertEquals(12, out.size)
        assertTrue(max.get() <= 4, "concurrency leaked: ${max.get()}")
    }

    @Test
    fun batchPipelineFillsSlots() {
        val started = ConcurrentHashMap<String, Long>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val q = request.requestUrl!!.queryParameter("q")!!
                started[q] = System.currentTimeMillis()
                if (q == "slow") Thread.sleep(80) else Thread.sleep(5)
                return MockResponse().setBody("""[{"ok":true}]""")
            }
        }

        val c = Client(Config(apiKey = "k", baseUrl = baseUrl(), concurrency = 2))
        c.forwardBatch(
            listOf(
                mapOf("q" to "slow"),
                mapOf("q" to "a"),
                mapOf("q" to "b"),
                mapOf("q" to "c"),
            ),
        )

        val slowAt = started["slow"]!!
        val aAt = started["a"]!!
        val bAt = started["b"]!!
        assertTrue(bAt - aAt <= 40, "b started too late after a (${bAt - aAt}) — wave batching?")
        assertTrue(bAt < slowAt + 60, "b should overlap slow; bAt=$bAt slowAt=$slowAt")
    }

    @Test
    fun batchAbortOn403() {
        val hits = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                hits.incrementAndGet()
                val q = request.requestUrl!!.queryParameter("q")
                if (q == "bad") return MockResponse().setResponseCode(403)
                Thread.sleep(80)
                return MockResponse().setBody("""[{"ok":true}]""")
            }
        }

        val c = Client(Config(apiKey = "k", baseUrl = baseUrl(), concurrency = 4))
        val ex = assertThrows(APIException::class.java) {
            c.forwardBatch(
                listOf(
                    mapOf("q" to "bad"),
                    mapOf("q" to "a"),
                    mapOf("q" to "b"),
                    mapOf("q" to "c"),
                    mapOf("q" to "d"),
                    mapOf("q" to "e"),
                ),
            )
        }
        assertTrue(ex.isAuth())
        assertTrue(hits.get() < 6, "expected abort before all slots, hits=${hits.get()}")
    }

    @Test
    fun batchPreservesOrder() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val q = request.requestUrl!!.queryParameter("q")!!
                if (q == "slow") {
                    Thread.sleep(60)
                    return MockResponse().setBody("""[{"id":"slow"}]""")
                }
                return MockResponse().setBody("""[{"id":"fast"}]""")
            }
        }

        val c = Client(Config(apiKey = "k", baseUrl = baseUrl(), concurrency = 10))
        val out = c.forwardBatch(
            listOf(mapOf("q" to "slow"), mapOf("q" to "fast1"), mapOf("q" to "fast2")),
        )
        assertEquals("slow", out[0][0].path("id").asText())
        assertEquals("fast", out[1][0].path("id").asText())
    }

    @Test
    fun retryBudgetPerSlot() {
        val attempts = ConcurrentHashMap<String, AtomicInteger>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val q = request.requestUrl!!.queryParameter("q")!!
                val n = attempts.getOrPut(q) { AtomicInteger() }.incrementAndGet()
                if (q == "flaky" && n < 3) return MockResponse().setResponseCode(503)
                return MockResponse().setBody("""[{"q":"$q"}]""")
            }
        }

        val c = Client(
            Config(
                apiKey = "k",
                baseUrl = baseUrl(),
                maxRetries = 5,
                retryBase = Duration.ofMillis(200),
            ),
        )
        val out = c.forwardBatch(listOf(mapOf("q" to "flaky"), mapOf("q" to "ok")))
        assertEquals(3, attempts["flaky"]!!.get())
        assertEquals(1, attempts["ok"]!!.get())
        assertEquals("flaky", out[0][0].path("q").asText())
    }
}
