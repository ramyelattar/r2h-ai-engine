package io.r2h.engine.core

sealed interface ModelCapability {
    data object TEXT_GENERATION : ModelCapability
    sealed interface Input : ModelCapability {
        data object Text : Input
        data object Image : Input
        data object Audio : Input
        data object Video : Input
    }
    sealed interface Output : ModelCapability {
        data object Text : Output
        data object Image : Output
        data object Audio : Output
        data object Labels : Output
        data object Embedding : Output
    }
    sealed interface Execution : ModelCapability {
        data object Local : Execution
        data object Cpu : Execution
        data object Remote : Execution
        data object Hybrid : Execution
    }
    sealed interface Lifecycle : ModelCapability {
        data object ExplicitLoad : Lifecycle
        data object ExplicitUnload : Lifecycle
        data object AutoLoad : Lifecycle
        data object AutoUnload : Lifecycle
    }
    sealed interface Interaction : ModelCapability {
        data object Streaming : Interaction
        data object Cancellation : Interaction
        data object SystemInstruction : Interaction
        data object FunctionCalling : Interaction
    }
}
