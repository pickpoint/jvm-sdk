package io.pickpoint.tracking

const val PROTOCOL_VERSION: Int = 2
const val MAX_STRING: Int = 4096
const val MAX_LOC_POINTS: Int = 100
const val MAX_BUFFER_POINTS: Int = 10_000
const val MAX_IN_FLIGHT_FRAMES: Int = 8

const val C_RESUME: Int = 0x01
const val C_TRACK_START: Int = 0x02
const val C_TRACK_STOP: Int = 0x03
const val C_LOC: Int = 0x04
const val C_SUBSCRIBE: Int = 0x05
const val C_UNSUBSCRIBE: Int = 0x06
const val C_EVENT: Int = 0x07
const val C_COMMAND_ACK: Int = 0x08

const val S_HELLO: Int = 0x80
const val S_RELOCATE: Int = 0x81
const val S_RESUME_OK: Int = 0x82
const val S_TRACK_STARTED: Int = 0x83
const val S_TRACK_STOPPED: Int = 0x84
const val S_ACK: Int = 0x85
const val S_LOC: Int = 0x86
const val S_SUBSCRIBED: Int = 0x87
const val S_ERROR: Int = 0x88
const val S_EVENT_ADDED: Int = 0x89
const val S_COMMAND: Int = 0x8A
const val S_PRESENCE: Int = 0x8B

enum class ErrorCode(val code: Int) {
    AUTH(1),
    TRACK_NOT_FOUND(2),
    FENCED(3),
    TRY_AGAIN(4),
    INVALID(5),
    UNAUTHORIZED(6),
    ;

    companion object {
        fun fromU8(v: Int): ErrorCode? = entries.firstOrNull { it.code == v }
    }
}

enum class CommandAckStatus(val code: Int) {
    UNSPECIFIED(0),
    OK(1),
    REJECTED(2),
    FAILED(3),
    ;

    companion object {
        fun fromU8(v: Int): CommandAckStatus = entries.firstOrNull { it.code == v } ?: UNSPECIFIED
    }
}

data class LatLng(
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double? = null,
    val accuracy: Double? = null,
    val heading: Double? = null,
    val speed: Double? = null,
    val timestampMs: Long? = null,
)

data class Resume(val trackUid: String, val lastSeq: Long)
data class TrackStart(val location: LatLng? = null, val route: List<LatLng> = emptyList(), val metadata: ByteArray = ByteArray(0))
data class TrackStop(val unused: Unit = Unit)
data class Loc(val seq: Long, val points: List<LatLng>)
data class Subscribe(val deviceUid: String, val includeEvents: Boolean = true, val minIntervalMs: Int = 0)
data class Unsubscribe(val sub: Int)
data class Event(val payload: ByteArray, val timestampMs: Long = 0)
data class CommandAck(val commandId: String, val status: CommandAckStatus = CommandAckStatus.OK, val message: String = "")

data class ClientMsg(
    val resume: Resume? = null,
    val trackStart: TrackStart? = null,
    val trackStop: TrackStop? = null,
    val loc: Loc? = null,
    val subscribe: Subscribe? = null,
    val unsubscribe: Unsubscribe? = null,
    val event: Event? = null,
    val commandAck: CommandAck? = null,
    val unknown: Int? = null,
)

data class Hello(val version: Int = PROTOCOL_VERSION, val shard: Int = 0, val nodeId: String = "")
data class Relocate(val retryAfterMs: Int = 0, val endpoint: String = "")
data class ResumeOk(val trackUid: String, val lastAcked: Long)
data class TrackStarted(val trackUid: String, val metadata: ByteArray = ByteArray(0))
data class TrackStopped(val trackUid: String)
data class Ack(val seq: Long)
data class ServerLoc(val sub: Int, val seq: Long, val point: LatLng)
data class Subscribed(
    val sub: Int,
    val deviceUid: String,
    val trackUid: String = "",
    val online: Boolean = false,
    val lastLocation: LatLng? = null,
    val lastSeenMs: Long? = null,
    val route: List<LatLng> = emptyList(),
    val estDistance: Double = 0.0,
    val estDuration: Double = 0.0,
    val startName: String = "",
    val endName: String = "",
    val metadata: ByteArray = ByteArray(0),
)
data class WireError(
    val code: ErrorCode,
    val retryAfterMs: Int = 0,
    val trackUid: String = "",
    val message: String = "",
)
data class EventAdded(val sub: Int, val payload: ByteArray, val timestampMs: Long = 0)
data class Command(val commandId: String, val payload: ByteArray, val timestampMs: Long = 0)
data class Presence(val sub: Int, val online: Boolean, val lastSeenMs: Long = 0)

data class ServerMsg(
    val hello: Hello? = null,
    val relocate: Relocate? = null,
    val resumeOk: ResumeOk? = null,
    val trackStarted: TrackStarted? = null,
    val trackStopped: TrackStopped? = null,
    val ack: Ack? = null,
    val loc: ServerLoc? = null,
    val subscribed: Subscribed? = null,
    val error: WireError? = null,
    val eventAdded: EventAdded? = null,
    val command: Command? = null,
    val presence: Presence? = null,
)
