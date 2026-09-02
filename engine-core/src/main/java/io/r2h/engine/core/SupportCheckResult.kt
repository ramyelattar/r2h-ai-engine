package io.r2h.engine.core

sealed interface SupportCheckResult {
    data object Supported : SupportCheckResult
    data object RuntimeNotFound : SupportCheckResult
    data class Unsupported(val reason: String) : SupportCheckResult
    data class PartiallySupported(val missingCapabilities: Set<ModelCapability>) : SupportCheckResult
}
