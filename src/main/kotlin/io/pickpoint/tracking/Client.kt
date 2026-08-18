package io.pickpoint.tracking

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
 * Binary WebSocket `tracking.v2`.
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
    private val filter = NoiseFilter()
    private var unackedFrames = 0
    private val backoff = newBackoff(cfg.reconnectMinDelay, cfg.reconnectMaxDelay, cfg.reconnectMaxAttempts)
    private var nextPublishAt: Instant = Instant.EPOCH
    private var nextEventAt: Instant = Instant.EPOCH
    private val subscriptions = linkedMapOf<String, Int>()
    private val subByHandle = linkedMapOf<Int, String>()
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
    @Volatile private var starting: Boolean = false

    fun state(): ConnectionState = state
    fun trackUid(): String = trackUid
    fun clientSeq(): Long = clientSeq
    fun lastAckedSeq(): Long = lastAckedSeq

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
        mutex.withLock {
            queue.clear()
            filter.reset()
            clientSeq = 0
            lastAckedSeq = 0
            starting = true
            startWait = fut
        }
        try {
            send(ClientMsg(trackStart = TrackStart(location, route, metadata ?: ByteArray(0))))
        } catch (e: Exception) {
            mutex.withLock {
                startWait = null
                starting = false
            }
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
            send(ClientMsg(resume = Resume(trackUid, lastClientSeq)))
        } catch (e: Exception) {
            mutex.withLock { resumeWait = null }
            throw e
        }
        return fut.await()
    }

    fun resumeBlocking(trackUid: String, lastClientSeq: Long): Long =
        runBlocking { resume(trackUid, lastClientSeq) }

    suspend fun publish(point: LatLng): Pair<Long, Boolean> {
        val plan = mutex.withLock {
            if (trackUid.isEmpty() && !starting) {
                starting = true
                queue.clear()
                filter.reset()
                clientSeq = 0
                lastAckedSeq = 0
                return@withLock PublishPlan(0, true, false, null, point)
            }
            val now = Instant.now()
            val emitted = filter.push(point) ?: return@withLock PublishPlan(clientSeq, false, false, null)
            val open = state == ConnectionState.OPEN && webSocket != null && trackUid.isNotEmpty()
            val windowOk = unackedFrames < MAX_IN_FLIGHT_FRAMES
            if (!open || !windowOk) {
                queue.pushStaging(stampLatLng(emitted))
                return@withLock PublishPlan(clientSeq, true, false, null)
            }
            if (!canAcceptPublish(nextPublishAt, now, 1)) {
                return@withLock PublishPlan(clientSeq, false, false, null)
            }
            nextPublishAt = nextPublishAllowedAt(nextPublishAt, now, 1)
            clientSeq += 1
            val s = clientSeq
            queue.pushInFlight(s, emitted)
            PublishPlan(s, true, true, stripLiveTime(emitted))
        }
        if (plan.start != null) {
            try {
                send(ClientMsg(trackStart = TrackStart(plan.start, emptyList(), ByteArray(0))))
            } catch (_: Exception) {
                mutex.withLock { starting = false }
                return 0L to false
            }
            return 0L to true
        }
        if (plan.send && plan.point != null) {
            try {
                val frames = encodeInFlightFrames(listOf(QueuedPoint(plan.seq, plan.point)))
                mutex.withLock { unackedFrames += frames.size }
                for (f in frames) {
                    val ws = webSocket ?: break
                    ws.send(f.toByteString())
                }
            } catch (_: Exception) {
            }
        }
        return plan.seq to plan.accepted
    }

    fun publishBlocking(point: LatLng): Pair<Long, Boolean> = runBlocking { publish(point) }

    suspend fun stopTrack(trackUid: String? = null) {
        val uid = trackUid ?: this.trackUid
        if (uid.isEmpty()) throw TrackingException(message = "no active track")
        val fut = CompletableDeferred<Unit>()
        mutex.withLock { stopWait = fut }
        try {
            send(ClientMsg(trackStop = TrackStop()))
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
        val open = mutex.withLock {
            if (trackUid.isEmpty()) throw TrackingException(message = "startTrack() before sendEvent()")
            val now = Instant.now()
            if (nextEventAt != Instant.EPOCH && now.isBefore(nextEventAt)) {
                return false
            }
            nextEventAt = now.plus(MIN_EVENT_INTERVAL)
            state == ConnectionState.OPEN && webSocket != null
        }
        if (!open) return true
        send(ClientMsg(event = Event(payload, System.currentTimeMillis())))
        return true
    }

    fun sendEventBlocking(payload: ByteArray): Boolean = runBlocking { sendEvent(payload) }

    suspend fun subscribe(deviceUid: String) {
        mutex.withLock { subscriptions[deviceUid] = 0 }
        send(ClientMsg(subscribe = Subscribe(deviceUid, includeEvents = true)))
    }

    fun subscribeBlocking(deviceUid: String) = runBlocking { subscribe(deviceUid) }

    suspend fun unsubscribe(sub: Int) {
        mutex.withLock {
            val uid = subByHandle.remove(sub)
            if (uid != null) subscriptions.remove(uid)
        }
        send(ClientMsg(unsubscribe = Unsubscribe(sub)))
    }

    suspend fun ackCommand(
        commandId: String,
        status: CommandAckStatus = CommandAckStatus.OK,
        message: String = "",
    ) {
        send(ClientMsg(commandAck = CommandAck(commandId, status, message)))
    }

    fun ackCommandBlocking(
        commandId: String,
        status: CommandAckStatus = CommandAckStatus.OK,
        message: String = "",
    ) = runBlocking { ackCommand(commandId, status, message) }

    suspend fun close() {
        val uid = trackUid
        if (uid.isNotEmpty()) {
            try {
                send(ClientMsg(trackStop = TrackStop()))
            } catch (_: Exception) {
            }
        }
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
            unackedFrames = 0
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
                } ?: return
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
                if (!hello.isCompleted) hello.completeExceptionally(t)
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

        when {
            first.relocate != null -> {
                ws.cancel()
                handleRelocate(first.relocate, sendResume)
                return
            }
            first.error != null -> {
                ws.cancel()
                throw errorFromWire(first.error)
            }
            first.hello != null -> {
                if (first.hello.version != PROTOCOL_VERSION) {
                    ws.cancel()
                    throw TrackingException(message = "unsupported protocol version ${first.hello.version}")
                }
            }
            else -> {
                ws.cancel()
                throw TrackingException(message = "expected hello")
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

    private data class PublishPlan(
        val seq: Long,
        val accepted: Boolean,
        val send: Boolean,
        val point: LatLng?,
        val start: LatLng? = null,
    )

    private suspend fun dispatch(msg: ServerMsg) {
        when {
            msg.relocate != null -> scope.launch { handleRelocate(msg.relocate, sendResume = true) }
            msg.resumeOk != null -> {
                val fut = mutex.withLock {
                    if (msg.resumeOk.trackUid.isNotEmpty()) trackUid = msg.resumeOk.trackUid
                    lastAckedSeq = msg.resumeOk.lastAcked
                    if (clientSeq < lastAckedSeq) clientSeq = lastAckedSeq
                    queue.ackThrough(lastAckedSeq)
                    unackedFrames = 0
                    val f = resumeWait
                    resumeWait = null
                    f
                }
                resendInFlight()
                flushStaging()
                fut?.complete(msg.resumeOk.lastAcked)
                recvCh.trySend(msg)
            }
            msg.trackStarted != null -> {
                val fut = mutex.withLock {
                    trackUid = msg.trackStarted.trackUid
                    clientSeq = 0
                    lastAckedSeq = 0
                    unackedFrames = 0
                    starting = false
                    val f = startWait
                    startWait = null
                    f
                }
                fut?.complete(msg.trackStarted.trackUid)
                flushStaging()
                recvCh.trySend(msg)
            }
            msg.trackStopped != null -> {
                val fut = mutex.withLock {
                    if (trackUid == msg.trackStopped.trackUid || msg.trackStopped.trackUid.isEmpty()) {
                        trackUid = ""
                        queue.clear()
                        filter.reset()
                    }
                    val f = stopWait
                    stopWait = null
                    f
                }
                fut?.complete(Unit)
                recvCh.trySend(msg)
            }
            msg.ack != null -> {
                mutex.withLock {
                    if (msg.ack.seq > lastAckedSeq) lastAckedSeq = msg.ack.seq
                    queue.ackThrough(msg.ack.seq)
                    unackedFrames = 0
                }
                flushStaging()
            }
            msg.command != null -> cmdCh.trySend(msg.command)
            msg.error != null -> {
                val err = errorFromWire(msg.error)
                mutex.withLock {
                    resumeWait?.let {
                        if (isFatalResumeError(err.code)) {
                            trackUid = ""
                            queue.clear()
                            filter.reset()
                            clientSeq = 0
                            lastAckedSeq = 0
                        }
                        it.completeExceptionally(err)
                        resumeWait = null
                    } ?: startWait?.let {
                        it.completeExceptionally(err)
                        startWait = null
                    } ?: stopWait?.let {
                        it.completeExceptionally(err)
                        stopWait = null
                    } ?: run {
                        if (err.code == ErrorCode.TRACK_NOT_FOUND) {
                            trackUid = ""
                            queue.clear()
                            filter.reset()
                        }
                    }
                }
                if (isAuthError(err.code)) scope.launch { handleAuthError() }
                recvCh.trySend(msg)
            }
            msg.subscribed != null -> {
                mutex.withLock {
                    if (subscriptions.containsKey(msg.subscribed.deviceUid)) {
                        subscriptions[msg.subscribed.deviceUid] = msg.subscribed.sub
                        subByHandle[msg.subscribed.sub] = msg.subscribed.deviceUid
                    }
                }
                recvCh.trySend(msg)
            }
            else -> recvCh.trySend(msg)
        }
    }

    private suspend fun handleRelocate(rel: Relocate, sendResume: Boolean) {
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
            if (result.device != null) cfg = cfg.copy(device = result.device, listener = null)
            if (result.listener != null) cfg = cfg.copy(listener = result.listener, device = null)
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
            unackedFrames = 0
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
        starting = false
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
        val subs = mutex.withLock {
            subByHandle.clear()
            subscriptions.keys.toList()
        }
        for (d in subs) {
            try {
                send(ClientMsg(subscribe = Subscribe(d, includeEvents = true)))
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
            send(ClientMsg(resume = Resume(uid, seq)))
        } catch (e: Exception) {
            mutex.withLock { resumeWait = null }
            throw e
        }
        try {
            fut.await()
        } catch (e: TrackingException) {
            if (isRetryResumeError(e.code)) {
                delay(50)
                sendResumeAndWait()
                return
            }
            throw e
        }
    }

    private suspend fun resendInFlight() {
        val (open, pts) = mutex.withLock {
            (state == ConnectionState.OPEN && webSocket != null && trackUid.isNotEmpty()) to queue.peekAll()
        }
        if (!open || pts.isEmpty()) return
        val frames = encodeInFlightFrames(pts)
        mutex.withLock { unackedFrames += frames.size }
        for (f in frames) {
            webSocket?.send(f.toByteString())
        }
    }

    private suspend fun flushStaging() {
        val assigned = mutex.withLock {
            val open = state == ConnectionState.OPEN && webSocket != null && trackUid.isNotEmpty()
            val window = MAX_IN_FLIGHT_FRAMES - unackedFrames
            if (!open || window <= 0) return@withLock emptyList()
            val points = queue.takeStaging(window * 100)
            points.map {
                clientSeq += 1
                val q = QueuedPoint(clientSeq, it, sent = false)
                queue.pushInFlight(q.seq, q.point)
                q
            }
        }
        if (assigned.isEmpty()) return
        val frames = encodeInFlightFrames(assigned)
        mutex.withLock { unackedFrames += frames.size }
        for (f in frames) {
            webSocket?.send(f.toByteString())
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
            for (uid in config.subscribe) {
                if (uid.isNotEmpty()) client.subscriptions[uid] = 0
            }
            client.dial(sendResume = false)
            return client
        }
    }
}

suspend fun connect(config: Config): TrackingClient = TrackingClient.connectSuspend(config)

fun connectBlocking(config: Config): TrackingClient = TrackingClient.connect(config)
