package io.pickpoint.tracking

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

class DecodeException(message: String) : RuntimeException(message)
class EncodeException(message: String) : RuntimeException(message)

private const val PF_ALT = 1
private const val PF_ACC = 2
private const val PF_TIME = 16
private const val LAT_MIN = -90_000_000
private const val LAT_MAX = 90_000_000
private const val LON_MIN = -180_000_000
private const val LON_MAX = 180_000_000
private const val I16_MIN = -32768
private const val I16_MAX = 32767

private class R(data: ByteArray) {
    private val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

    fun need(n: Int) {
        if (buf.remaining() < n) throw DecodeException("truncated frame")
    }

    fun u8(): Int {
        need(1)
        return buf.get().toInt() and 0xff
    }

    fun u16(): Int {
        need(2)
        return buf.short.toInt() and 0xffff
    }

    fun u32(): Long {
        need(4)
        return buf.int.toLong() and 0xffffffffL
    }

    fun i16(): Int {
        need(2)
        return buf.short.toInt()
    }

    fun i32(): Int {
        need(4)
        return buf.int
    }

    fun i64(): Long {
        need(8)
        return buf.long
    }

    fun f64(): Double {
        need(8)
        return buf.double
    }

    fun uuid(): String {
        need(16)
        val b = ByteArray(16)
        buf.get(b)
        return formatUuid(b)
    }

    fun uuidOpt(): String {
        need(16)
        val b = ByteArray(16)
        buf.get(b)
        return if (b.all { it == 0.toByte() }) "" else formatUuid(b)
    }

    fun str(): String {
        val n = u16()
        if (n > MAX_STRING) throw DecodeException("invalid frame")
        need(n)
        val b = ByteArray(n)
        buf.get(b)
        return String(b, Charsets.UTF_8)
    }

    fun bytes(): ByteArray {
        val n = u16()
        if (n > MAX_STRING) throw DecodeException("invalid frame")
        need(n)
        val b = ByteArray(n)
        buf.get(b)
        return b
    }
}

private class W {
    private val buf = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
    private val extra = ArrayList<ByteArray>()
    private var extraLen = 0

    private fun ensure(n: Int) {
        if (buf.remaining() >= n) return
        val copy = ByteArray(buf.position())
        buf.flip()
        buf.get(copy)
        extra.add(copy)
        extraLen += copy.size
        buf.clear()
    }

    fun u8(v: Int) {
        ensure(1)
        buf.put(v.toByte())
    }

    fun u16(v: Int) {
        ensure(2)
        buf.putShort(v.toShort())
    }

    fun u32(v: Long) {
        ensure(4)
        buf.putInt(v.toInt())
    }

    fun i16(v: Int) {
        ensure(2)
        buf.putShort(v.toShort())
    }

    fun i32(v: Int) {
        ensure(4)
        buf.putInt(v)
    }

    fun i64(v: Long) {
        ensure(8)
        buf.putLong(v)
    }

    fun f64(v: Double) {
        ensure(8)
        buf.putDouble(v)
    }

    fun raw(b: ByteArray) {
        ensure(b.size)
        if (buf.remaining() >= b.size) {
            buf.put(b)
        } else {
            extra.add(b)
            extraLen += b.size
        }
    }

    fun finish(): ByteArray {
        val head = ByteArray(buf.position())
        val dup = buf.duplicate()
        dup.flip()
        dup.get(head)
        if (extra.isEmpty()) return head
        val out = ByteArray(extraLen + head.size)
        var o = 0
        for (e in extra) {
            System.arraycopy(e, 0, out, o, e.size)
            o += e.size
        }
        System.arraycopy(head, 0, out, o, head.size)
        return out
    }
}

fun parseUuid(s: String): ByteArray {
    if (s.isEmpty()) return ByteArray(16)
    val h = s.replace("-", "").lowercase()
    if (h.length != 32) return ByteArray(16)
    return try {
        h.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    } catch (_: Exception) {
        ByteArray(16)
    }
}

