package io.pickpoint.tracking

import io.pickpoint.tracking.v2.ClientMsg
import io.pickpoint.tracking.v2.LatLng
import io.pickpoint.tracking.v2.Resume
import io.pickpoint.tracking.v2.ServerMsg

fun encodeClientMsg(msg: ClientMsg): ByteArray = msg.toByteArray()

fun decodeServerMsg(raw: ByteArray): ServerMsg = ServerMsg.parseFrom(raw)

/** Builds a resume ClientMsg (for golden / wire tests). */
fun clientResume(trackUid: String, lastClientSeq: Long): ClientMsg =
    ClientMsg.newBuilder()
        .setResume(Resume.newBuilder().setTrackUid(trackUid).setLastClientSeq(lastClientSeq))
        .build()

fun stampLatLng(point: LatLng): LatLng {
    if (point.hasTimestampMs()) return point
    return point.toBuilder().setTimestampMs(System.currentTimeMillis()).build()
}

fun cloneLatLng(point: LatLng): LatLng = point.toBuilder().build()

fun latLng(lat: Double, lon: Double, altitude: Double? = null, accuracy: Double? = null): LatLng {
    val b = LatLng.newBuilder().setLatitude(lat).setLongitude(lon)
    if (altitude != null) b.setAltitude(altitude)
    if (accuracy != null) b.setAccuracy(accuracy)
    return b.build()
}
