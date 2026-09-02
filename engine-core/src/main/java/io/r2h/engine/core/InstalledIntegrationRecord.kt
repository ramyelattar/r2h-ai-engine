package io.r2h.engine.core

import io.r2h.engine.api.model.EngineError

data class InstalledIntegrationRecord(
    val displayName: String,
    val packageName: String,
    val installedIntegration: Boolean,
    val trustedIntegration: Boolean,
    val discoverableIntegration: Boolean,
    val lastError: EngineError? = null,
)
