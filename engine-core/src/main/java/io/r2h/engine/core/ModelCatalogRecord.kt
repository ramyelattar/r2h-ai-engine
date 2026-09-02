package io.r2h.engine.core

import io.r2h.engine.api.model.EngineError
import io.r2h.engine.api.model.ModelValidationState

data class ModelCatalogRecord(
    val descriptor: ModelDescriptor,
    val fileSizeBytes: Long,
    val installed: Boolean,
    val validationState: ModelValidationState,
    val validationError: EngineError? = null,
)
