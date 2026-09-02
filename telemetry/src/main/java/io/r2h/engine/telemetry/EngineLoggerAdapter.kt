package io.r2h.engine.telemetry

/**
 * Convenience wrapper that adapts [EngineLogger] to the engine-core port interface
 * without creating a compile-time dependency between `:telemetry` and `:engine-core`.
 *
 * Usage in `:app`:
 * ```kotlin
 * val logger = EngineLogger(context, scope)
 * val adapter = EngineLoggerAdapter(logger)
 * // Pass adapter to EngineServiceImpl as InferenceEventSink
 * ```
 *
 * The adapter is intentionally defined here (in `:telemetry`) rather than in `:engine-core`
 * to keep the dependency direction correct: telemetry does not import engine-core.
 * The `:app` module, which imports both, bridges them.
 */
class EngineLoggerAdapter(private val logger: EngineLogger) {

    fun onQueueFull(callerUid: Int) =
        logger.log(TelemetryEvent.QueueFull(callerUid))

    fun onSecurityRejection(callerUid: Int, reason: String) =
        logger.log(TelemetryEvent.SecurityRejection(callerUid, reason))

    fun onModelLoaded(modelId: String, durationMs: Long) =
        logger.log(TelemetryEvent.ModelLoaded(modelId, durationMs))

    fun onModelLoadFailed(modelId: String, reason: String) =
        logger.log(TelemetryEvent.ModelLoadFailed(modelId, reason))

    fun onInferenceCompleted(
        modelId: String,
        callerUid: Int,
        promptTokenCount: Int,
        generatedTokenCount: Int,
        firstTokenMs: Long,
        totalMs: Long,
        finishReason: String,
    ) = logger.log(
        TelemetryEvent.InferenceCompleted(
            modelId             = modelId,
            callerUid           = callerUid,
            promptTokenCount    = promptTokenCount,
            generatedTokenCount = generatedTokenCount,
            firstTokenMs        = firstTokenMs,
            totalMs             = totalMs,
            finishReason        = finishReason,
        )
    )
}
