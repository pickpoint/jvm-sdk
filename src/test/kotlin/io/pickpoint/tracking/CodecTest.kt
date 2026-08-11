package io.pickpoint.tracking

import io.pickpoint.tracking.v2.ClientMsg
import io.pickpoint.tracking.v2.Hello
import io.pickpoint.tracking.v2.LatLng
import io.pickpoint.tracking.v2.ServerMsg
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodecTest {
    @Test
    fun stampLatLngDefaultTimestamp() {
        val before = System.currentTimeMillis()
        val p = stampLatLng(LatLng.newBuilder().setLatitude(1.0).setLongitude(2.0).build())
        val after = System.currentTimeMillis()
        assertTrue(p.hasTimestampMs())
        assertTrue(p.timestampMs in before..after)
    }

    @Test
    fun stampLatLngPreservesTimestamp() {
        val p = stampLatLng(
            LatLng.newBuilder().setLatitude(1.0).setLongitude(2.0).setTimestampMs(42).build(),
        )
        assertEquals(42, p.timestampMs)
    }

    @Test
    fun codecRoundTripResume() {
        val msg = clientResume("t1", 9)
        val round = ClientMsg.parseFrom(encodeClientMsg(msg))
        assertEquals("t1", round.resume.trackUid)
        assertEquals(9, round.resume.lastClientSeq)
    }

    @Test
    fun codecRoundTripHello() {
        val msg = ServerMsg.newBuilder()
            .setHello(Hello.newBuilder().setNodeId("n1").setShard(7))
            .build()
        val got = decodeServerMsg(msg.toByteArray())
        assertEquals("n1", got.hello.nodeId)
        assertEquals(7, got.hello.shard)
    }

    @Test
    fun goldenResumeWire() {
        val b = encodeClientMsg(clientResume("track-uid-9", 42))
        val got = b.joinToString("") { "%02x".format(it) }
        assertEquals("0a0f0a0b747261636b2d7569642d39102a", got)
    }
}