fun formatUuid(b: ByteArray): String {
    val h = b.joinToString("") { "%02x".format(it) }
    return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
}

private fun W.uuid(s: String) = raw(parseUuid(s))

private fun W.str(s: String) {
    val b = s.toByteArray(Charsets.UTF_8).let { if (it.size > MAX_STRING) it.copyOf(MAX_STRING) else it }
    u16(b.size)
    raw(b)
}

private fun W.bytes(b: ByteArray) {
    val n = min(b.size, MAX_STRING)
    u16(n)
    raw(b.copyOf(n))
}

fun degToMicro(d: Double): Int = (d * 1_000_000.0).roundToInt()
fun microToDeg(m: Int): Double = m / 1_000_000.0

fun microDeltaFits(prevLat: Int, prevLon: Int, lat: Int, lon: Int): Boolean {
    val dlat = lat.toLong() - prevLat
    val dlon = lon.toLong() - prevLon
    return dlat in I16_MIN..I16_MAX && dlon in I16_MIN..I16_MAX
}

private fun checkCoord(lat: Int, lon: Int) {
    if (lat !in LAT_MIN..LAT_MAX || lon !in LON_MIN..LON_MAX) {
        throw DecodeException("invalid frame")
    }
}

private fun W.writePoint(p: LatLng, prev: Pair<Int, Int>?): Pair<Int, Int> {
    val lat = degToMicro(p.latitude)
    val lon = degToMicro(p.longitude)
    var flags = 0
    if (p.altitude != null) flags = flags or PF_ALT
    if (p.accuracy != null) flags = flags or PF_ACC
    if (p.timestampMs != null) flags = flags or PF_TIME
    u8(flags)
    if (prev != null) {
        if (!microDeltaFits(prev.first, prev.second, lat, lon)) {
            throw EncodeException("intra-frame delta overflows i16")
        }
        i16(lat - prev.first)
        i16(lon - prev.second)
    } else {
        i32(lat)
        i32(lon)
    }
    if (p.altitude != null) i32((p.altitude * 1000.0).roundToInt())
    if (p.accuracy != null) {
        val cm = (p.accuracy * 100.0).roundToInt().coerceIn(0, 0xffff)
        u16(cm)
    }
    if (p.timestampMs != null) i64(p.timestampMs)
    return lat to lon
}

private fun W.writeAbs(p: LatLng) {
    writePoint(p, null)
}

private fun R.readPoint(prev: Pair<Int, Int>?): Triple<LatLng, Int, Int> {
    val flags = u8()
    val lat: Int
    val lon: Int
    if (prev != null) {
        lat = prev.first + i16()
        lon = prev.second + i16()
    } else {
        lat = i32()
        lon = i32()
    }
    checkCoord(lat, lon)
    var alt: Double? = null
    var acc: Double? = null
    var ts: Long? = null
    if (flags and PF_ALT != 0) alt = i32() / 1000.0
    if (flags and PF_ACC != 0) acc = u16() / 100.0
    if (flags and PF_TIME != 0) ts = i64()
    return Triple(LatLng(microToDeg(lat), microToDeg(lon), alt, acc, timestampMs = ts), lat, lon)
}

private fun W.writeRouteAbs(route: List<LatLng>) {
    val n = min(route.size, 0xffff)
    u16(n)
    for (i in 0 until n) {
        i32(degToMicro(route[i].latitude))
        i32(degToMicro(route[i].longitude))
    }
}

private fun R.readRouteAbs(): List<LatLng> {
    val n = u16()
    return List(n) {
        val lat = i32()
        val lon = i32()
        checkCoord(lat, lon)
        LatLng(microToDeg(lat), microToDeg(lon))
    }
}

