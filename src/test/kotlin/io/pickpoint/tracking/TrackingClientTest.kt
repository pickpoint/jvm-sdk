package io.pickpoint.tracking

import io.pickpoint.tracking.v2.ClientMsg
import io.pickpoint.tracking.v2.ErrorCode
import io.pickpoint.tracking.v2.LatLng
import io.pickpoint.tracking.v2.LocationAdded
import io.pickpoint.tracking.v2.ServerMsg
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
                c.startTrack(LatLng.newBuilder().setLatitude(1.0).setLongitude(2.0).build())
                var accepted = 0
                repeat(MAX_PUBLISH_HZ * 3) { i ->
                    val (_, ok) = c.publish(LatLng.newBuilder().setLatitude(i.toDouble()).setLongitude(0.0).build())
                    if (ok) accepted++
                }
                assertEquals(1, accepted)
                assertEquals(1, c.clientSeq())
                Thread.sleep(MIN_PUBLISH_INTERVAL.toMillis() + 5)
                val (seq, ok) = c.publish(LatLng.newBuilder().setLatitude(9.0).setLongitude(9.0).build())
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
                val uid = c.startTrack(LatLng.newBuilder().setLatitude(1.0).setLongitude(1.0).build())
                assertTrue(c.publish(LatLng.newBuilder().setLatitude(2.0).setLongitude(2.0).build()).second)
                withTimeout(2000) {
                    while (true) {
                        val msg = c.recv()
                        if (msg.bodyCase == ServerMsg.BodyCase.LOCATION_ADDED) break
                    }
                }
                val acked = c.resume(uid, 1)
                assertEquals(0, acked)
                ms.waitMsg { it.bodyCase == ClientMsg.BodyCase.RESUME }
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
            if (msg.bodyCase == ClientMsg.BodyCase.SUBSCRIBE) {
                Thread {
                    Thread.sleep(20)
                    conn.send(
                        ServerMsg.newBuilder()
                            .setLocationAdded(
                                LocationAdded.newBuilder()
                                    .setDeviceUid(msg.subscribe.deviceUid)
                                    .setTrackUid("t1")
                                    .setClientSeq(3)
                                    .setPoint(LatLng.newBuilder().setLatitude(1.5).setLongitude(2.5)),
                            )
                            .build(),
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
                c.subscribe("device-1")
                withTimeout(3000) {
                    while (true) {
                        val msg = c.recv()
                        when (msg.bodyCase) {
                            ServerMsg.BodyCase.LOCATION_ADDED -> {
                                assertEquals(1.5, msg.locationAdded.point.latitude, 1e-9)
                                return@withTimeout
                            }
                            ServerMsg.BodyCase.SUBSCRIBED -> continue
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
            if (msg.bodyCase == ClientMsg.BodyCase.TRACK_START) {
                conn.send(serverError(ErrorCode.ERROR_CODE_AUTH, "bad creds"))
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
                assertEquals(ErrorCode.ERROR_CODE_AUTH, ex.code)
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
                    if (msg.bodyCase == ClientMsg.BodyCase.TRACK_START) {
                        conn.send(serverError(ErrorCode.ERROR_CODE_UNAUTHORIZED, "expired"))
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
