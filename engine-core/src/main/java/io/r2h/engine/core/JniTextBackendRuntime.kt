package io.r2h.engine.core

import io.r2h.engine.nativebridge.NativeErrorCode
import io.r2h.engine.nativebridge.NativeFinishCode
import kotlinx.coroutines.flow.flow
import java.io.File

class JniTextBackendRuntime(
    private val defaultMaxContextWindow: Int = 512,
    private val defaultThreads: Int = 2,
) : BackendRuntime {
    private val nativeRuntime: NativeRuntimePort = JniNativeRuntimePort
    override val descriptor: RuntimeDescriptor = RuntimeDescriptor(
        key = "llama-cpp",
        displayName = "llama.cpp JNI",
        supportedModelTypes = setOf(ModelType.GGUF, ModelType.TEXT_GENERATION),
        supportedCapabilities = setOf(
            ModelCapability.TEXT_GENERATION,
            ModelCapability.Input.Text,
            ModelCapability.Output.Text,
            ModelCapability.Execution.Local,
            ModelCapability.Execution.Cpu,
            ModelCapability.Lifecycle.ExplicitLoad,
            ModelCapability.Lifecycle.ExplicitUnload,
            ModelCapability.Interaction.Cancellation,
            ModelCapability.Interaction.Streaming,
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
        val required = setOf(ModelCapability.Input.Text, ModelCapability.Output.Text, ModelCapability.Execution.Local)
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
        return CompatibilityResult.Compatible("Local GGUF text model is loadable by llama.cpp JNI.")
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
            nativeRuntime.createContext(
                modelPath = local.artifactRef,
                maxContextLength = model.constraints.maxContextWindow.takeIf { it > 0 } ?: defaultMaxContextWindow,
                threads = defaultThreads,
            )
        } catch (t: Throwable) {
            return fail(
                model.id,
                FailureReason.BACKEND_ERROR,
                "Native context creation failed: ${t.message ?: t.javaClass.simpleName}",
            )
        }

        if (contextHandle == 0L) {
            return fail(model.id, FailureReason.BACKEND_ERROR, "Native context creation returned 0.")
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
        runtimeState = RuntimeState(RuntimeState.Lifecycle.BUSY, activeRequestCount = 1)
        val output = StringBuilder()
        val prompt = input.parts.filterIsInstance<InferenceInput.Part.Text>()
            .joinToString("\n") { it.content }
        val started = System.currentTimeMillis()
        try {
            val result = nativeRuntime.generate(
                contextHandle = handle,
                prompt = prompt,
                maxTokens = input.metadata["engine.maxTokens"]?.toIntOrNull()?.coerceAtLeast(1) ?: 128,
                temperature = input.metadata["engine.temperature"]?.toFloatOrNull()?.coerceIn(0f, 2f) ?: 0.7f,
                tokenCallback = { token -> output.append(token) },
            )
            if (result.errorCode != NativeErrorCode.SUCCESS && result.errorCode != NativeErrorCode.CANCELLED) {
                throw IllegalStateException("Native generation failed with errorCode=${result.errorCode}.")
            }
            emit(
                InferenceOutput(
                    requestId = input.requestId,
                    phase = InferenceOutput.Phase.Final,
                    items = listOf(InferenceOutput.Item.Text(output.toString())),
                    completion = InferenceOutput.CompletionStatus.Terminal(
                        when (result.finishReasonCode) {
                            NativeFinishCode.CANCELLED -> InferenceOutput.FinishReason.CANCELLED
                            NativeFinishCode.MAX_TOKENS -> InferenceOutput.FinishReason.LENGTH
                            else -> InferenceOutput.FinishReason.COMPLETED
                        },
                    ),
                    latencyMs = System.currentTimeMillis() - started,
                ),
            )
        } finally {
            runtimeState = RuntimeState(RuntimeState.Lifecycle.READY)
        }
    }

    override suspend fun cancel(requestId: String): CancellationResult {
        val handle = lastContextHandle
        if (handle == 0L) return CancellationResult.RequestNotFound
        nativeRuntime.cancel(handle)
        return CancellationResult.Cancelled
    }

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

    fun loadedContextHandle(): Long = lastContextHandle

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
