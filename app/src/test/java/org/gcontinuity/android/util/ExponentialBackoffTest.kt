package org.gcontinuity.android.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [ExponentialBackoff].
 * All timing assertions use wide tolerances to account for random jitter.
 */
class ExponentialBackoffTest {

    @Test
    fun `first delay is approximately initialDelayMs`() {
        // No jitter in this instance to get deterministic results
        val backoff = ExponentialBackoff(
            initialDelayMs = 1_000L,
            maxDelayMs     = 30_000L,
            factor         = 2.0,
            jitterMs       = 0L,
        )
        val delay = backoff.nextDelayMs()
        // 1000 * 2^0 = 1000
        assertEquals(1_000L, delay)
    }

    @Test
    fun `delays increase exponentially across attempts`() {
        val backoff = ExponentialBackoff(
            initialDelayMs = 1_000L,
            maxDelayMs     = 60_000L,
            factor         = 2.0,
            jitterMs       = 0L,
        )
        val d0 = backoff.nextDelayMs() // 1000
        val d1 = backoff.nextDelayMs() // 2000
        val d2 = backoff.nextDelayMs() // 4000
        val d3 = backoff.nextDelayMs() // 8000

        assertTrue("d1 > d0", d1 > d0)
        assertTrue("d2 > d1", d2 > d1)
        assertTrue("d3 > d2", d3 > d2)
        // Verify exact doubling
        assertEquals(d0 * 2, d1)
        assertEquals(d1 * 2, d2)
        assertEquals(d2 * 2, d3)
    }

    @Test
    fun `delay never exceeds maxDelayMs plus jitter ceiling`() {
        val maxDelay = 30_000L
        val jitter   = 500L
        val backoff = ExponentialBackoff(
            initialDelayMs = 1_000L,
            maxDelayMs     = maxDelay,
            factor         = 2.0,
            jitterMs       = jitter,
        )
        repeat(20) {
            val delay = backoff.nextDelayMs()
            assertTrue(
                "delay $delay must not exceed maxDelay + jitter (${maxDelay + jitter})",
                delay <= maxDelay + jitter
            )
        }
    }

    @Test
    fun `reset restores first delay after multiple attempts`() {
        val backoff = ExponentialBackoff(
            initialDelayMs = 1_000L,
            maxDelayMs     = 30_000L,
            factor         = 2.0,
            jitterMs       = 0L,
        )
        repeat(10) { backoff.nextDelayMs() }
        assertEquals(10, backoff.currentAttempt)

        backoff.reset()

        assertEquals(0, backoff.currentAttempt)
        // First delay after reset should equal initialDelayMs again
        assertEquals(1_000L, backoff.nextDelayMs())
    }

    @Test
    fun `currentAttempt increments after each nextDelayMs call`() {
        val backoff = ExponentialBackoff()
        assertEquals(0, backoff.currentAttempt)
        backoff.nextDelayMs()
        assertEquals(1, backoff.currentAttempt)
        backoff.nextDelayMs()
        assertEquals(2, backoff.currentAttempt)
    }

    @Test
    fun `jitter produces value within expected range`() {
        val maxJitter = 1_000L
        val backoff = ExponentialBackoff(
            initialDelayMs = 0L,
            maxDelayMs     = 60_000L,
            factor         = 1.0,  // constant base so only jitter varies
            jitterMs       = maxJitter,
        )
        repeat(50) {
            backoff.reset()
            val delay = backoff.nextDelayMs()
            assertTrue("jitter $delay must be in [0, maxJitter]", delay in 0L..maxJitter)
        }
    }
}
