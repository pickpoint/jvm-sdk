package io.pickpoint.tracking

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

class TrackingClientTest {

    @Test
    fun publishRateLimit() = runBlocking {
        val ms = startMock(auto = true)
        try {
            val c = connect(
                Config(
                    endpoint = ms.url,
                    device = DeviceAuth("c", "s"),
                    disableReconnect = true,
                ),
            )
            try {
                c.startTrack(LatLng(1.0, 2.0))
                var accepted = 0
                repeat(MAX_PUBLISH_HZ * 3) { i ->
                    val (_, ok) = c.publish(LatLng(i.toDouble(), 0.0))
                    if (ok) accepted++
                }
                assertEquals(1, accepted)
                assertEquals(1, c.clientSeq())
                Thread.sleep(MIN_PUBLISH_INTERVAL.toMillis() + 5)
                val (seq, ok) = c.publish(LatLng(9.0, 9.0))
                assertTrue(ok)
                assertEquals(2, seq)
            } finally {
                c.close()
            }
        } finally {
            ms.close()
        }
    }

    @Test
    fun sendEventLimits() = runBlocking {
        val ms = startMock(auto = true)
        try {
            val c = connect(
                Config(
                    endpoint = ms.url,
                    device = DeviceAuth("c", "s"),
                    disableReconnect = true,
                ),
            )
            try {
                c.startTrack()
                assertThrows(TrackingException::class.java) {
                    runBlocking { c.sendEvent(ByteArray(MAX_EVENT_BYTES + 1)) }
                }
                assertTrue(c.sendEvent("a".toByteArray()))
                assertFalse(c.sendEvent("b".toByteArray()))
            } finally {
                c.close()
            }
        } finally {
            ms.close()
        }
    }

    @Test
    fun resumeAfterPublish() = runBlocking {
        val ms = startMock(auto = true)
        try {
            val c = connect(
                Config(
                    endpoint = ms.url,
                    device = DeviceAuth("c", "s"),
                    disableReconnect = true,
                ),
            )
            try {
                val uid = c.startTrack(LatLng(1.0, 1.0))
                assertTrue(c.publish(LatLng(2.0, 2.0)).second)
                ms.waitMsg { it.loc != null }
                val acked = c.resume(uid, 1)
                assertEquals(0, acked)
                ms.waitMsg { it.resume != null }
            } finally {
                c.close()
            }
        } finally {
            ms.close()
        }
    }

    @Test
    fun listenerSubscribeAndLocation() = runBlocking {
        val ms = startMock(auto = true) { msg, conn ->
            if (msg.subscribe != null) {
                Thread {
                    Thread.sleep(20)
                    conn.send(
                        ServerMsg(
                            loc = ServerLoc(1, 3, LatLng(1.5, 2.5)),
                        ),
                    )
                }.start()
            }
        }
        try {
            val c = connect(
                Config(
                    endpoint = ms.url,
                    listener = ListenerAuth("jwt"),
                    disableReconnect = true,
                ),
            )
            try {
                c.subscribe(MOCK_DEVICE_UID)
                withTimeout(3000) {
                    while (true) {
                        val msg = c.recv()
                        when {
                            msg.loc != null -> {
                                assertEquals(1.5, msg.loc.point.latitude, 1e-9)
                                return@withTimeout
                            }
                            msg.subscribed != null -> continue
                            else -> continue
                        }
                    }
                }
            } finally {
                c.close()
            }
        } finally {
            ms.close()
        }
    }

    @Test
    fun authErrorWithoutRefreshCloses() = runBlocking {
        val ms = startMock(auto = false) { msg, conn ->
            if (msg.trackStart != null) {
                conn.send(serverError(ErrorCode.AUTH, "bad creds"))
            }
        }
        try {
            val c = connect(
                Config(
                    endpoint = ms.url,
                    device = DeviceAuth("c", "s"),
                    reconnectMinDelay = Duration.ofMillis(10),
                    reconnectMaxDelay = Duration.ofMillis(20),
                ),
            )
            try {
                val ex = assertThrows(TrackingException::class.java) {
                    runBlocking { c.startTrack() }
                }
                assertEquals(ErrorCode.AUTH, ex.code)
                waitFor(3000) { c.state() == ConnectionState.CLOSED }
            } finally {
                c.close()
            }
        } finally {
            ms.close()
        }
    }

    @Test
    fun authErrorRefreshRedials() = runBlocking {
        val hellos = AtomicInteger()
        val refreshed = AtomicInteger()
        val ms = startMockOpts(
            MockOpts(
                auto = false,
                beforeHello = { _, _ -> hellos.incrementAndGet() },
                onMsg = { msg, conn ->
                    if (msg.trackStart != null) {
                        conn.send(serverError(ErrorCode.UNAUTHORIZED, "expired"))
                    }
                },
            ),
        )
        try {
            val c = connect(
                Config(
                    endpoint = ms.url,
                    device = DeviceAuth("c", "s"),
                    reconnectMinDelay = Duration.ofMillis(15),
                    reconnectMaxDelay = Duration.ofMillis(40),
                    helloTimeout = Duration.ofSeconds(2),
                    refreshAuth = RefreshAuth {
                        refreshed.incrementAndGet()
                        AuthRefresh(device = DeviceAuth("c2", "s2"))
                    },
                ),
            )
            try {
                Thread { runBlocking { runCatching { c.startTrack() } } }.start()
                waitFor(3000) { refreshed.get() >= 1 }
                waitFor(5000) { hellos.get() >= 2 }
            } finally {
                c.close()
            }
        } finally {
            ms.close()
        }
    }
}
