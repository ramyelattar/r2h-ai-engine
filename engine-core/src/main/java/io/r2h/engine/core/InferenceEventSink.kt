package io.r2h.engine.core

/**
 * Port interface for structured telemetry events emitted by engine-core.
 * Implemented by `:telemetry`'s [io.r2h.engine.telemetry.EngineLogger] and
 * injected by `:app`. A no-op implementation is used when telemetry is disabled.
 *
 * All methods must return immediately and must never throw. Implementations
 * must dispatch any I/O to a background thread internally.
 *
 * Content restrictions that apply to every implementation:
 *  - No prompt text, response text, or user-generated content in any field.
 *  - No PII beyond the app-level caller UID.
 */
interface InferenceEventSink {

    fun onQueueFull(callerUid: Int)

    fun onSecurityRejection(callerUid: Int, reason: String)

    fun onModelLoaded(modelId: String, durationMs: Long)

    fun onModelLoadFailed(modelId: String, reason: String)

    fun onInferenceCompleted(
        modelId: String,
        callerUid: Int,
        promptTokenCount: Int,
        generatedTokenCount: Int,
        firstTokenMs: Long,
        totalMs: Long,
        finishReason: String,
    )

    /** No-op implementation used in tests and when telemetry is disabled. */
    object NoOp : InferenceEventSink {
        override fun onQueueFull(callerUid: Int) = Unit
        override fun onSecurityRejection(callerUid: Int, reason: String) = Unit
        override fun onModelLoaded(modelId: String, durationMs: Long) = Unit
        override fun onModelLoadFailed(modelId: String, reason: String) = Unit
        override fun onInferenceCompleted(modelId: String, callerUid: Int, promptTokenCount: Int,
            generatedTokenCount: Int, firstTokenMs: Long, totalMs: Long, finishReason: String) = Unit
    }
}