fun encodeLocFrames(lastSeq: Long, points: List<LatLng>): List<ByteArray> {
    if (points.isEmpty()) return emptyList()
    val firstSeq = lastSeq + 1 - points.size
    val out = ArrayList<ByteArray>()
    var i = 0
    while (i < points.size) {
        val start = i
        var prevLat = degToMicro(points[i].latitude)
        var prevLon = degToMicro(points[i].longitude)
        i++
        while (i < points.size && (i - start) < MAX_LOC_POINTS) {
            val lat = degToMicro(points[i].latitude)
            val lon = degToMicro(points[i].longitude)
            if (!microDeltaFits(prevLat, prevLon, lat, lon)) break
            prevLat = lat
            prevLon = lon
            i++
        }
        val chunk = points.subList(start, i)
        val seq = firstSeq + i - 1
        out.add(encodeLocFrame(seq, chunk))
    }
    return out
}

fun encodeInFlightFrames(pts: List<QueuedPoint>): List<ByteArray> {
    if (pts.isEmpty()) return emptyList()
    val out = ArrayList<ByteArray>()
    var i = 0
    while (i < pts.size) {
        val start = i
        var prevLat = degToMicro(pts[i].point.latitude)
        var prevLon = degToMicro(pts[i].point.longitude)
        i++
        while (i < pts.size && (i - start) < MAX_LOC_POINTS) {
            if (pts[i].seq != pts[i - 1].seq + 1) break
            val lat = degToMicro(pts[i].point.latitude)
            val lon = degToMicro(pts[i].point.longitude)
            if (!microDeltaFits(prevLat, prevLon, lat, lon)) break
            prevLat = lat
            prevLon = lon
            i++
        }
        val chunk = pts.subList(start, i)
        out.add(encodeLocFrame(chunk.last().seq, chunk.map { it.point }))
    }
    return out
}

private fun encodeLocFrame(seq: Long, points: List<LatLng>): ByteArray {
    val w = W()
    w.u8(C_LOC)
    w.u32(seq)
    w.u8(points.size)
    var prev: Pair<Int, Int>? = null
    for (p in points) prev = w.writePoint(p, prev)
    return w.finish()
}

fun encodeClientMsg(msg: ClientMsg): ByteArray {
    val w = W()
    when {
        msg.resume != null -> {
            w.u8(C_RESUME)
            w.uuid(msg.resume.trackUid)
            w.u32(msg.resume.lastSeq)
        }
        msg.trackStart != null -> {
            w.u8(C_TRACK_START)
            w.u8(if (msg.trackStart.location != null) 1 else 0)
            if (msg.trackStart.location != null) w.writeAbs(msg.trackStart.location)
            w.writeRouteAbs(msg.trackStart.route)
            w.bytes(msg.trackStart.metadata)
        }
        msg.trackStop != null -> w.u8(C_TRACK_STOP)
        msg.loc != null -> {
            val frames = encodeLocFrames(msg.loc.seq, msg.loc.points)
            if (frames.isEmpty()) throw EncodeException("empty loc")
            return frames.first()
        }
        msg.subscribe != null -> {
            w.u8(C_SUBSCRIBE)
            w.uuid(msg.subscribe.deviceUid)
            w.u8(if (msg.subscribe.includeEvents) 1 else 0)
            w.u16(min(msg.subscribe.minIntervalMs, 0xffff))
        }
        msg.unsubscribe != null -> {
            w.u8(C_UNSUBSCRIBE)
            w.u8(msg.unsubscribe.sub)
        }
        msg.event != null -> {
            w.u8(C_EVENT)
            w.bytes(msg.event.payload)
            w.i64(msg.event.timestampMs)
        }
        msg.commandAck != null -> {
            w.u8(C_COMMAND_ACK)
            w.uuid(msg.commandAck.commandId)
            w.u8(msg.commandAck.status.code)
            w.str(msg.commandAck.message)
        }
        else -> throw EncodeException("empty client msg")
    }
    return w.finish()
}

