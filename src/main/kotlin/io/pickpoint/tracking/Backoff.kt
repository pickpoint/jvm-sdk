package io.pickpoint.tracking

import java.time.Duration
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.random.Random

data class BackoffState(
    var attempt: Int = 0,
    val minDelay: Duration = Duration.ofMillis(500),
    val maxDelay: Duration = Duration.ofSeconds(30),
    val maxAttempts: Int = 0,
)

fun newBackoff(minDelay: Duration, maxDelay: Duration, maxAttempts: Int): BackoffState {
    val min = if (minDelay.isZero || minDelay.isNegative) Duration.ofMillis(500) else minDelay
    val max = if (maxDelay.isZero || maxDelay.isNegative) Duration.ofSeconds(30) else maxDelay
    return BackoffState(minDelay = min, maxDelay = max, maxAttempts = maxAttempts)
}

/** Full-jitter exponential delay, or null when attempts are exhausted. */
fun nextDelay(state: BackoffState, random: () -> Double = { Random.nextDouble() }): Duration? {
    if (state.maxAttempts > 0 && state.attempt >= state.maxAttempts) return null
    val minMs = state.minDelay.toMillis().toDouble()
    val maxMs = state.maxDelay.toMillis().toDouble()
    var exp = minMs * 2.0.pow(state.attempt.toDouble())
    exp = min(exp, maxMs)
    state.attempt++
    return Duration.ofMillis(floor(random() * exp).toLong())
}

fun resetBackoff(state: BackoffState) {
    state.attempt = 0
}
