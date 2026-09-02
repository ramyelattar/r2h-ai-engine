package io.r2h.engine.core

/**
 * Port interface for resolving a modelId to a validated absolute file path.
 * Implemented by `:model-manager` and injected by `:app`.
 *
 * The implementation must perform SHA-256 validation before returning a path,
 * and must return null if the model is not found or fails validation.
 */
fun interface ModelResolverPort {
    fun resolveValidatedPath(modelId: String): String?
}
