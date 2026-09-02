package io.r2h.engine.core

sealed interface CallerValidationResult {
    data class Allowed(val packageName: String) : CallerValidationResult
    data class Denied(val reason: String) : CallerValidationResult
}
