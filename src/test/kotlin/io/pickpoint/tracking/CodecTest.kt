package io.pickpoint.tracking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CodecTest {
    @Test
    fun stampLatLngDefaultTimestamp() {
        val before = System.currentTimeMillis()
        val p = stampLatLng(LatLng(1.0, 2.0))
        val after = System.currentTimeMillis()
        assertTrue(p.timestampMs != null)
        assertTrue(p.timestampMs!! in before..after)
    }

    @Test
    fun stampLatLngPreservesTimestamp() {
        val p = stampLatLng(LatLng(1.0, 2.0, timestampMs = 42))
        assertEquals(42, p.timestampMs)
    }

    @Test
    fun goldenAckSeq1() {
        val b = encodeServerMsg(ServerMsg(ack = Ack(1)))
        assertEquals("8501000000", bytesToHex(b))
        val msg = decodeServerMsg(b)
        assertEquals(1, msg?.ack?.seq)
    }

    @Test
    fun goldenLoc55N37E() {
        val b = encodeClientMsg(ClientMsg(loc = Loc(1, listOf(LatLng(55.0, 37.0)))))
        assertEquals("04010000000100c03b470340933402", bytesToHex(b))
    }

    @Test
    fun goldenResume() {
        val b = encodeClientMsg(clientResume("00112233-4455-6677-8899-aabbccddeeff", 45))
        assertEquals("0100112233445566778899aabbccddeeff2d000000", bytesToHex(b))
    }

    @Test
    fun codecRoundTripResume() {
        val msg = clientResume("00112233-4455-6677-8899-aabbccddeeff", 9)
        val round = decodeClientMsg(encodeClientMsg(msg))
        assertEquals("00112233-4455-6677-8899-aabbccddeeff", round.resume?.trackUid)
        assertEquals(9, round.resume?.lastSeq)
    }

    @Test
    fun codecRoundTripHello() {
        val msg = ServerMsg(hello = Hello(2, 7, "00112233-4455-6677-8899-aabbccddeeff"))
        val got = decodeServerMsg(encodeServerMsg(msg))
        assertEquals("00112233-4455-6677-8899-aabbccddeeff", got?.hello?.nodeId)
        assertEquals(7, got?.hello?.shard)
        assertEquals(2, got?.hello?.version)
    }

    @Test
    fun deviceAckVsListenerLoc() {
        val ack = encodeServerMsg(ServerMsg(ack = Ack(1)))
        val loc = encodeServerMsg(ServerMsg(loc = ServerLoc(1, 1, LatLng(55.0, 37.0))))
        assertEquals(S_ACK, ack[0].toInt() and 0xff)
        assertEquals(S_LOC, loc[0].toInt() and 0xff)
        assertNotEquals(ack[0], loc[0])
    }

    @Test
    fun encodeLocSplitsOnI16Overflow() {
        val frames = encodeLocFrames(2, listOf(LatLng(0.0, 0.0), LatLng(4.0, 0.0)))
        assertEquals(2, frames.size)
        assertEquals(1, decodeClientMsg(frames[0]).loc?.seq)
        assertEquals(2, decodeClientMsg(frames[1]).loc?.seq)
    }

    @Test
    fun unknownServerTypeIgnored() {
        assertNull(decodeServerMsg(byteArrayOf(0x8D.toByte())))
    }

    @Test
    fun unsubscribeIsSubHandle() {
        val b = encodeClientMsg(ClientMsg(unsubscribe = Unsubscribe(7)))
        assertEquals(listOf(0x06.toByte(), 0x07.toByte()), b.toList())
    }

    @Test
    fun fatalResumeAuthAndTrackNotFoundOnly() {
        assertTrue(isFatalResumeError(ErrorCode.AUTH))
        assertTrue(isFatalResumeError(ErrorCode.TRACK_NOT_FOUND))
        assertTrue(!isFatalResumeError(ErrorCode.FENCED))
        assertTrue(isRetryResumeError(ErrorCode.FENCED))
    }
}
