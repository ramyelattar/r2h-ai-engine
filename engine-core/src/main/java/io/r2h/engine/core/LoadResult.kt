package io.r2h.engine.core

sealed interface LoadResult {
    data class Success(val loadedState: LoadState.Loaded) : LoadResult
    data class Failure(val reason: FailureReason, val message: String) : LoadResult
}