fun decodeClientMsg(data: ByteArray): ClientMsg {
    if (data.isEmpty()) throw DecodeException("truncated frame")
    val r = R(data)
    return when (val typ = r.u8()) {
        C_RESUME -> ClientMsg(resume = Resume(r.uuid(), r.u32()))
        C_TRACK_START -> {
            val flags = r.u8()
            val loc = if (flags and 1 != 0) r.readPoint(null).first else null
            ClientMsg(trackStart = TrackStart(loc, r.readRouteAbs(), r.bytes()))
        }
        C_TRACK_STOP -> ClientMsg(trackStop = TrackStop())
        C_LOC -> {
            val seq = r.u32()
            val count = r.u8()
            if (count == 0 || count > MAX_LOC_POINTS) throw DecodeException("invalid frame")
            val points = ArrayList<LatLng>(count)
            var prev: Pair<Int, Int>? = null
            repeat(count) {
                val (p, lat, lon) = r.readPoint(prev)
                points.add(p)
                prev = lat to lon
            }
            ClientMsg(loc = Loc(seq, points))
        }
        C_SUBSCRIBE -> ClientMsg(
            subscribe = Subscribe(r.uuid(), r.u8() and 1 != 0, r.u16()),
        )
        C_UNSUBSCRIBE -> ClientMsg(unsubscribe = Unsubscribe(r.u8()))
        C_EVENT -> ClientMsg(event = Event(r.bytes(), r.i64()))
        C_COMMAND_ACK -> ClientMsg(
            commandAck = CommandAck(r.uuid(), CommandAckStatus.fromU8(r.u8()), r.str()),
        )
        0x00, 0x7F, 0xFF -> throw DecodeException("invalid frame")
        else -> if (typ in 0x01..0x7E) ClientMsg(unknown = typ) else throw DecodeException("invalid frame")
    }
}

fun encodeServerMsg(msg: ServerMsg): ByteArray {
    val w = W()
    when {
        msg.hello != null -> {
            w.u8(S_HELLO)
            w.u8(msg.hello.version)
            w.u16(msg.hello.shard)
            w.uuid(msg.hello.nodeId)
        }
        msg.relocate != null -> {
            w.u8(S_RELOCATE)
            w.u32(msg.relocate.retryAfterMs.toLong())
            w.str(msg.relocate.endpoint)
        }
        msg.resumeOk != null -> {
            w.u8(S_RESUME_OK)
            w.uuid(msg.resumeOk.trackUid)
            w.u32(msg.resumeOk.lastAcked)
        }
        msg.trackStarted != null -> {
            w.u8(S_TRACK_STARTED)
            w.uuid(msg.trackStarted.trackUid)
            w.bytes(msg.trackStarted.metadata)
        }
        msg.trackStopped != null -> {
            w.u8(S_TRACK_STOPPED)
            w.uuid(msg.trackStopped.trackUid)
        }
        msg.ack != null -> {
            w.u8(S_ACK)
            w.u32(msg.ack.seq)
        }
        msg.loc != null -> {
            w.u8(S_LOC)
            w.u8(msg.loc.sub)
            w.u32(msg.loc.seq)
            w.writeAbs(msg.loc.point)
        }
        msg.subscribed != null -> {
            val s = msg.subscribed
            w.u8(S_SUBSCRIBED)
            w.u8(s.sub)
            w.uuid(s.deviceUid)
            w.uuid(s.trackUid)
            w.u8(if (s.online) 1 else 0)
            var flags = 0
            if (s.lastLocation != null) flags = flags or 1
            if (s.lastSeenMs != null) flags = flags or 2
            if (s.route.isNotEmpty()) flags = flags or 4
            w.u8(flags)
            if (s.lastLocation != null) w.writeAbs(s.lastLocation)
            if (s.lastSeenMs != null) w.i64(s.lastSeenMs)
            if (flags and 4 != 0) w.writeRouteAbs(s.route)
            w.f64(s.estDistance)
            w.f64(s.estDuration)
            w.str(s.startName)
            w.str(s.endName)
            w.bytes(s.metadata)
        }
        msg.error != null -> {
            w.u8(S_ERROR)
            w.u8(msg.error.code.code)
            w.u32(msg.error.retryAfterMs.toLong())
            w.uuid(msg.error.trackUid)
            w.str(msg.error.message)
        }
        msg.eventAdded != null -> {
            w.u8(S_EVENT_ADDED)
            w.u8(msg.eventAdded.sub)
            w.bytes(msg.eventAdded.payload)
            w.i64(msg.eventAdded.timestampMs)
        }
        msg.command != null -> {
            w.u8(S_COMMAND)
            w.uuid(msg.command.commandId)
            w.bytes(msg.command.payload)
            w.i64(msg.command.timestampMs)
        }
        msg.presence != null -> {
            w.u8(S_PRESENCE)
            w.u8(msg.presence.sub)
            w.u8(if (msg.presence.online) 1 else 0)
            w.i64(msg.presence.lastSeenMs)
        }
        else -> throw EncodeException("empty server msg")
    }
    return w.finish()
}

