package io.r2h.engine.core

sealed interface CancellationResult {
    data object Cancelled : CancellationResult
    data object RequestNotFound : CancellationResult
    data class Failure(val message: String) : CancellationResult
}
