package io.r2h.engine.core

import kotlinx.coroutines.flow.Flow

interface BackendRuntime {
    val descriptor: RuntimeDescriptor

    fun assessCompatibility(model: ModelDescriptor): CompatibilityResult

    fun getLoadState(modelId: String): LoadState

    suspend fun loadModel(model: ModelDescriptor): LoadResult

    fun execute(model: ModelDescriptor, input: InferenceInput): Flow<InferenceOutput>

    suspend fun cancel(requestId: String): CancellationResult

    suspend fun unloadModel(modelId: String): UnloadResult

    fun getRuntimeState(): RuntimeState
}
