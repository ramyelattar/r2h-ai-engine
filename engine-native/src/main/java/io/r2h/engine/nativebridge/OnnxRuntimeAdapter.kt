package io.r2h.engine.nativebridge

data class OnnxRuntimeAdapter(
    val backendKey: String = "onnx",
    val capabilityState: String = "RUNTIME_MISSING",
    val reason: String = "Android ONNX Runtime dependency and model descriptors are not active in the production source set.",
) {
    val available: Boolean = false
}
