package io.r2h.engine.nativebridge

data class LiteRtMultimodalRuntime(
    val backendKey: String = "litert-multimodal",
    val capabilityState: String = "MODEL_MISSING",
    val reason: String = "Multimodal/mmproj model path is not provisioned or validated.",
)
