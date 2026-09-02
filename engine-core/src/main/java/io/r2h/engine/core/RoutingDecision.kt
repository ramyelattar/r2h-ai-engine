package io.r2h.engine.core

sealed interface RoutingDecision {
    data class Routed(
        val runtimeKey: String,
        val modelId: String,
        val locality: ExecutionLocality,
    ) : RoutingDecision

    data class Rejected(val reason: Reason) : RoutingDecision

    enum class Reason {
        NO_CANDIDATE_MODELS,
        NO_COMPATIBLE_RUNTIME,
        CAPABILITY_MISMATCH,
        LOCALITY_NOT_AVAILABLE,
    }
}
