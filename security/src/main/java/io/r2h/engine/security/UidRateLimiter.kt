package io.r2h.engine.security

import android.os.SystemClock
import kotlin.math.ceil
import kotlin.math.min

fun interface MonotonicClock {
    fun nowMs(): Long
}

sealed interface UidRateLimitDecision {
    data object Allowed : UidRateLimitDecision
    data class Denied(val retryAfterMs: Long) : UidRateLimitDecision
}

/**
 * Thread-safe, bounded token bucket keyed by Binder caller UID.
 *
 * Time is based on elapsed realtime, so wall-clock changes cannot refill a bucket.
 * Stale entries are removed during normal calls and the least-recently-seen entry
 * is evicted if the fixed UID bound is reached.
 */
class UidRateLimiter(
    private val capacity: Double,
    private val refillTokensPerSecond: Double,
    private val maxTrackedUids: Int,
    private val staleAfterMs: Long,
    private val clock: MonotonicClock = MonotonicClock(SystemClock::elapsedRealtime),
) {
    private data class Bucket(
        var tokens: Double,
        var lastRefillMs: Long,
        var lastSeenMs: Long,
    )

    private val lock = Any()
    private val buckets = mutableMapOf<Int, Bucket>()

    init {
        require(capacity > 0.0 && capacity.isFinite())
        require(refillTokensPerSecond > 0.0 && refillTokensPerSecond.isFinite())
        require(maxTrackedUids > 0)
        require(staleAfterMs > 0L)
    }

    fun tryAcquire(callerUid: Int, cost: Double): UidRateLimitDecision {
        require(callerUid >= 0)
        require(cost > 0.0 && cost.isFinite() && cost <= capacity)
        val nowMs = clock.nowMs().coerceAtLeast(0L)

        return synchronized(lock) {
            removeStaleBuckets(nowMs)
            val bucket = buckets[callerUid] ?: newBucket(callerUid, nowMs)
            refill(bucket, nowMs)
            bucket.lastSeenMs = maxOf(bucket.lastSeenMs, nowMs)

            if (bucket.tokens >= cost) {
                bucket.tokens -= cost
                UidRateLimitDecision.Allowed
            } else {
                val missingTokens = cost - bucket.tokens
                val retryAfterMs = ceil(missingTokens / refillTokensPerSecond * 1_000.0)
                    .toLong()
                    .coerceAtLeast(1L)
                UidRateLimitDecision.Denied(retryAfterMs)
            }
        }
    }

    internal fun trackedUidCountForTest(): Int = synchronized(lock) { buckets.size }

    private fun newBucket(callerUid: Int, nowMs: Long): Bucket {
        if (buckets.size >= maxTrackedUids) {
            buckets.minByOrNull { it.value.lastSeenMs }?.key?.let(buckets::remove)
        }
        return Bucket(capacity, nowMs, nowMs).also { buckets[callerUid] = it }
    }

    private fun refill(bucket: Bucket, nowMs: Long) {
        val elapsedMs = (nowMs - bucket.lastRefillMs).coerceAtLeast(0L)
        if (elapsedMs > 0L) {
            bucket.tokens = min(
                capacity,
                bucket.tokens + (elapsedMs.toDouble() / 1_000.0 * refillTokensPerSecond),
            )
            bucket.lastRefillMs = nowMs
        }
    }

    private fun removeStaleBuckets(nowMs: Long) {
        buckets.entries.removeAll { (_, bucket) ->
            nowMs >= bucket.lastSeenMs && nowMs - bucket.lastSeenMs > staleAfterMs
        }
    }
}
