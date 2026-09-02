package io.r2h.engine.core

/**
 * Internal state of [InferenceOrchestrator].
 *
 * Mapped to the public [io.r2h.engine.api.model.EngineStateCode] in
 * [EngineServiceImpl.getEngineStatus] before crossing the IPC boundary.
 */
internal sealed interface OrchestratorState {

    /** A model is loaded and the queue is idle. */
    data object Idle : OrchestratorState

    /** Actively running the native inference loop for one request. */
    data class Generating(val requestId: String) : OrchestratorState

    /** A model load or hot-swap is in progress. */
    data class LoadingModel(val modelId: String) : OrchestratorState

    /** No model loaded or engine not yet initialised. */
    data object Unavailable : OrchestratorState
}
