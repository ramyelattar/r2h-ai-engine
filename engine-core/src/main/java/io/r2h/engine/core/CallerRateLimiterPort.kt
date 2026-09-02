package io.r2h.engine.core

fun interface CallerRateLimiterPort {
    fun tryAcquire(callerUid: Int, cost: Int): CallerRateLimitResult
}

sealed interface CallerRateLimitResult {
    data object Allowed : CallerRateLimitResult
    data class Denied(val retryAfterMs: Long) : CallerRateLimitResult
}