fun decodeServerMsg(data: ByteArray): ServerMsg? {
    if (data.isEmpty()) throw DecodeException("truncated frame")
    val r = R(data)
    return when (val typ = r.u8()) {
        S_HELLO -> ServerMsg(hello = Hello(r.u8(), r.u16(), r.uuid()))
        S_RELOCATE -> ServerMsg(relocate = Relocate(r.u32().toInt(), r.str()))
        S_RESUME_OK -> ServerMsg(resumeOk = ResumeOk(r.uuid(), r.u32()))
        S_TRACK_STARTED -> ServerMsg(trackStarted = TrackStarted(r.uuid(), r.bytes()))
        S_TRACK_STOPPED -> ServerMsg(trackStopped = TrackStopped(r.uuid()))
        S_ACK -> ServerMsg(ack = Ack(r.u32()))
        S_LOC -> {
            val sub = r.u8()
            val seq = r.u32()
            val (p, _, _) = r.readPoint(null)
            ServerMsg(loc = ServerLoc(sub, seq, p))
        }
        S_SUBSCRIBED -> {
            val sub = r.u8()
            val device = r.uuid()
            val track = r.uuidOpt()
            val online = r.u8() != 0
            val flags = r.u8()
            val lastLoc = if (flags and 1 != 0) r.readPoint(null).first else null
            val lastSeen = if (flags and 2 != 0) r.i64() else null
            val route = if (flags and 4 != 0) r.readRouteAbs() else emptyList()
            ServerMsg(
                subscribed = Subscribed(
                    sub, device, track, online, lastLoc, lastSeen, route,
                    r.f64(), r.f64(), r.str(), r.str(), r.bytes(),
                ),
            )
        }
        S_ERROR -> {
            val code = ErrorCode.fromU8(r.u8()) ?: throw DecodeException("invalid frame")
            val retry = r.u32().toInt()
            ServerMsg(error = WireError(code, retry, r.uuidOpt(), r.str()))
        }
        S_EVENT_ADDED -> ServerMsg(eventAdded = EventAdded(r.u8(), r.bytes(), r.i64()))
        S_COMMAND -> ServerMsg(command = Command(r.uuid(), r.bytes(), r.i64()))
        S_PRESENCE -> ServerMsg(presence = Presence(r.u8(), r.u8() != 0, r.i64()))
        0x00, 0x7F, 0xFF, 0x8C -> throw DecodeException("invalid frame")
        else -> if (typ in 0x80..0xFE) null else throw DecodeException("invalid frame")
    }
}

fun clientResume(trackUid: String, lastClientSeq: Long): ClientMsg =
    ClientMsg(resume = Resume(trackUid, lastClientSeq))

fun stampLatLng(point: LatLng): LatLng =
    if (point.timestampMs != null) point else point.copy(timestampMs = System.currentTimeMillis())

fun cloneLatLng(point: LatLng): LatLng = point.copy()

fun latLng(lat: Double, lon: Double, altitude: Double? = null, accuracy: Double? = null): LatLng =
    LatLng(lat, lon, altitude, accuracy)

fun stripLiveTime(p: LatLng): LatLng = p.copy(timestampMs = null)

fun bytesToHex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }
