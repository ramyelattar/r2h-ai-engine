package io.r2h.engine.security

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UidRateLimiterTest {

    @Test
    fun `initial burst is allowed and the next request is rejected`() {
        val limiter = limiter(capacity = 3.0)

        repeat(3) { assertEquals(UidRateLimitDecision.Allowed, limiter.tryAcquire(1001, 1.0)) }
        assertTrue(limiter.tryAcquire(1001, 1.0) is UidRateLimitDecision.Denied)
    }

    @Test
    fun `tokens refill using monotonic elapsed time`() {
        val clock = MutableMonotonicClock()
        val limiter = limiter(capacity = 2.0, refillTokensPerSecond = 2.0, clock = clock)
        repeat(2) { assertEquals(UidRateLimitDecision.Allowed, limiter.tryAcquire(1001, 1.0)) }
        assertTrue(limiter.tryAcquire(1001, 1.0) is UidRateLimitDecision.Denied)

        clock.advanceBy(500L)

        assertEquals(UidRateLimitDecision.Allowed, limiter.tryAcquire(1001, 1.0))
    }

    @Test
    fun `different UIDs receive isolated buckets`() {
        val limiter = limiter(capacity = 1.0)

        assertEquals(UidRateLimitDecision.Allowed, limiter.tryAcquire(1001, 1.0))
        assertTrue(limiter.tryAcquire(1001, 1.0) is UidRateLimitDecision.Denied)
        assertEquals(UidRateLimitDecision.Allowed, limiter.tryAcquire(2002, 1.0))
    }

    @Test
    fun `stale UID state is removed`() {
        val clock = MutableMonotonicClock()
        val limiter = limiter(staleAfterMs = 1_000L, clock = clock)
        limiter.tryAcquire(1001, 1.0)
        assertEquals(1, limiter.trackedUidCountForTest())

        clock.advanceBy(1_001L)
        limiter.tryAcquire(2002, 1.0)

        assertEquals(1, limiter.trackedUidCountForTest())
    }

    @Test
    fun `tracked UID state never exceeds its fixed bound`() {
        val limiter = limiter(maxTrackedUids = 2)

        limiter.tryAcquire(1001, 1.0)
        limiter.tryAcquire(2002, 1.0)
        limiter.tryAcquire(3003, 1.0)

        assertEquals(2, limiter.trackedUidCountForTest())
    }

    @Test
    fun `concurrent calls cannot consume more than the bucket capacity`() {
        val limiter = limiter(capacity = 10.0)
        val executor = Executors.newFixedThreadPool(50)
        val start = CountDownLatch(1)
        val finished = CountDownLatch(50)
        val allowed = AtomicInteger(0)
        try {
            repeat(50) {
                executor.execute {
                    start.await()
                    if (limiter.tryAcquire(1001, 1.0) == UidRateLimitDecision.Allowed) {
                        allowed.incrementAndGet()
                    }
                    finished.countDown()
                }
            }
            start.countDown()
            assertTrue(finished.await(5, TimeUnit.SECONDS))

            assertEquals(10, allowed.get())
        } finally {
            executor.shutdownNow()
        }
    }

    private fun limiter(
        capacity: Double = 10.0,
        refillTokensPerSecond: Double = 1.0,
        maxTrackedUids: Int = 256,
        staleAfterMs: Long = 15 * 60 * 1_000L,
        clock: MonotonicClock = MutableMonotonicClock(),
    ) = UidRateLimiter(
        capacity = capacity,
        refillTokensPerSecond = refillTokensPerSecond,
        maxTrackedUids = maxTrackedUids,
        staleAfterMs = staleAfterMs,
        clock = clock,
    )

    private class MutableMonotonicClock(
        private var nowMs: Long = 0L,
    ) : MonotonicClock {
        override fun nowMs(): Long = nowMs

        fun advanceBy(deltaMs: Long) {
            nowMs += deltaMs
        }
    }
}
