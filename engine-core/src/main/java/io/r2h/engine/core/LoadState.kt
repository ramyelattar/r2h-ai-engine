package io.r2h.engine.core

sealed interface LoadState {
    data object NotLoaded : LoadState
    data class Loading(val progressPercent: Int = 0) : LoadState
    data class Loaded(val loadedAtEpochMs: Long) : LoadState
    data class Failed(val reason: FailureReason, val message: String) : LoadState
}
