package io.r2h.engine.core

data class RuntimeDescriptor(
    val key: String,
    val displayName: String,
    val supportedModelTypes: Set<ModelType>,
    val supportedCapabilities: Set<ModelCapability>,
    val supportedLocalities: Set<ExecutionLocality>,
)
