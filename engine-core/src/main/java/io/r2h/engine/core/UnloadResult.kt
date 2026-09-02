package io.r2h.engine.core

sealed interface UnloadResult {
    data object Success : UnloadResult
    data class Failure(val reason: FailureReason, val message: String) : UnloadResult
}
