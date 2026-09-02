package io.r2h.engine.core

interface TaskRouter {
    fun route(
        input: InferenceInput,
        candidateModels: List<ModelDescriptor>,
        registry: RuntimeRegistry,
    ): RoutingDecision
}
