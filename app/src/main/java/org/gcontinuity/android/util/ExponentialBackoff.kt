package org.gcontinuity.android.util

import kotlin.math.min
import kotlin.math.pow

/**
 * Computes exponentially increasing delays for connection retry attempts.
 *
 * Thread-safe for single-threaded coroutine use; if called from multiple
 * coroutines concurrently, wrap in a mutex. Resets to [initialDelayMs] on [reset].
 *
 * @param initialDelayMs Delay after the first failure (default 1 s).
 * @param maxDelayMs     Upper ceiling for any single delay (default 30 s).
 * @param factor         Exponential multiplier per attempt (default 2.0).
 * @param jitterMs       Maximum random jitter added to avoid thundering-herd (default 500 ms).
 */
class ExponentialBackoff(
    private val initialDelayMs: Long = 1_000L,
    private val maxDelayMs: Long     = 30_000L,
    private val factor: Double       = 2.0,
    private val jitterMs: Long       = 500L,
) {
    private var attempt = 0

    /**
     * Returns the next retry delay in milliseconds and increments the internal
     * attempt counter. The delay is [initialDelayMs] × [factor]^attempt, capped
     * at [maxDelayMs], plus a random jitter in [0, [jitterMs]).
     */
    fun nextDelayMs(): Long {
        val base = (initialDelayMs * factor.pow(attempt.toDouble())).toLong()
        val capped = min(base, maxDelayMs)
        val jitter = (Math.random() * jitterMs).toLong()
        attempt++
        return capped + jitter
    }

    /**
     * Resets the attempt counter to zero after a successful connection.
     * The next [nextDelayMs] call will return approximately [initialDelayMs].
     */
    fun reset() {
        attempt = 0
    }

    /** The number of failed attempts since the last [reset]. */
    val currentAttempt: Int get() = attempt
}
