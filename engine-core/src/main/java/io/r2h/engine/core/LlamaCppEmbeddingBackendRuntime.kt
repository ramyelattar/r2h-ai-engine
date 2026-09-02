package io.r2h.engine.core

import kotlinx.coroutines.flow.flow
import java.io.File
import kotlin.math.sqrt

class LlamaCppEmbeddingBackendRuntime(
    private val defaultMaxContextWindow: Int = 512,
    private val defaultThreads: Int = 2,
) : BackendRuntime {
    private val nativeRuntime: NativeRuntimePort = JniNativeRuntimePort
    override val descriptor: RuntimeDescriptor = RuntimeDescriptor(
        key = "llama-cpp-embedding",
        displayName = "llama.cpp Qwen3 Embeddings JNI",
        supportedModelTypes = setOf(ModelType.EMBEDDING, ModelType.GGUF),
        supportedCapabilities = setOf(
            ModelCapability.Input.Text,
            ModelCapability.Output.Embedding,
            ModelCapability.Execution.Local,
            ModelCapability.Execution.Cpu,
            ModelCapability.Lifecycle.ExplicitLoad,
            ModelCapability.Lifecycle.ExplicitUnload,
        ),
        supportedLocalities = setOf(ExecutionLocality.LOCAL),
    )

    @Volatile private var loadedModelId: String? = null
    @Volatile private var loadState: LoadState = LoadState.NotLoaded
    @Volatile private var runtimeState: RuntimeState = RuntimeState(RuntimeState.Lifecycle.IDLE)
    @Volatile private var lastContextHandle: Long = 0L

    override fun assessCompatibility(model: ModelDescriptor): CompatibilityResult {
        if (model.backendKey != descriptor.key) {
            return CompatibilityResult.Incompatible(CompatibilityResult.Reason.BackendKeyMismatch)
        }
        if (model.modelType !in descriptor.supportedModelTypes) {
            return CompatibilityResult.Incompatible(CompatibilityResult.Reason.ModelTypeNotSupported)
        }
        val required = setOf(ModelCapability.Input.Text, ModelCapability.Output.Embedding, ModelCapability.Execution.Local)
        val missing = required - model.capabilities
        if (missing.isNotEmpty()) {
            return CompatibilityResult.Incompatible(CompatibilityResult.Reason.CapabilityNotSupported)
        }
        val local = model.source as? ModelDescriptor.Source.Local
            ?: return CompatibilityResult.Incompatible(CompatibilityResult.Reason.LocalityNotSupported)
        val modelFile = File(local.artifactRef)
        if (!modelFile.isFile || !local.format.equals("gguf", ignoreCase = true)) {
            return CompatibilityResult.Incompatible(CompatibilityResult.Reason.ModelTypeNotSupported)
        }
        return CompatibilityResult.Compatible("Local Qwen3 GGUF embedding model is loadable by llama.cpp JNI.")
    }

    override fun getLoadState(modelId: String): LoadState =
        if (modelId == loadedModelId) loadState else LoadState.NotLoaded

    override suspend fun loadModel(model: ModelDescriptor): LoadResult {
        runtimeState = RuntimeState(RuntimeState.Lifecycle.LOADING)
        val compatibility = assessCompatibility(model)
        if (compatibility !is CompatibilityResult.Compatible) {
            return fail(model.id, FailureReason.INVALID_MODEL, "Model is not compatible with ${descriptor.key}.")
        }
        val local = model.source as ModelDescriptor.Source.Local
        val contextHandle = try {
            nativeRuntime.createEmbeddingContext(
                modelPath = local.artifactRef,
                maxContextLength = model.constraints.maxContextWindow.takeIf { it > 0 } ?: defaultMaxContextWindow,
                threads = defaultThreads,
            )
        } catch (t: Throwable) {
            return fail(model.id, FailureReason.BACKEND_ERROR, "Native embedding context creation failed: ${t.message ?: t.javaClass.simpleName}")
        }
        if (contextHandle == 0L) {
            return fail(model.id, FailureReason.BACKEND_ERROR, "Native embedding context creation returned 0.")
        }
        if (lastContextHandle != 0L) {
            runCatching { nativeRuntime.destroyContext(lastContextHandle) }
        }
        lastContextHandle = contextHandle
        loadedModelId = model.id
        loadState = LoadState.Loaded(System.currentTimeMillis())
        runtimeState = RuntimeState(RuntimeState.Lifecycle.READY)
        return LoadResult.Success(loadState as LoadState.Loaded)
    }

    override fun execute(model: ModelDescriptor, input: InferenceInput) = flow {
        val handle = lastContextHandle
        if (handle == 0L || loadedModelId != model.id) {
            throw IllegalStateException("Model ${model.id} is not loaded.")
        }
        if (input.task != InferenceInput.Task.Embedding) {
            throw IllegalArgumentException("Unsupported task for ${descriptor.key}: ${input.task}")
        }
        val text = input.parts.filterIsInstance<InferenceInput.Part.Text>()
            .joinToString("\n") { it.content }
            .trim()
        require(text.isNotBlank()) { "Embedding input text is empty." }
        runtimeState = RuntimeState(RuntimeState.Lifecycle.BUSY, activeRequestCount = 1)
        val started = System.currentTimeMillis()
        try {
            val raw = nativeRuntime.embedText(handle, text)
                ?: throw IllegalStateException("Native embedding returned null.")
            val normalized = normalize(raw)
            emit(
                InferenceOutput(
                    requestId = input.requestId,
                    phase = InferenceOutput.Phase.Final,
                    items = listOf(InferenceOutput.Item.Embedding(normalized)),
                    completion = InferenceOutput.CompletionStatus.Terminal(InferenceOutput.FinishReason.COMPLETED),
                    latencyMs = System.currentTimeMillis() - started,
                ),
            )
        } finally {
            runtimeState = RuntimeState(RuntimeState.Lifecycle.READY)
        }
    }

    override suspend fun cancel(requestId: String): CancellationResult = CancellationResult.RequestNotFound

    override suspend fun unloadModel(modelId: String): UnloadResult {
        if (modelId != loadedModelId) return UnloadResult.Success
        runtimeState = RuntimeState(RuntimeState.Lifecycle.UNLOADING)
        val handle = lastContextHandle
        if (handle != 0L) {
            runCatching { nativeRuntime.destroyContext(handle) }
        }
        lastContextHandle = 0L
        loadedModelId = null
        loadState = LoadState.NotLoaded
        runtimeState = RuntimeState(RuntimeState.Lifecycle.IDLE)
        return UnloadResult.Success
    }

    override fun getRuntimeState(): RuntimeState = runtimeState

    private fun normalize(values: FloatArray): FloatArray {
        var sum = 0.0
        for (value in values) {
            sum += (value * value).toDouble()
        }
        val norm = sqrt(sum).toFloat()
        if (norm <= 0f) return values
        return FloatArray(values.size) { index -> values[index] / norm }
    }

    private fun fail(modelId: String, reason: FailureReason, message: String): LoadResult.Failure {
        loadedModelId = modelId
        loadState = LoadState.Failed(reason, message)
        runtimeState = RuntimeState(
            RuntimeState.Lifecycle.ERROR,
            lastFailure = RuntimeState.RuntimeFailure(reason, message),
        )
        return LoadResult.Failure(reason, message)
    }
}
