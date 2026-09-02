package io.r2h.engine.ui.dashboard

data class ModelCatalogItem(
    val modelId: String,
    val displayName: String,
    val validationState: String,
    val capabilityState: String,
)
