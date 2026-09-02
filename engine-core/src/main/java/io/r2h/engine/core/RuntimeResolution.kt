package io.r2h.engine.core

sealed interface RuntimeResolution {
    data object RegistryEmpty : RuntimeResolution

    data class Resolved(
        val runtime: BackendRuntime,
        val modelDescriptor: ModelDescriptor,
    ) : RuntimeResolution

    data class NoneFound(val reason: String) : RuntimeResolution
}
