package io.pickpoint.tracking

import io.pickpoint.tracking.v2.ClientMsg
import io.pickpoint.tracking.v2.ErrorCode
import io.pickpoint.tracking.v2.Hello
import io.pickpoint.tracking.v2.LocationAdded
import io.pickpoint.tracking.v2.Relocate
import io.pickpoint.tracking.v2.ResumeOk
import io.pickpoint.tracking.v2.ServerMsg
import io.pickpoint.tracking.v2.Subscribed
import io.pickpoint.tracking.v2.TrackStarted
import io.pickpoint.tracking.v2.TrackStopped
import io.pickpoint.tracking.v2.Error as WireError
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.Assertions.assertTrue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class MockConn(val ws: WebSocket) {
    private val lock = Any()
    val messages = mutableListOf<ClientMsg>()

    fun send(msg: ServerMsg): Boolean =
        ws.send(msg.toByteArray().toByteString())

    fun close() {
        try {
            ws.close(1000, "test")
        } catch (_: Exception) {
        }
    }

    fun record(msg: ClientMsg) {
        synchronized(lock) { messages.add(msg) }
    }

    fun snapshot(): List<ClientMsg> = synchronized(lock) { messages.toList() }
}

data class MockOpts(
    val auto: Boolean = true,
    val onMsg: ((ClientMsg, MockConn) -> Unit)? = null,
    val beforeHello: ((connectionIndex: Int, MockConn) -> Unit)? = null,
    val relocateOnConnect: Relocate? = null,
)

class MockTrackingServer(private val opts: MockOpts) {
    private val server = MockWebServer()
    private val connections = CopyOnWriteArrayList<MockConn>()
    private val connIndex = AtomicInteger()

    val url: String
        get() = server.url("/").toString().trimEnd('/').replace("http://", "ws://").replace("https://", "wss://")

    fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                return MockResponse().withWebSocketUpgrade(listener())
            }
        }
        server.start()
    }

    fun close() {
        connections.forEach { runCatching { it.close() } }
        runCatching { server.shutdown() }
    }

    fun connCount(): Int = connections.size

    fun waitConn(timeoutMs: Long = 2000): MockConn {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (connections.isNotEmpty()) return connections[0]
            Thread.sleep(5)
        }
        error("waitConn timeout")
    }

    fun waitMsg(timeoutMs: Long = 8000, pred: (ClientMsg) -> Boolean): ClientMsg {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            for (c in connections) {
                for (m in c.snapshot()) {
                    if (pred(m)) return m
                }
            }
            Thread.sleep(5)
        }
        error("waitMsg timeout")
    }

    fun countMessages(pred: (ClientMsg) -> Boolean): Int {
        var n = 0
        for (c in connections) {
            for (m in c.snapshot()) {
                if (pred(m)) n++
            }
        }
        return n
    }

    private fun listener() = object : WebSocketListener() {
        private lateinit var conn: MockConn
        private val idx = connIndex.incrementAndGet()

        override fun onOpen(webSocket: WebSocket, response: Response) {
            conn = MockConn(webSocket)
            connections.add(conn)
            val sendHello = {
                if (opts.relocateOnConnect != null && idx == 1) {
                    conn.send(ServerMsg.newBuilder().setRelocate(opts.relocateOnConnect).build())
                } else {
                    conn.send(
                        ServerMsg.newBuilder()
                            .setHello(Hello.newBuilder().setNodeId("mock-1"))
                            .build(),
                    )
                }
            }
            if (opts.beforeHello != null) {
                Thread {
                    opts.beforeHello.invoke(idx, conn)
                    sendHello()
                }.start()
            } else {
                sendHello()
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            val msg = try {
                ClientMsg.parseFrom(bytes.toByteArray())
            } catch (_: Exception) {
                return
            }
            conn.record(msg)
            opts.onMsg?.invoke(msg, conn)
            if (!opts.auto) return
            when (msg.bodyCase) {
                ClientMsg.BodyCase.TRACK_START ->
                    conn.send(
                        ServerMsg.newBuilder()
                            .setTrackStarted(TrackStarted.newBuilder().setTrackUid("track-mock-1"))
                            .build(),
                    )
                ClientMsg.BodyCase.TRACK_STOP ->
                    conn.send(
                        ServerMsg.newBuilder()
                            .setTrackStopped(TrackStopped.newBuilder().setTrackUid(msg.trackStop.trackUid))
                            .build(),
                    )
                ClientMsg.BodyCase.RESUME ->
                    conn.send(
                        ServerMsg.newBuilder()
                            .setResumeOk(
                                ResumeOk.newBuilder()
                                    .setTrackUid(msg.resume.trackUid)
                                    .setLastAckedSeq(0),
                            )
                            .build(),
                    )
                ClientMsg.BodyCase.LOCATION_ADD ->
                    conn.send(
                        ServerMsg.newBuilder()
                            .setLocationAdded(
                                LocationAdded.newBuilder()
                                    .setTrackUid(msg.locationAdd.trackUid)
                                    .setClientSeq(msg.locationAdd.clientSeq)
                                    .setPoint(msg.locationAdd.point)
                                    .setDeviceUid("dev-1"),
                            )
                            .build(),
                    )
                ClientMsg.BodyCase.LOCATION_BATCH ->
                    conn.send(
                        ServerMsg.newBuilder()
                            .setLocationAdded(
                                LocationAdded.newBuilder()
                                    .setTrackUid(msg.locationBatch.trackUid)
                                    .setClientSeq(msg.locationBatch.clientSeq)
                                    .setDeviceUid("dev-1"),
                            )
                            .build(),
                    )
                ClientMsg.BodyCase.SUBSCRIBE ->
                    conn.send(
                        ServerMsg.newBuilder()
                            .setSubscribed(
                                Subscribed.newBuilder()
                                    .setDeviceUid(msg.subscribe.deviceUid)
                                    .setTrackUid("track-mock-1"),
                            )
                            .build(),
                    )
                ClientMsg.BodyCase.PING ->
                    conn.send(ServerMsg.newBuilder().setPong(io.pickpoint.tracking.v2.Pong.getDefaultInstance()).build())
                else -> Unit
            }
        }
    }
}

fun startMock(
    auto: Boolean = true,
    onMsg: ((ClientMsg, MockConn) -> Unit)? = null,
): MockTrackingServer = startMockOpts(MockOpts(auto = auto, onMsg = onMsg))

fun startMockOpts(opts: MockOpts): MockTrackingServer =
    MockTrackingServer(opts).also { it.start() }

fun serverError(code: ErrorCode, message: String): ServerMsg =
    ServerMsg.newBuilder()
        .setError(WireError.newBuilder().setCode(code).setMessage(message))
        .build()

fun waitFor(timeoutMs: Long = 5000, pred: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (pred()) return
        Thread.sleep(15)
    }
    error("waitFor timeout")
}

fun awaitLatch(latch: CountDownLatch, timeoutSec: Long = 5) {
    assertTrue(latch.await(timeoutSec, TimeUnit.SECONDS))
}
