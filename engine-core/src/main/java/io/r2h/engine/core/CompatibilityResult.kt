package io.r2h.engine.core

sealed interface CompatibilityResult {
    data class Compatible(val message: String = "") : CompatibilityResult

    data class Incompatible(val reasons: List<Reason>) : CompatibilityResult {
        constructor(reason: Reason) : this(listOf(reason))
    }

    enum class Reason {
        BackendKeyMismatch,
        ModelTypeNotSupported,
        CapabilityNotSupported,
        LocalityNotSupported,
        ContextWindowTooLarge,
    }
}
