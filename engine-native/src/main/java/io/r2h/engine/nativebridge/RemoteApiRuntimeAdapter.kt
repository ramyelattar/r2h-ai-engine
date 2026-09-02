package io.r2h.engine.nativebridge

data class RemoteApiRuntimeAdapter(
    val backendKey: String = "remote-api",
    val capabilityState: String = "UNAVAILABLE",
    val reason: String = "Remote API execution is disabled for the offline/local-only engine release posture.",
)
