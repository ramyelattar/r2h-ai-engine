package io.r2h.engine.telemetry

/**
 * Sealed hierarchy of structured events that may be logged to the local telemetry sink.
 *
 * Rules that apply to ALL event types:
 *  - No field may contain prompt text, response text, or any user-generated content.
 *  - No field may contain PII (names, email addresses, device identifiers beyond the
 *    android.os.Process UID, which is an app-level ID, not a user ID).
 *  - All latency values are in milliseconds.
 */
sealed interface TelemetryEvent {

    /**
     * Fired when a model is successfully loaded into the native inference context.
     *
     * @param modelId     Identifier of the model (same as [io.r2h.engine.api.model.ModelInfo.modelId]).
     * @param durationMs  Wall-clock time from createContext call to successful return.
     */
    data class ModelLoaded(
        val modelId: String,
        val durationMs: Long,
    ) : TelemetryEvent

    /**
     * Fired when a model fails to load.
     *
     * @param modelId  Identifier of the model that failed to load.
     * @param reason   Short machine-readable error tag (e.g., "OOM", "FILE_NOT_FOUND").
     *                 Must not contain file paths or user data.
     */
    data class ModelLoadFailed(
        val modelId: String,
        val reason: String,
    ) : TelemetryEvent

    /**
     * Fired when an inference request completes (success, cancel, or error).
     *
     * @param modelId             Model that served the request.
     * @param callerUid           UID of the requesting process (app-level, not user-level).
     * @param promptTokenCount    Tokens evaluated for the prompt.
     * @param generatedTokenCount Tokens produced.
     * @param firstTokenMs        Latency to first token in milliseconds. -1 if not applicable.
     * @param totalMs             Total wall-clock time for the request.
     * @param finishReason        One of: COMPLETE, MAX_TOKENS, CANCELLED, ERROR.
     */
    data class InferenceCompleted(
        val modelId: String,
        val callerUid: Int,
        val promptTokenCount: Int,
        val generatedTokenCount: Int,
        val firstTokenMs: Long,
        val totalMs: Long,
        val finishReason: String,
    ) : TelemetryEvent

    /**
     * Fired when an IPC request is rejected for a security reason.
     *
     * @param callerUid  UID of the rejected caller.
     * @param reason     Machine-readable rejection reason (e.g., "UID_NOT_ALLOWLISTED",
     *                   "SIGNATURE_MISMATCH"). Must not contain caller package name.
     */
    data class SecurityRejection(
        val callerUid: Int,
        val reason: String,
    ) : TelemetryEvent

    /**
     * Fired when a request is rejected because the queue is full.
     *
     * @param callerUid  UID of the caller whose request was rejected.
     */
    data class QueueFull(val callerUid: Int) : TelemetryEvent
}
