package io.r2h.engine.nativebridge

enum class RuntimeProbeStatus {
    AVAILABLE,
    SMOKE_FAILED,
    RUNTIME_MISSING,
    MODEL_MISSING,
    NOT_TESTED,
}

data class RuntimeProbeResult(
    val status: RuntimeProbeStatus,
    val backendLabel: String,
    val outputPreview: String,
    val elapsedMs: Long,
    val errorCode: String?,
    val errorMessage: String?,
)
