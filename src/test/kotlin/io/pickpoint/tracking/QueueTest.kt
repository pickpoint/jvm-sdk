package io.pickpoint.tracking

import io.pickpoint.tracking.v2.LatLng
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class QueueTest {
    @Test
    fun offlineQueueAckThrough() {
        val q = OfflineQueue(10)
        q.enqueue(1, LatLng.getDefaultInstance())
        q.enqueue(2, LatLng.getDefaultInstance())
        q.enqueue(3, LatLng.getDefaultInstance())
        q.ackThrough(2)
        assertEquals(1, q.size())
        assertEquals(3L, q.peekAll().single().seq)
    }

    @Test
    fun offlineQueueDropOldest() {
        var dropped = 0
        val q = OfflineQueue(3) { dropped += it }
        repeat(5) { i ->
            q.enqueue(i.toLong() + 1, LatLng.newBuilder().setLatitude(i.toDouble()).build())
        }
        assertEquals(3, q.size())
        assertEquals(2, dropped)
        assertEquals(3L, q.peekAll().first().seq)
    }
}
