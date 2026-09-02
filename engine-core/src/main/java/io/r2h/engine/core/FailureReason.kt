package io.r2h.engine.core

enum class FailureReason {
    LOAD_FAILED,
    INVALID_MODEL,
    BACKEND_ERROR,
    OUT_OF_MEMORY,
    TIMEOUT,
    UNKNOWN,
}
