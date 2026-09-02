package io.r2h.engine.nativebridge

data class LiteRtTextRuntime(
    val backendKey: String = "litert-text",
    val capabilityState: String = "UNAVAILABLE",
    val reason: String = "LiteRT text runtime scaffold is preserved but not wired to an active runtime.",
)
