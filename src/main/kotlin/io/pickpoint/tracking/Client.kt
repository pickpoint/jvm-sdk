package io.pickpoint.tracking

import io.pickpoint.tracking.v2.ClientMsg
import io.pickpoint.tracking.v2.Command
import io.pickpoint.tracking.v2.CommandAck
import io.pickpoint.tracking.v2.CommandAckStatus
import io.pickpoint.tracking.v2.Event
import io.pickpoint.tracking.v2.LatLng
import io.pickpoint.tracking.v2.LocationAdd
import io.pickpoint.tracking.v2.LocationBatch
import io.pickpoint.tracking.v2.Resume
import io.pickpoint.tracking.v2.ServerMsg
import io.pickpoint.tracking.v2.Subscribe
import io.pickpoint.tracking.v2.TrackStart
import io.pickpoint.tracking.v2.TrackStop
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.time.Instant
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tracking session (device publisher or listener).
 * Binary WebSocket protobuf (`tracking.v2.proto`).
 */
class TrackingClient internal constructor(
    private var cfg: Config,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var state: ConnectionState = ConnectionState.CONNECTING
    @Volatile private var trackUid: String = ""
    @Volatile private var clientSeq: Long = 0
    @Volatile private var lastAckedSeq: Long = 0

    private val queue = OfflineQueue(cfg.maxQueueSize)
    private val backoff = newBackoff(cfg.reconnectMinDelay, cfg.reconnectMaxDelay, cfg.reconnectMaxAttempts)
    private var nextPublishAt: Instant = Instant.EPOCH
    private var nextEventAt: Instant = Instant.EPOCH
    private val subscriptions = linkedSetOf<String>()
    private val intentional = AtomicBoolean(false)
    private var dialGen: Long = 0
    private var webSocket: WebSocket? = null

    private val recvCh = Channel<ServerMsg>(64, BufferOverflow.DROP_OLDEST)
    private val cmdCh = Channel<Command>(16, BufferOverflow.DROP_OLDEST)

    private var startWait: CompletableDeferred<String>? = null
    private var stopWait: CompletableDeferred<Unit>? = null
    private var resumeWait: CompletableDeferred<Long>? = null
    private var reconnectJob: Job? = null
    private var helloWait: CompletableDeferred<ServerMsg>? = null

    fun state(): ConnectionState = state
    fun trackUid(): String = trackUid
    fun clientSeq(): Long = clientSeq
    fun lastAckedSeq(): Long = lastAckedSeq

    /** Blocking receive of the next server message (Go-style). */
    fun recvBlocking(): ServerMsg = runBlocking { recv() }

    suspend fun recv(): ServerMsg = recvCh.receive()

    fun recvCommandBlocking(): Command = runBlocking { recvCommand() }

    suspend fun recvCommand(): Command = cmdCh.receive()

    suspend fun send(msg: ClientMsg) {
        mutex.withLock {
            if (state == ConnectionState.CLOSED && intentional.get()) {
                throw TrackingException(message = "closed")
            }
            val ws = webSocket ?: throw TrackingException(message = "socket not open")
            if (!ws.send(encodeClientMsg(msg).toByteString())) {
                throw TrackingException(message = "send failed")
            }
        }
    }

    fun sendBlocking(msg: ClientMsg) = runBlocking { send(msg) }

    suspend fun startTrack(
        location: LatLng? = null,
        route: List<LatLng> = emptyList(),
        metadata: ByteArray? = null,
    ): String {
        val fut = CompletableDeferred<String>()
        mutex.withLock { startWait = fut }
        val start = TrackStart.newBuilder()
        if (location != null) start.location = stampLatLng(cloneLatLng(location))
        route.forEach { start.addRoute(stampLatLng(cloneLatLng(it))) }
        if (metadata != null) start.metadata = com.google.protobuf.ByteString.copyFrom(metadata)
        try {
            send(ClientMsg.newBuilder().setTrackStart(start).build())
        } catch (e: Exception) {
            mutex.withLock { startWait = null }
            throw e
        }
        return fut.await()
    }

    fun startTrackBlocking(
        location: LatLng? = null,
        route: List<LatLng> = emptyList(),
        metadata: ByteArray? = null,
    ): String = runBlocking { startTrack(location, route, metadata) }

    suspend fun resume(trackUid: String, lastClientSeq: Long): Long {
        val fut = CompletableDeferred<Long>()
        mutex.withLock {
            this.trackUid = trackUid
            this.clientSeq = lastClientSeq
            resumeWait = fut
        }
        try {
            send(
                ClientMsg.newBuilder()
                    .setResume(Resume.newBuilder().setTrackUid(trackUid).setLastClientSeq(lastClientSeq))
                    .build(),
            )
        } catch (e: Exception) {
            mutex.withLock { resumeWait = null }
            throw e
        }
        return fut.await()
    }

    fun resumeBlocking(trackUid: String, lastClientSeq: Long): Long =
        runBlocking { resume(trackUid, lastClientSeq) }

    /**
     * Publish a location point. Returns `(seq, accepted)`.
     * Rate-limited to [MAX_PUBLISH_HZ]; queues offline when reconnecting.
     */
    suspend fun publish(point: LatLng): Pair<Long, Boolean> {
        val (seq, accepted, uid, open) = mutex.withLock {
            if (trackUid.isEmpty()) return@withLock PublishPlan(0, false, "", false)
            val now = Instant.now()
            if (!canAcceptPublish(nextPublishAt, now, 1)) {
                return@withLock PublishPlan(clientSeq, false, trackUid, false)
            }
            nextPublishAt = nextPublishAllowedAt(nextPublishAt, now, 1)
            clientSeq += 1
            val s = clientSeq
            val pt = stampLatLng(cloneLatLng(point))
            queue.enqueue(s, pt)
            PublishPlan(s, true, trackUid, state == ConnectionState.OPEN && webSocket != null)
        }
        if (accepted && open) {
            try {
                send(
                    ClientMsg.newBuilder()
                        .setLocationAdd(
                            LocationAdd.newBuilder()
                                .setTrackUid(uid)
                                .setClientSeq(seq)
                                .setPoint(stampLatLng(cloneLatLng(point))),
                        )
                        .build(),
                )
            } catch (_: Exception) {
                // queued for resume flush
            }
        }
        return seq to accepted
    }

    fun publishBlocking(point: LatLng): Pair<Long, Boolean> = runBlocking { publish(point) }

    suspend fun stopTrack(trackUid: String? = null) {
        val uid = trackUid ?: this.trackUid
        if (uid.isEmpty()) throw TrackingException(message = "no active track")
        val fut = CompletableDeferred<Unit>()
        mutex.withLock { stopWait = fut }
        try {
            send(ClientMsg.newBuilder().setTrackStop(TrackStop.newBuilder().setTrackUid(uid)).build())
        } catch (e: Exception) {
            mutex.withLock { stopWait = null }
            throw e
        }
        fut.await()
    }

    fun stopTrackBlocking(trackUid: String? = null) = runBlocking { stopTrack(trackUid) }

    suspend fun sendEvent(payload: ByteArray): Boolean {
        if (payload.size > MAX_EVENT_BYTES) {
            throw TrackingException(message = "event payload exceeds 4 KiB")
        }
        val (uid, open) = mutex.withLock {
            if (trackUid.isEmpty()) throw TrackingException(message = "startTrack() before sendEvent()")
            val now = Instant.now()
            if (nextEventAt != Instant.EPOCH && now.isBefore(nextEventAt)) {
                return false
            }
            nextEventAt = now.plus(MIN_EVENT_INTERVAL)
            trackUid to (state == ConnectionState.OPEN && webSocket != null)
        }
        if (!open) return true
        val ev = Event.newBuilder()
            .setTrackUid(uid)
            .setPayload(com.google.protobuf.ByteString.copyFrom(payload))
            .setTimestampMs(System.currentTimeMillis())
            .build()
        send(ClientMsg.newBuilder().setEvent(ev).build())
        return true
    }

    fun sendEventBlocking(payload: ByteArray): Boolean = runBlocking { sendEvent(payload) }

    suspend fun subscribe(deviceUid: String) {
        mutex.withLock { subscriptions.add(deviceUid) }
        send(ClientMsg.newBuilder().setSubscribe(Subscribe.newBuilder().setDeviceUid(deviceUid)).build())
    }

    fun subscribeBlocking(deviceUid: String) = runBlocking { subscribe(deviceUid) }

    suspend fun ackCommand(
        commandId: String,
        status: CommandAckStatus = CommandAckStatus.COMMAND_ACK_STATUS_OK,
        message: String = "",
    ) {
        val ack = CommandAck.newBuilder().setCommandId(commandId).setStatus(status)
        if (message.isNotEmpty()) ack.message = message
        send(ClientMsg.newBuilder().setCommandAck(ack).build())
    }

    fun ackCommandBlocking(
        commandId: String,
        status: CommandAckStatus = CommandAckStatus.COMMAND_ACK_STATUS_OK,
        message: String = "",
    ) = runBlocking { ackCommand(commandId, status, message) }

    suspend fun close() {
        mutex.withLock {
            intentional.set(true)
            reconnectJob?.cancel()
            reconnectJob = null
            state = ConnectionState.CLOSED
            rejectPending(TrackingException(message = "client closed"))
            closeTransportLocked()
        }
        scope.coroutineContext[Job]?.cancel()
    }

    fun closeBlocking() = runBlocking { close() }

    /** Test-only: abruptly drop the socket to exercise reconnect. */
    internal fun forceDisconnectForTest() {
        webSocket?.cancel()
    }

    internal suspend fun dial(sendResume: Boolean = false) {
        val gen: Long
        mutex.withLock {
            reconnectJob?.cancel()
            reconnectJob = null
            dialGen += 1
            gen = dialGen
            state = if (state == ConnectionState.OPEN || state == ConnectionState.RECONNECTING) {
                ConnectionState.RECONNECTING
            } else {
                ConnectionState.CONNECTING
            }
        }

        val url = buildWsUrl(cfg)
        val hello = CompletableDeferred<ServerMsg>()
        mutex.withLock { helloWait = hello }

        val request = Request.Builder()
            .url(url)
            .header("Sec-WebSocket-Protocol", SUBPROTOCOL)
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val proto = response.header("Sec-WebSocket-Protocol")
                if (proto != null && proto != SUBPROTOCOL) {
                    hello.completeExceptionally(
                        TrackingException(message = "server did not accept $SUBPROTOCOL"),
                    )
                    webSocket.close(1002, "bad subprotocol")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val msg = try {
                    decodeServerMsg(bytes.toByteArray())
                } catch (_: Exception) {
                    return
                }
                if (!hello.isCompleted) {
                    hello.complete(msg)
                    return
                }
                scope.launch { dispatch(msg) }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!hello.isCompleted) {
                    hello.completeExceptionally(t)
                }
                scope.launch { onSocketClosed(gen) }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                scope.launch { onSocketClosed(gen) }
            }
        }

        val ws = http.newWebSocket(request, listener)
        mutex.withLock {
            if (gen != dialGen || intentional.get()) {
                ws.cancel()
                throw TrackingException(message = "dial superseded")
            }
            closeTransportLocked()
            webSocket = ws
        }

        val first = try {
            withTimeout(cfg.helloTimeout.toMillis()) { hello.await() }
        } catch (e: Exception) {
            ws.cancel()
            throw TrackingException(message = "hello timeout", cause = e)
        }

        when (first.bodyCase) {
            ServerMsg.BodyCase.RELOCATE -> {
                ws.cancel()
                handleRelocate(first.relocate, sendResume)
                return
            }
            ServerMsg.BodyCase.ERROR -> {
                ws.cancel()
                throw errorFromWire(first.error)
            }
            ServerMsg.BodyCase.HELLO -> Unit
            else -> {
                ws.cancel()
                throw TrackingException(message = "expected hello, got ${first.bodyCase}")
            }
        }

        mutex.withLock {
            if (gen != dialGen || intentional.get()) {
                ws.cancel()
                throw TrackingException(message = "dial superseded")
            }
            state = ConnectionState.OPEN
            resetBackoff(backoff)
            helloWait = null
        }

        if (sendResume) sendResumeAndWait()
        resubscribe()
    }

    private data class PublishPlan(val seq: Long, val accepted: Boolean, val uid: String, val open: Boolean)

    private suspend fun dispatch(msg: ServerMsg) {
        when (msg.bodyCase) {
            ServerMsg.BodyCase.RELOCATE -> {
                scope.launch { handleRelocate(msg.relocate, sendResume = true) }
            }
            ServerMsg.BodyCase.RESUME_OK -> {
                val fut = mutex.withLock {
                    if (msg.resumeOk.trackUid.isNotEmpty()) trackUid = msg.resumeOk.trackUid
                    lastAckedSeq = msg.resumeOk.lastAckedSeq
                    if (clientSeq < lastAckedSeq) clientSeq = lastAckedSeq
                    queue.ackThrough(lastAckedSeq)
                    val f = resumeWait
                    resumeWait = null
                    f
                }
                flushQueue()
                fut?.complete(msg.resumeOk.lastAckedSeq)
                recvCh.trySend(msg)
            }
            ServerMsg.BodyCase.TRACK_STARTED -> {
                val fut = mutex.withLock {
                    trackUid = msg.trackStarted.trackUid
                    clientSeq = 0
                    lastAckedSeq = 0
                    queue.clear()
                    val f = startWait
                    startWait = null
                    f
                }
                fut?.complete(msg.trackStarted.trackUid)
                recvCh.trySend(msg)
            }
            ServerMsg.BodyCase.TRACK_STOPPED -> {
                val fut = mutex.withLock {
                    if (trackUid == msg.trackStopped.trackUid) {
                        trackUid = ""
                        queue.clear()
                    }
                    val f = stopWait
                    stopWait = null
                    f
                }
                fut?.complete(Unit)
                recvCh.trySend(msg)
            }
            ServerMsg.BodyCase.LOCATION_ADDED -> {
                mutex.withLock {
                    if (msg.locationAdded.clientSeq > lastAckedSeq) {
                        lastAckedSeq = msg.locationAdded.clientSeq
                    }
                    queue.ackThrough(msg.locationAdded.clientSeq)
                }
                recvCh.trySend(msg)
            }
            ServerMsg.BodyCase.COMMAND -> {
                cmdCh.trySend(msg.command)
            }
            ServerMsg.BodyCase.ERROR -> {
                val err = errorFromWire(msg.error)
                mutex.withLock {
                    resumeWait?.let {
                        if (isFatalResumeError(err.code)) {
                            trackUid = ""
                            queue.clear()
                        }
                        it.completeExceptionally(err)
                        resumeWait = null
                    } ?: startWait?.let {
                        it.completeExceptionally(err)
                        startWait = null
                    } ?: stopWait?.let {
                        it.completeExceptionally(err)
                        stopWait = null
                    }
                }
                if (isAuthError(err.code)) {
                    scope.launch { handleAuthError() }
                }
                recvCh.trySend(msg)
            }
            else -> recvCh.trySend(msg)
        }
    }

    private suspend fun handleRelocate(rel: io.pickpoint.tracking.v2.Relocate, sendResume: Boolean) {
        var resume = sendResume
        if (rel.endpoint.isNotEmpty()) {
            mutex.withLock { cfg = cfg.copy(endpoint = rel.endpoint) }
        }
        if (rel.retryAfterMs > 0) delay(rel.retryAfterMs.toLong())
        mutex.withLock {
            if (trackUid.isNotEmpty()) resume = true
            if (intentional.get()) throw TrackingException(message = "closed")
        }
        dial(sendResume = resume)
    }

    private suspend fun handleAuthError() {
        val refresh = cfg.refreshAuth
        if (refresh == null) {
            mutex.withLock {
                intentional.set(true)
                reconnectJob?.cancel()
                state = ConnectionState.CLOSED
                closeTransportLocked()
            }
            return
        }
        val result = try {
            refresh.refresh()
        } catch (_: Exception) {
            mutex.withLock {
                intentional.set(true)
                state = ConnectionState.CLOSED
            }
            return
        }
        val sendResume: Boolean
        mutex.withLock {
            if (result.device != null) {
                cfg = cfg.copy(device = result.device, listener = null)
            }
            if (result.listener != null) {
                cfg = cfg.copy(listener = result.listener, device = null)
            }
            sendResume = trackUid.isNotEmpty()
            if (intentional.get()) return
            dialGen += 1
            reconnectJob?.cancel()
            closeTransportLocked()
        }
        try {
            dial(sendResume = sendResume)
        } catch (_: Exception) {
            mutex.withLock {
                if (!intentional.get()) scheduleReconnectLocked()
            }
        }
    }

    private suspend fun onSocketClosed(gen: Long) {
        val shouldReconnect: Boolean
        mutex.withLock {
            if (gen != dialGen) return
            webSocket = null
            if (intentional.get()) {
                state = ConnectionState.CLOSED
                return
            }
            if (cfg.disableReconnect) {
                state = ConnectionState.CLOSED
                rejectPending(TrackingException(message = "connection closed"))
                return
            }
            state = ConnectionState.RECONNECTING
            shouldReconnect = true
        }
        if (shouldReconnect) {
            // Schedule outside the mutex so dial never races with this lock.
            mutex.withLock { scheduleReconnectLocked() }
        }
    }

    private fun scheduleReconnectLocked() {
        if (state != ConnectionState.RECONNECTING && state != ConnectionState.CLOSED) {
            state = ConnectionState.RECONNECTING
        }
        if (intentional.get() || cfg.disableReconnect) return
        val delayDur = nextDelay(backoff) ?: run {
            state = ConnectionState.CLOSED
            rejectPending(TrackingException(message = "reconnect attempts exhausted"))
            return
        }
        val sendResume = trackUid.isNotEmpty()
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(delayDur.toMillis())
            if (intentional.get()) return@launch
            // Clear before dial() — dial cancels reconnectJob and must not cancel itself.
            mutex.withLock { reconnectJob = null }
            try {
                dial(sendResume = sendResume)
            } catch (_: Exception) {
                mutex.withLock {
                    if (!intentional.get() && state != ConnectionState.OPEN) {
                        state = ConnectionState.RECONNECTING
                        scheduleReconnectLocked()
                    }
                }
            }
        }
    }

    private fun rejectPending(err: Exception) {
        startWait?.completeExceptionally(err)
        startWait = null
        stopWait?.completeExceptionally(err)
        stopWait = null
        resumeWait?.completeExceptionally(err)
        resumeWait = null
    }

    private fun closeTransportLocked() {
        webSocket?.cancel()
        webSocket = null
    }

    private suspend fun resubscribe() {
        val subs = mutex.withLock { subscriptions.toList() }
        for (d in subs) {
            try {
                send(ClientMsg.newBuilder().setSubscribe(Subscribe.newBuilder().setDeviceUid(d)).build())
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun sendResumeAndWait() {
        val (uid, seq, fut) = mutex.withLock {
            if (trackUid.isEmpty()) return
            val f = CompletableDeferred<Long>()
            resumeWait = f
            Triple(trackUid, clientSeq, f)
        }
        try {
            send(
                ClientMsg.newBuilder()
                    .setResume(Resume.newBuilder().setTrackUid(uid).setLastClientSeq(seq))
                    .build(),
            )
        } catch (e: Exception) {
            mutex.withLock { resumeWait = null }
            throw e
        }
        fut.await()
    }

    private suspend fun flushQueue() {
        val (uid, pending, open) = mutex.withLock {
            Triple(trackUid, queue.peekAll(), state == ConnectionState.OPEN && webSocket != null)
        }
        if (uid.isEmpty() || !open || pending.isEmpty()) return
        val batch = LocationBatch.newBuilder()
            .setTrackUid(uid)
            .setClientSeq(pending.last().seq)
        pending.forEach { batch.addPoints(it.point) }
        try {
            send(ClientMsg.newBuilder().setLocationBatch(batch).build())
        } catch (_: Exception) {
        }
    }

    companion object {
        @JvmStatic
        fun connect(config: Config): TrackingClient = runBlocking { connectSuspend(config) }

        suspend fun connectSuspend(config: Config): TrackingClient {
            if (config.endpoint.isEmpty()) {
                throw TrackingException(message = "Endpoint is required")
            }
            if (config.device == null && config.listener == null) {
                throw TrackingException(message = "Device or Listener auth is required")
            }
            val client = TrackingClient(config)
            client.dial(sendResume = false)
            return client
        }
    }
}

/** Kotlin-friendly connect. */
suspend fun connect(config: Config): TrackingClient = TrackingClient.connectSuspend(config)

/** Blocking connect for Java. */
fun connectBlocking(config: Config): TrackingClient = TrackingClient.connect(config)
