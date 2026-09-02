package io.r2h.engine.core

/**
 * Internal result of [AiModelBackend.generate].
 * This type is internal to engine-core and never crosses the IPC boundary.
 * [EngineServiceImpl] translates this into [io.r2h.engine.api.model.GenerateResponse].
 */
internal data class BackendGenerateResult(
    val isSuccess: Boolean,
    val isCancelled: Boolean,
    val isInvalidHandle: Boolean,
    val isOom: Boolean,
    val isMaxTokens: Boolean,
    val promptTokenCount: Int,
    val generatedTokenCount: Int,
    val errorMessage: String? = null,
)

