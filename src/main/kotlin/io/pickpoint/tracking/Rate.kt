package io.pickpoint.tracking

import java.time.Instant

const val MIN_PUBLISH_INTERVAL_MS: Long = 1000L / MAX_PUBLISH_HZ

fun canAcceptPublish(nextAllowedAt: Instant, now: Instant, pointCount: Int): Boolean {
    if (pointCount <= 0) return true
    return !now.isBefore(nextAllowedAt)
}

fun nextPublishAllowedAt(nextAllowedAt: Instant, now: Instant, pointCount: Int): Instant {
    var start = now
    if (nextAllowedAt.isAfter(start)) start = nextAllowedAt
    val n = pointCount.coerceAtLeast(0)
    return start.plus(MIN_PUBLISH_INTERVAL.multipliedBy(n.toLong()))
}
