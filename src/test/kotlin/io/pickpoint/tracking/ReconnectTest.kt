package io.pickpoint.tracking

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
                c.publish(LatLng(1.0, 2.0))
                Thread.sleep(25)
                c.publish(LatLng(3.0, 4.0))
                assertEquals(2, c.clientSeq())

                c.forceDisconnectForTest()
                waitFor(3000) { c.state() == ConnectionState.RECONNECTING }
                release.countDown()

                val resume = ms.waitMsg { it.resume != null }
                assertEquals(uid, resume.resume!!.trackUid)
                assertEquals(2, resume.resume!!.lastSeq)

                waitFor(5000) { c.state() == ConnectionState.OPEN }
                assertEquals(1, ms.countMessages { it.trackStart != null })
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
                    when {
                        msg.trackStart != null ->
                            conn.send(ServerMsg(trackStarted = TrackStarted("dddddddd-dddd-dddd-dddd-dddddddddddd")))
                        msg.resume != null ->
                            conn.send(serverError(ErrorCode.TRACK_NOT_FOUND, "track expired"))
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
                assertEquals("dddddddd-dddd-dddd-dddd-dddddddddddd", c.trackUid())
                c.forceDisconnectForTest()
                waitFor(3000) { c.state() == ConnectionState.RECONNECTING }
                release.countDown()
                ms.waitMsg { it.resume != null }
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
                    relocateOnConnect = Relocate(endpoint = target.url, retryAfterMs = 10),
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
                    assertEquals(MOCK_TRACK_UID, c.startTrack())
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
                val (seq, ok) = c.publish(LatLng(9.0, 9.0))
                assertTrue(ok)
                assertEquals(0, seq)
                release.countDown()
                ms.waitMsg { it.resume != null }
                ms.waitMsg { it.loc != null }
                waitFor(3000) { c.clientSeq() == 1L }
            } finally {
                c.close()
            }
        } finally {
            ms.close()
        }
    }
}
