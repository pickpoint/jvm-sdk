package io.pickpoint.tracking

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

const val MOCK_TRACK_UID = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
const val MOCK_DEVICE_UID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb"
const val MOCK_NODE_ID = "cccccccc-cccc-cccc-cccc-cccccccccccc"

class MockConn(val ws: WebSocket) {
    private val lock = Any()
    val messages = mutableListOf<ClientMsg>()

    fun send(msg: ServerMsg): Boolean =
        ws.send(encodeServerMsg(msg).toByteString())

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
    private val nextSub = AtomicInteger(1)

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
                    conn.send(ServerMsg(relocate = opts.relocateOnConnect))
                } else {
                    conn.send(ServerMsg(hello = Hello(PROTOCOL_VERSION, 0, MOCK_NODE_ID)))
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
                decodeClientMsg(bytes.toByteArray())
            } catch (_: Exception) {
                return
            }
            conn.record(msg)
            opts.onMsg?.invoke(msg, conn)
            if (!opts.auto) return
            when {
                msg.trackStart != null ->
                    conn.send(ServerMsg(trackStarted = TrackStarted(MOCK_TRACK_UID)))
                msg.trackStop != null ->
                    conn.send(ServerMsg(trackStopped = TrackStopped(MOCK_TRACK_UID)))
                msg.resume != null ->
                    conn.send(ServerMsg(resumeOk = ResumeOk(msg.resume.trackUid, 0)))
                msg.loc != null ->
                    conn.send(ServerMsg(ack = Ack(msg.loc.seq)))
                msg.subscribe != null -> {
                    val sub = nextSub.getAndIncrement()
                    conn.send(
                        ServerMsg(
                            subscribed = Subscribed(
                                sub = sub,
                                deviceUid = msg.subscribe.deviceUid,
                                trackUid = MOCK_TRACK_UID,
                                online = true,
                            ),
                        ),
                    )
                }
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
    ServerMsg(error = WireError(code, message = message))

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
