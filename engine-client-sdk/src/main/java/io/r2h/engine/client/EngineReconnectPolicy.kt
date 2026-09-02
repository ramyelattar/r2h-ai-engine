package io.r2h.engine.client

internal class EngineReconnectPolicy(
    private val maxAttempts: Int,
    private val initialDelayMs: Long,
) {
    private var attempts: Int = 0

    val attemptCount: Int
        get() = attempts

    fun reset() {
        attempts = 0
    }

    fun nextDelayMs(): Long? {
        if (attempts >= maxAttempts) return null
        val delay = initialDelayMs * (1L shl attempts.coerceAtMost(4))
        attempts += 1
        return delay
    }
}
