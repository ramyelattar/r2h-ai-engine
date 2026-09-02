package io.r2h.engine.core

import android.util.Log
import io.r2h.engine.api.privacy.DiagnosticOperation
import io.r2h.engine.api.privacy.DiagnosticStatus
import io.r2h.engine.api.privacy.PrivacySafeDiagnostics
import io.r2h.engine.nativebridge.AudioTaggingSession
import io.r2h.engine.nativebridge.SherpaOnnxAudioTaggingRuntimeAdapter
import kotlinx.coroutines.flow.flow
import java.io.File
import java.util.Locale

private const val TAG = "SherpaAudioTaggingRuntime"
private const val AUDIO_TAGGING_BACKEND_KEY = "sherpa-onnx-audio-tagging"
private const val AUDIO_TAGGING_RUNTIME_LABEL = "Sherpa-ONNX CED audio tagging"

class SherpaOnnxAudioTaggingBackendRuntime(
    private val adapter: SherpaOnnxAudioTaggingRuntimeAdapter = SherpaOnnxAudioTaggingRuntimeAdapter(),
) : BackendRuntime {
    override val descriptor: RuntimeDescriptor = RuntimeDescriptor(
        key = AUDIO_TAGGING_BACKEND_KEY,
        displayName = AUDIO_TAGGING_RUNTIME_LABEL,
        supportedModelTypes = setOf(ModelType.CLASSIFICATION),
        supportedCapabilities = setOf(
            ModelCapability.Input.Audio,
            ModelCapability.Output.Text,
            ModelCapability.Output.Labels,
            ModelCapability.Execution.Local,
            ModelCapability.Execution.Cpu,
            ModelCapability.Lifecycle.ExplicitLoad,
            ModelCapability.Lifecycle.ExplicitUnload,
        ),
        supportedLocalities = setOf(ExecutionLocality.LOCAL),
    )

    @Volatile private var loadedModelId: String? = null
    @Volatile private var session: AudioTaggingSession? = null
    @Volatile private var loadState: LoadState = LoadState.NotLoaded
    @Volatile private var runtimeState: RuntimeState = RuntimeState(RuntimeState.Lifecycle.IDLE)

    override fun assessCompatibility(model: ModelDescriptor): CompatibilityResult {
        if (model.backendKey != descriptor.key) {
            return CompatibilityResult.Incompatible(CompatibilityResult.Reason.BackendKeyMismatch)
        }
        if (model.modelType != ModelType.CLASSIFICATION) {
            return CompatibilityResult.Incompatible(CompatibilityResult.Reason.ModelTypeNotSupported)
        }
        val local = model.source as? ModelDescriptor.Source.Local
            ?: return CompatibilityResult.Incompatible(CompatibilityResult.Reason.LocalityNotSupported)
        if (!File(local.artifactRef).isFile || !local.artifactRef.endsWith(".onnx", ignoreCase = true)) {
            return CompatibilityResult.Incompatible(CompatibilityResult.Reason.ModelTypeNotSupported)
        }
        val labels = model.metadata["labelsFile"]?.let(::File)
        if (labels?.isFile != true) {
            return CompatibilityResult.Incompatible(CompatibilityResult.Reason.ModelTypeNotSupported)
        }
        val required = setOf(
            ModelCapability.Input.Audio,
            ModelCapability.Output.Text,
            ModelCapability.Output.Labels,
            ModelCapability.Execution.Local,
        )
        val missing = required - model.capabilities
        if (missing.isNotEmpty()) {
            return CompatibilityResult.Incompatible(CompatibilityResult.Reason.CapabilityNotSupported)
        }
        return CompatibilityResult.Compatible("Local Sherpa-ONNX CED audio tagging model and labels are present.")
    }

    override fun getLoadState(modelId: String): LoadState =
        if (modelId == loadedModelId) loadState else LoadState.NotLoaded

    override suspend fun loadModel(model: ModelDescriptor): LoadResult {
        runtimeState = RuntimeState(RuntimeState.Lifecycle.LOADING)
        val compatibility = assessCompatibility(model)
        if (compatibility !is CompatibilityResult.Compatible) {
            return fail(model.id, FailureReason.INVALID_MODEL, "Model is not compatible with ${descriptor.key}.")
        }

        return try {
            val modelFile = File((model.source as ModelDescriptor.Source.Local).artifactRef)
            val labelsFile = File(model.metadata.getValue("labelsFile"))
            val topK = model.metadata["topK"]?.toIntOrNull()?.coerceAtLeast(1) ?: 5
            val opened = adapter.open(modelFile, labelsFile, topK)
            session?.close()
            session = opened
            loadedModelId = model.id
            loadState = LoadState.Loaded(System.currentTimeMillis())
            runtimeState = RuntimeState(RuntimeState.Lifecycle.READY)
            LoadResult.Success(loadState as LoadState.Loaded)
        } catch (t: Throwable) {
            fail(
                model.id,
                FailureReason.BACKEND_ERROR,
                "${t.javaClass.simpleName}: ${t.message ?: t.javaClass.name}",
            )
        }
    }

    override fun execute(model: ModelDescriptor, input: InferenceInput) = flow {
        val loaded = session ?: throw IllegalStateException("Audio tagging model ${model.id} is not loaded.")
        if (model.id != loadedModelId) {
            throw IllegalStateException("Audio tagging model ${model.id} is not loaded in this runtime.")
        }
        if (
            input.task != InferenceInput.Task.AudioAnalysis &&
            input.task != InferenceInput.Task.AudioTagging &&
            input.task != InferenceInput.Task.Classification
        ) {
            throw IllegalArgumentException("Sherpa CED runtime only supports AUDIO_ANALYSIS/AUDIO_TAGGING.")
        }
        val audio = input.parts.filterIsInstance<InferenceInput.Part.Audio>().firstOrNull()
            ?: throw IllegalArgumentException("AUDIO_ANALYSIS requires a WAV input reference.")
        val wav = File(audio.artifact.ref)
        require(wav.isFile) { "Audio input does not exist: ${wav.absolutePath}" }

        val started = System.currentTimeMillis()
        runtimeState = RuntimeState(RuntimeState.Lifecycle.BUSY, activeRequestCount = 1)
        Log.i(
            TAG,
            PrivacySafeDiagnostics.contentEvent(
                operation = DiagnosticOperation.AUDIO_TAGGING,
                status = DiagnosticStatus.STARTED,
                requestId = input.requestId,
                inputContent = wav.absolutePath,
                recordCount = loaded.topK,
            ),
        )
        try {
            val result = loaded.tag(wav)
            val summary = buildString {
                appendLine("AUDIO proof result")
                appendLine("finish=COMPLETED")
                appendLine("modelId=${model.id}")
                appendLine("runtime=$AUDIO_TAGGING_RUNTIME_LABEL")
                appendLine("modelPath=${loaded.modelFile.absolutePath}")
                appendLine("labelsFile=${loaded.labelsFile.absolutePath}")
                appendLine("labels file loaded=true")
                appendLine("proofWavPath=${result.audioFile.absolutePath}")
                appendLine("sampleRate=${result.sampleRate}")
                appendLine("durationSeconds=${String.format(Locale.US, "%.3f", result.durationSeconds)}")
                appendLine("topK=${loaded.topK}")
                appendLine("top-K labels:")
                result.events.forEachIndexed { index, event ->
                    appendLine(
                        "${index + 1}. ${event.label} confidence=" +
                            String.format(Locale.US, "%.6f", event.confidence) +
                            " index=${event.index}",
                    )
                }
                append("audio tagging confidence finish=COMPLETED")
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
            session?.close()
            session = null
            loadedModelId = null
            loadState = LoadState.NotLoaded
            runtimeState = RuntimeState(RuntimeState.Lifecycle.IDLE)
        }
        return UnloadResult.Success
    }

    override fun getRuntimeState(): RuntimeState = runtimeState

    private fun fail(modelId: String, reason: FailureReason, message: String): LoadResult.Failure {
        loadedModelId = modelId
        session?.close()
        session = null
        loadState = LoadState.Failed(reason, message)
        runtimeState = RuntimeState(
            RuntimeState.Lifecycle.ERROR,
            lastFailure = RuntimeState.RuntimeFailure(reason, message),
        )
        return LoadResult.Failure(reason, message)
    }
}
