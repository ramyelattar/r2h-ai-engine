package io.r2h.engine.core

import io.r2h.engine.nativebridge.RuntimeProbeStatus
import io.r2h.engine.nativebridge.WhisperCppRuntimeAdapter
import kotlinx.coroutines.flow.flow
import java.io.File

private const val STT_BACKEND_KEY = "whisper-cpp"

class WhisperCppSttBackendRuntime(
    private val adapter: WhisperCppRuntimeAdapter = WhisperCppRuntimeAdapter(),
) : BackendRuntime {
    override val descriptor: RuntimeDescriptor = RuntimeDescriptor(
        key = STT_BACKEND_KEY,
        displayName = "whisper.cpp STT",
        supportedModelTypes = setOf(ModelType.SPEECH_TO_TEXT),
        supportedCapabilities = setOf(
            ModelCapability.Input.Audio,
            ModelCapability.Output.Text,
            ModelCapability.Execution.Local,
            ModelCapability.Execution.Cpu,
            ModelCapability.Lifecycle.ExplicitLoad,
            ModelCapability.Lifecycle.ExplicitUnload,
        ),
        supportedLocalities = setOf(ExecutionLocality.LOCAL),
    )

    @Volatile private var loadedModelId: String? = null
    @Volatile private var modelFile: File? = null
    @Volatile private var loadState: LoadState = LoadState.NotLoaded
    @Volatile private var runtimeState: RuntimeState = RuntimeState(RuntimeState.Lifecycle.IDLE)

    override fun assessCompatibility(model: ModelDescriptor): CompatibilityResult {
        if (model.backendKey != descriptor.key) {
            return CompatibilityResult.Incompatible(CompatibilityResult.Reason.BackendKeyMismatch)
        }
        if (model.modelType != ModelType.SPEECH_TO_TEXT) {
            return CompatibilityResult.Incompatible(CompatibilityResult.Reason.ModelTypeNotSupported)
        }
        val local = model.source as? ModelDescriptor.Source.Local
            ?: return CompatibilityResult.Incompatible(CompatibilityResult.Reason.LocalityNotSupported)
        if (!File(local.artifactRef).isFile || !local.artifactRef.endsWith(".bin", ignoreCase = true)) {
            return CompatibilityResult.Incompatible(CompatibilityResult.Reason.ModelTypeNotSupported)
        }
        val required = setOf(ModelCapability.Input.Audio, ModelCapability.Output.Text, ModelCapability.Execution.Local)
        val missing = required - model.capabilities
        if (missing.isNotEmpty()) {
            return CompatibilityResult.Incompatible(CompatibilityResult.Reason.CapabilityNotSupported)
        }
        return CompatibilityResult.Compatible("Local whisper.cpp ggml model is present.")
    }

    override fun getLoadState(modelId: String): LoadState =
        if (modelId == loadedModelId) loadState else LoadState.NotLoaded

    override suspend fun loadModel(model: ModelDescriptor): LoadResult {
        runtimeState = RuntimeState(RuntimeState.Lifecycle.LOADING)
        val compatibility = assessCompatibility(model)
        if (compatibility !is CompatibilityResult.Compatible) {
            return fail(model.id, FailureReason.INVALID_MODEL, "Model is not compatible with ${descriptor.key}.")
        }
        val file = File((model.source as ModelDescriptor.Source.Local).artifactRef)
        loadedModelId = model.id
        modelFile = file
        loadState = LoadState.Loaded(System.currentTimeMillis())
        runtimeState = RuntimeState(RuntimeState.Lifecycle.READY)
        return LoadResult.Success(loadState as LoadState.Loaded)
    }

    override fun execute(model: ModelDescriptor, input: InferenceInput) = flow {
        val file = modelFile ?: throw IllegalStateException("STT model ${model.id} is not loaded.")
        if (model.id != loadedModelId) {
            throw IllegalStateException("STT model ${model.id} is not loaded in this runtime.")
        }
        if (input.task != InferenceInput.Task.SpeechToText) {
            throw IllegalArgumentException("whisper.cpp runtime only supports SPEECH_TO_TEXT.")
        }
        val audio = input.parts.filterIsInstance<InferenceInput.Part.Audio>().firstOrNull()
            ?: throw IllegalArgumentException("SPEECH_TO_TEXT requires an audio input reference.")
        val wav = File(audio.artifact.ref)
        require(wav.isFile) { "Audio input does not exist: ${wav.absolutePath}" }

        val started = System.currentTimeMillis()
        runtimeState = RuntimeState(RuntimeState.Lifecycle.BUSY, activeRequestCount = 1)
        try {
            val result = adapter.runSmoke(file, wav)
            if (result.status != RuntimeProbeStatus.AVAILABLE) {
                error("${result.errorCode ?: "WHISPER_TRANSCRIBE_FAILED"}: ${result.errorMessage ?: result.outputPreview}")
            }
            val transcript = result.outputPreview.trim()
            val summary = buildString {
                appendLine("STT proof result")
                appendLine("finish=COMPLETED")
                appendLine("modelId=${model.id}")
                appendLine("modelPath=${file.absolutePath}")
                appendLine("audioPath=${wav.absolutePath}")
                appendLine("transcript=$transcript")
                append("runtime=whisper.cpp")
            }
            emit(
                InferenceOutput(
                    requestId = input.requestId,
                    phase = InferenceOutput.Phase.Final,
                    items = listOf(InferenceOutput.Item.Text(summary)),
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
        if (modelId == loadedModelId) {
            loadedModelId = null
            modelFile = null
            loadState = LoadState.NotLoaded
            runtimeState = RuntimeState(RuntimeState.Lifecycle.IDLE)
        }
        return UnloadResult.Success
    }

    override fun getRuntimeState(): RuntimeState = runtimeState

    private fun fail(modelId: String, reason: FailureReason, message: String): LoadResult.Failure {
        loadedModelId = modelId
        modelFile = null
        loadState = LoadState.Failed(reason, message)
        runtimeState = RuntimeState(
            RuntimeState.Lifecycle.ERROR,
            lastFailure = RuntimeState.RuntimeFailure(reason, message),
        )
        return LoadResult.Failure(reason, message)
    }
}
