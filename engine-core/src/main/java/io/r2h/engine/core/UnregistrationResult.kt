package io.r2h.engine.core

sealed interface UnregistrationResult {
    data object Success : UnregistrationResult
    data object NotFound : UnregistrationResult
    data class Rejected(val reason: String) : UnregistrationResult
}
