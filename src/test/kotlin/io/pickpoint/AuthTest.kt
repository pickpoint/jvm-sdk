package io.pickpoint

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AuthTest {
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
    fun clientAuthRefreshOn401() {
        val n = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.contains("/client-tokens/refresh")) {
                    return MockResponse().setBody(
                        """{"accessToken":"access-2","refreshToken":"refresh-2","expiresAt":${System.currentTimeMillis() + 60_000}}""",
                    )
                }
                val i = n.incrementAndGet()
                val auth = request.getHeader("Authorization")
                if (i == 1) {
                    assertEquals("Bearer access-1", auth)
                    return MockResponse().setResponseCode(401)
                }
                assertEquals("Bearer access-2", auth)
                return MockResponse().setBody("""[{"ok":true}]""")
            }
        }

        val c = Client(
            Config(
                baseUrl = baseUrl(),
                clientAuth = ClientAuth("access-1", "refresh-1", System.currentTimeMillis() + 60_000),
            ),
        )
        assertEquals(1, c.forward(mapOf("q" to "a")).size)
    }

    @Test
    fun singleFlightRefresh() {
        val refreshes = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.contains("/refresh")) {
                    refreshes.incrementAndGet()
                    Thread.sleep(40)
                    return MockResponse().setBody(
                        """{"accessToken":"access-fresh","refreshToken":"refresh-2","expiresAt":${System.currentTimeMillis() + 120_000}}""",
                    )
                }
                assertEquals("Bearer access-fresh", request.getHeader("Authorization"))
                return MockResponse().setBody("""[{"ok":true}]""")
            }
        }

        val c = Client(
            Config(
                baseUrl = baseUrl(),
                clientAuth = ClientAuth("stale", "refresh-1", System.currentTimeMillis() + 80),
            ),
        )
        Thread.sleep(50)

        val latch = CountDownLatch(4)
        val errors = AtomicInteger()
        repeat(4) {
            Thread {
                try {
                    c.forward(mapOf("q" to "x"))
                } catch (_: Exception) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }.start()
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(0, errors.get())
        assertEquals(1, refreshes.get())
    }

    @Test
    fun refreshRotationSecondClientFails() {
        val lock = Any()
        var valid = "refresh-1"
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.contains("/refresh")) {
                    val body = request.body.readUtf8()
                    val token = Regex(""""refreshToken"\s*:\s*"([^"]+)"""").find(body)?.groupValues?.get(1)
                    val ok = synchronized(lock) {
                        val match = token == valid
                        if (match) valid = "refresh-2"
                        match
                    }
                    if (!ok) return MockResponse().setResponseCode(401)
                    return MockResponse().setBody(
                        """{"accessToken":"a2","refreshToken":"refresh-2","expiresAt":${System.currentTimeMillis() + 60_000}}""",
                    )
                }
                return MockResponse().setBody("[]")
            }
        }

        fun mk() = Client(
            Config(
                baseUrl = baseUrl(),
                clientAuth = ClientAuth("a1", "refresh-1", System.currentTimeMillis() + 50),
            ),
        )

        val a = mk()
        Thread.sleep(40)
        a.forward(mapOf("q" to "a"))

        val b = mk()
        Thread.sleep(40)
        val ex = assertThrows(APIException::class.java) {
            b.forward(mapOf("q" to "b"))
        }
        assertTrue(ex.isAuth())
    }

    @Test
    fun unauthorizedRetryExactlyOnce() {
        val hits = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.contains("/refresh")) {
                    return MockResponse().setBody(
                        """{"accessToken":"a2","refreshToken":"r2","expiresAt":${System.currentTimeMillis() + 60_000}}""",
                    )
                }
                hits.incrementAndGet()
                return MockResponse().setResponseCode(401)
            }
        }

        val c = Client(
            Config(
                baseUrl = baseUrl(),
                clientAuth = ClientAuth("a1", "r1", System.currentTimeMillis() + 60_000),
            ),
        )
        val ex = assertThrows(APIException::class.java) {
            c.forward(mapOf("q" to "x"))
        }
        assertTrue(ex.isAuth())
        assertEquals(2, hits.get())
    }

    @Test
    fun proactiveRefreshHalfwayTtl() {
        val refreshed = AtomicBoolean()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.contains("/refresh")) {
                    refreshed.set(true)
                    return MockResponse().setBody(
                        """{"accessToken":"a2","refreshToken":"r2","expiresAt":${System.currentTimeMillis() + 60_000}}""",
                    )
                }
                return MockResponse().setBody("[]")
            }
        }

        val ttlMs = 200L
        val c = Client(
            Config(
                baseUrl = baseUrl(),
                clientAuth = ClientAuth("a1", "r1", System.currentTimeMillis() + ttlMs),
            ),
        )
        c.forward(mapOf("q" to "early"))
        assertFalse(refreshed.get())
        Thread.sleep(ttlMs * 55 / 100 + 10)
        c.forward(mapOf("q" to "late"))
        assertTrue(refreshed.get())
    }

    @Test
    fun mixedFanOutShares401Refresh() {
        val refreshes = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path!!.contains("/refresh")) {
                    refreshes.incrementAndGet()
                    Thread.sleep(20)
                    return MockResponse().setBody(
                        """{"accessToken":"a2","refreshToken":"r2","expiresAt":${System.currentTimeMillis() + 60_000}}""",
                    )
                }
                if (request.getHeader("Authorization") == "Bearer a1") {
                    return MockResponse().setResponseCode(401)
                }
                return when {
                    request.path!!.contains("/address/search") ->
                        MockResponse().setBody("""{"features":[]}""")
                    request.path!!.contains("/devices") ->
                        MockResponse().setBody("""{"data":[],"total":0}""")
                    else -> MockResponse().setBody("""[{"ok":true}]""")
                }
            }
        }

        val c = Client(
            Config(
                baseUrl = baseUrl(),
                clientAuth = ClientAuth("a1", "r1", System.currentTimeMillis() + 60_000),
            ),
        )
        val latch = CountDownLatch(3)
        Thread { try { c.forward(mapOf("q" to "a")) } finally { latch.countDown() } }.start()
        Thread { try { c.search(mapOf("q" to "b")) } finally { latch.countDown() } }.start()
        Thread { try { c.devices.list() } finally { latch.countDown() } }.start()
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals(1, refreshes.get())
    }

    @Test
    fun mintClientTokens() {
        server.enqueue(
            MockResponse().setBody(
                """{"accessToken":"a","refreshToken":"r","expiresAt":123,"expiresIn":600,"scopes":["geocoding"]}""",
            ),
        )
        val pair = mintClientTokens(
            Config(apiKey = "secret", baseUrl = baseUrl()),
            scopes = listOf("geocoding"),
            ttlSec = 600,
        )
        assertEquals("a", pair.accessToken)
        val req = server.takeRequest()
        assertEquals("secret", req.getHeader("x-api-key"))
        assertTrue(req.body.readUtf8().contains("geocoding"))
    }

    @Test
    fun mintClientTokensEmptyScopes() {
        server.enqueue(
            MockResponse().setBody(
                """{"accessToken":"a","refreshToken":"r","expiresAt":1,"scopes":["geocoding"]}""",
            ),
        )
        val pair = mintClientTokens(Config(apiKey = "secret", baseUrl = baseUrl()), scopes = emptyList())
        assertEquals("a", pair.accessToken)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains(""""scopes":[]""") || body.contains(""""scopes": []"""))
    }

    @Test
    fun mintClientTokensWithScopes() {
        server.enqueue(MockResponse().setBody("""{"accessToken":"a","refreshToken":"r","expiresAt":1}"""))
        mintClientTokens(
            Config(apiKey = "k", baseUrl = baseUrl()),
            scopes = listOf("geocoding", "devices"),
            ttlSec = 600,
        )
        assertTrue(server.takeRequest().body.readUtf8().contains("devices"))
    }

    @Test
    fun mintRequiresApiKey() {
        assertThrows(APIException::class.java) {
            mintClientTokens(Config(accessToken = "x"))
        }
    }
}
