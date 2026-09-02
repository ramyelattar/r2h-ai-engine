package io.r2h.engine.core

data class RuntimeState(
    val lifecycle: Lifecycle,
    val activeRequestCount: Int = 0,
    val maxConcurrency: Int = 1,
    val lastFailure: RuntimeFailure? = null,
) {
    enum class Lifecycle { IDLE, LOADING, READY, BUSY, ERROR, UNLOADING }

    data class RuntimeFailure(
        val reason: FailureReason,
        val message: String? = null,
    )
}
