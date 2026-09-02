package io.r2h.engine.nativebridge

data class LlamaCppRuntimeAdapter(
    val backendKey: String = "llama-cpp",
    val libraryName: String = "r2h_native",
    val supportsGguf: Boolean = true,
    val supportsMmproj: Boolean = false,
)
