package io.pickpoint.tracking

data class QueuedPoint(val seq: Long, val point: LatLng, val sent: Boolean = true)

class OfflineQueue(
    maxSize: Int = MAX_BUFFER_POINTS,
    private val onGap: ((Int) -> Unit)? = null,
) {
    private val maxSize = if (maxSize <= 0) MAX_BUFFER_POINTS else maxSize
    private val staging = ArrayList<LatLng>()
    private val inflight = ArrayList<QueuedPoint>()

    fun size(): Int = staging.size + inflight.size
    fun stagingSize(): Int = staging.size

    fun pushStaging(point: LatLng) {
        staging.add(point)
        enforceCap()
    }

    fun enqueue(seq: Long, point: LatLng): Int {
        inflight.add(QueuedPoint(seq, point, sent = true))
        val before = size()
        enforceCap()
        return (before - size()).coerceAtLeast(0)
    }

    fun pushInFlight(seq: Long, point: LatLng) {
        inflight.add(QueuedPoint(seq, point, sent = false))
        enforceCap()
    }

    fun ackThrough(ack: Long) {
        inflight.removeAll { it.seq <= ack }
    }

    fun peekAll(): List<QueuedPoint> = inflight.toList()
    fun peekStaging(): List<LatLng> = staging.toList()

    fun takeStaging(n: Int): List<LatLng> {
        val take = n.coerceIn(0, staging.size)
        val out = staging.subList(0, take).toList()
        repeat(take) { staging.removeAt(0) }
        return out
    }

    fun clear() {
        staging.clear()
        inflight.clear()
    }

    private fun enforceCap() {
        var dropped = 0
        while (size() > maxSize) {
            if (collapseOneCollinear(staging)) {
                dropped++
                continue
            }
            if (staging.isNotEmpty()) {
                staging.removeAt(0)
                dropped++
                continue
            }
            if (inflight.isNotEmpty()) {
                inflight.removeAt(0)
                dropped++
                continue
            }
            break
        }
        if (dropped > 0) onGap?.invoke(dropped)
    }
}
