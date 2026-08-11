package io.pickpoint.tracking

import io.pickpoint.tracking.v2.ClientMsg
import io.pickpoint.tracking.v2.ErrorCode
import io.pickpoint.tracking.v2.LatLng
import io.pickpoint.tracking.v2.Relocate
import io.pickpoint.tracking.v2.ServerMsg
import io.pickpoint.tracking.v2.TrackStarted
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch

class ReconnectTest {

    @Test
    fun reconnectSendsResumeNotTrackStart() = runBlocking {
        val release = CountDownLatch(1)
        val ms = startMockOpts(
            MockOpts(
                auto = true,
                beforeHello = { idx, _ ->
                    if (idx >= 2) release.await()
                },
            ),
        )
        try {
            val c = connect(
                Config(
                    endpoint = ms.url,
                    device = DeviceAuth("c", "s"),
                    reconnectMinDelay = Duration.ofMillis(20),
                    reconnectMaxDelay = Duration.ofMillis(50),
                ),
            )
            try {
                val uid = c.startTrack()
                c.publish(LatLng.newBuilder().setLatitude(1.0).setLongitude(2.0).build())
                Thread.sleep(25)
                c.publish(LatLng.newBuilder().setLatitude(3.0).setLongitude(4.0).build())
                assertEquals(2, c.clientSeq())

                c.forceDisconnectForTest()
                waitFor(3000) { c.state() == ConnectionState.RECONNECTING }
                release.countDown()

                val resume = ms.waitMsg { it.bodyCase == ClientMsg.BodyCase.RESUME }
                assertEquals(uid, resume.resume.trackUid)
                assertEquals(2, resume.resume.lastClientSeq)

                waitFor(5000) { c.state() == ConnectionState.OPEN }
                assertEquals(1, ms.countMessages { it.bodyCase == ClientMsg.BodyCase.TRACK_START })
            } finally {
                c.close()
            }
        } finally {
            ms.close()
        }
    }

    @Test
    fun reconnectTrackNotFoundClearsCursor() = runBlocking {
        val release = CountDownLatch(1)
        val ms = startMockOpts(
            MockOpts(
                auto = false,
                beforeHello = { idx, _ ->
                    if (idx >= 2) release.await()
                },
                onMsg = { msg, conn ->
                    when (msg.bodyCase) {
                        ClientMsg.BodyCase.TRACK_START ->
                            conn.send(
                                ServerMsg.newBuilder()
                                    .setTrackStarted(TrackStarted.newBuilder().setTrackUid("t-gone"))
                                    .build(),
                            )
                        ClientMsg.BodyCase.RESUME ->
                            conn.send(serverError(ErrorCode.ERROR_CODE_TRACK_NOT_FOUND, "track expired"))
                        else -> Unit
                    }
                },
            ),
        )
        try {
            val c = connect(
                Config(
                    endpoint = ms.url,
                    device = DeviceAuth("c", "s"),
                    reconnectMinDelay = Duration.ofMillis(20),
                    reconnectMaxDelay = Duration.ofMillis(40),
                ),
            )
            try {
                c.startTrack()
                assertEquals("t-gone", c.trackUid())
                c.forceDisconnectForTest()
                waitFor(3000) { c.state() == ConnectionState.RECONNECTING }
                release.countDown()
                ms.waitMsg { it.bodyCase == ClientMsg.BodyCase.RESUME }
                waitFor(5000) { c.trackUid().isEmpty() }
            } finally {
                c.close()
            }
        } finally {
            ms.close()
        }
    }

    @Test
    fun relocateDialsNewEndpoint() = runBlocking {
        val target = startMock(auto = true)
        try {
            val gateway = startMockOpts(
                MockOpts(
                    auto = false,
                    relocateOnConnect = Relocate.newBuilder()
                        .setEndpoint(target.url)
                        .setRetryAfterMs(10)
                        .build(),
                ),
            )
            try {
                val c = connect(
                    Config(
                        endpoint = gateway.url,
                        device = DeviceAuth("c", "s"),
                        disableReconnect = true,
                    ),
                )
                try {
                    assertEquals(ConnectionState.OPEN, c.state())
                    assertTrue(target.connCount() >= 1)
                    assertEquals("track-mock-1", c.startTrack())
                } finally {
                    c.close()
                }
            } finally {
                gateway.close()
            }
        } finally {
            target.close()
        }
    }

    @Test
    fun queueFlushAfterResume() = runBlocking {
        val release = CountDownLatch(1)
        val ms = startMockOpts(
            MockOpts(
                auto = true,
                beforeHello = { idx, _ ->
                    if (idx >= 2) release.await()
                },
            ),
        )
        try {
            val c = connect(
                Config(
                    endpoint = ms.url,
                    device = DeviceAuth("c", "s"),
                    reconnectMinDelay = Duration.ofMillis(20),
                    reconnectMaxDelay = Duration.ofMillis(50),
                ),
            )
            try {
                c.startTrack()
                c.forceDisconnectForTest()
                waitFor(3000) { c.state() == ConnectionState.RECONNECTING }
                val (seq, ok) = c.publish(LatLng.newBuilder().setLatitude(9.0).setLongitude(9.0).build())
                assertTrue(ok)
                assertEquals(1, seq)
                release.countDown()
                ms.waitMsg { it.bodyCase == ClientMsg.BodyCase.RESUME }
                ms.waitMsg {
                    it.bodyCase == ClientMsg.BodyCase.LOCATION_BATCH ||
                        it.bodyCase == ClientMsg.BodyCase.LOCATION_ADD
                }
                Unit
            } finally {
                c.close()
            }
        } finally {
            ms.close()
        }
    }
}
