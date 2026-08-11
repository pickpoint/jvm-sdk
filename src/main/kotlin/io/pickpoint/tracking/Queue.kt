package io.pickpoint.tracking

import io.pickpoint.tracking.v2.LatLng

data class QueuedPoint(val seq: Long, val point: LatLng)

/** Bounded offline queue keyed by clientSeq. Drop-oldest on overflow. */
class OfflineQueue(
    maxSize: Int = 10_000,
    private val onGap: ((Int) -> Unit)? = null,
) {
    private val maxSize = if (maxSize <= 0) 10_000 else maxSize
    private val items = ArrayDeque<QueuedPoint>()

    fun size(): Int = items.size

    fun enqueue(seq: Long, point: LatLng) {
        items.addLast(QueuedPoint(seq, point))
        if (items.size > maxSize) {
            val dropped = items.size - maxSize
            repeat(dropped) { items.removeFirst() }
            onGap?.invoke(dropped)
        }
    }

    fun ackThrough(ack: Long) {
        while (items.isNotEmpty() && items.first().seq <= ack) {
            items.removeFirst()
        }
    }

    fun peekAll(): List<QueuedPoint> = items.toList()

    fun clear() {
        items.clear()
    }
}
