package io.r2h.engine.core

sealed interface CompatibilityResolution {
    data object RegistryEmpty : CompatibilityResolution

    data class Resolved(
        val compatibleRuntimes: List<BackendRuntime>,
        val primaryRuntime: BackendRuntime,
    ) : CompatibilityResolution

    data class NoneCompatible(val checkedRuntimes: List<String>) : CompatibilityResolution
}
