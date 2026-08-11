package io.pickpoint.tracking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class RateTest {
    @Test
    fun publishRateSpacing() {
        val t0 = Instant.parse("2020-01-01T00:00:00Z")
        assertTrue(canAcceptPublish(t0, t0, 1))
        val next = nextPublishAllowedAt(t0, t0, 1)
        assertEquals(t0.plusMillis(MIN_PUBLISH_INTERVAL_MS), next)
        assertFalse(canAcceptPublish(next, t0, 1))
        assertTrue(canAcceptPublish(next, next, 1))
    }

    @Test
    fun publishRateBatchSlots() {
        val t0 = Instant.parse("2020-01-01T00:00:00Z")
        val next = nextPublishAllowedAt(t0, t0, 3)
        assertEquals(t0.plusMillis(MIN_PUBLISH_INTERVAL_MS * 3), next)
    }
}
