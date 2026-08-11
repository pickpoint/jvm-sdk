package io.pickpoint.tracking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration

class BackoffTest {
    @Test
    fun backoffFullJitter() {
        val state = newBackoff(Duration.ofMillis(100), Duration.ofMillis(800), 0)
        val d = nextDelay(state) { 0.5 }!!
        assertTrue(d.toMillis() in 0..100)
        assertEquals(1, state.attempt)
        val d2 = nextDelay(state) { 0.5 }!!
        assertTrue(d2.toMillis() in 0..200)
    }

    @Test
    fun respectsMaxAttempts() {
        val state = newBackoff(Duration.ofMillis(10), Duration.ofMillis(100), 2)
        assertNotNull(nextDelay(state) { 0.5 })
        assertNotNull(nextDelay(state) { 0.5 })
        assertNull(nextDelay(state) { 0.5 })
    }

    @Test
    fun resetClearsAttempts() {
        val state = newBackoff(Duration.ofMillis(10), Duration.ofSeconds(1), 1)
        nextDelay(state) { 0.0 }
        assertNull(nextDelay(state) { 0.0 })
        resetBackoff(state)
        assertNotNull(nextDelay(state) { 0.0 })
    }

    @Test
    fun capsAtMax() {
        val state = newBackoff(Duration.ofMillis(100), Duration.ofMillis(200), 0)
        state.attempt = 10
        val d = nextDelay(state) { 0.999 }!!
        assertTrue(d.toMillis() <= 200)
        assertEquals(11, state.attempt)
    }
}
