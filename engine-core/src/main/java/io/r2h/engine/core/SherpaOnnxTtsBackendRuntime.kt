package io.r2h.engine.core

import io.r2h.engine.nativebridge.SherpaOnnxTtsRuntimeAdapter
import io.r2h.engine.nativebridge.RuntimeProbeStatus
import kotlinx.coroutines.flow.flow
import java.io.File
import java.util.Locale

private const val TTS_BACKEND_KEY = "sherpa-onnx-tts"
private const val ENGLISH_VOICE_ID = "vits-piper-en_US-lessac-low-int8"
private const val ARABIC_VOICE_ID = "vits-piper-ar_JO-kareem-medium-int8"

class SherpaOnnxTtsBackendRuntime(
    private val outputDirectory: File,
    private val adapter: SherpaOnnxTtsRuntimeAdapter = SherpaOnnxTtsRuntimeAdapter(),
) : BackendRuntime {
    override val descriptor: RuntimeDescriptor = RuntimeDescriptor(
        key = TTS_BACKEND_KEY,
        displayName = "Sherpa-ONNX Piper TTS",
        supportedModelTypes = setOf(ModelType.TEXT_TO_SPEECH),
        supportedCapabilities = setOf(
            ModelCapability.Input.Text,
            ModelCapability.Output.Audio,
            ModelCapability.Output.Text,
            ModelCapability.Execution.Local,
            ModelCapability.Execution.Cpu,
            ModelCapability.Lifecycle.ExplicitLoad,
            ModelCapability.Lifecycle.ExplicitUnload,
        ),
        supportedLocalities = setOf(ExecutionLocality.LOCAL),
    )

    @Volatile private var loadedModelId: String? = null
    @Volatile private var voiceRoot: File? = null
    @Volatile private var loadState: LoadState = LoadState.NotLoaded
    @Volatile private var runtimeState: RuntimeState = RuntimeState(RuntimeState.Lifecycle.IDLE)

    override fun assessCompatibility(model: ModelDescriptor): CompatibilityResult {
        if (model.backendKey != descriptor.key) {
            return CompatibilityResult.Incompatible(CompatibilityResult.Reason.BackendKeyMismatch)
        }
        if (model.modelType != ModelType.TEXT_TO_SPEECH) {
            return CompatibilityResult.Incompatible(CompatibilityResult.Reason.ModelTypeNotSupported)
        }
        val local = model.source as? ModelDescriptor.Source.Local
            ?: return CompatibilityResult.Incompatible(CompatibilityResult.Reason.LocalityNotSupported)
        val root = File(local.artifactRef)
        if (!voiceDir(root, ENGLISH_VOICE_ID).isDirectory || !voiceDir(root, ARABIC_VOICE_ID).isDirectory) {
            return CompatibilityResult.Incompatible(CompatibilityResult.Reason.ModelTypeNotSupported)
        }
        val required = setOf(ModelCapability.Input.Text, ModelCapability.Output.Audio, ModelCapability.Execution.Local)
        val missing = required - model.capabilities
        if (missing.isNotEmpty()) {
            return CompatibilityResult.Incompatible(CompatibilityResult.Reason.CapabilityNotSupported)
        }
        return CompatibilityResult.Compatible("Selected Sherpa/Piper voice directories are present.")
    }

    override fun getLoadState(modelId: String): LoadState =
        if (modelId == loadedModelId) loadState else LoadState.NotLoaded

    override suspend fun loadModel(model: ModelDescriptor): LoadResult {
        runtimeState = RuntimeState(RuntimeState.Lifecycle.LOADING)
        val compatibility = assessCompatibility(model)
        if (compatibility !is CompatibilityResult.Compatible) {
            return fail(model.id, FailureReason.INVALID_MODEL, "Model is not compatible with ${descriptor.key}.")
        }

        val root = File((model.source as ModelDescriptor.Source.Local).artifactRef)
        val privateOutputRoot = prepareOutputDirectory()
        val smoke = adapter.runSmoke(
            englishModelDir = voiceDir(root, ENGLISH_VOICE_ID),
            arabicModelDir = voiceDir(root, ARABIC_VOICE_ID),
            outputWav = File(privateOutputRoot, "tts-load-smoke.wav"),
        )
        if (smoke.status != RuntimeProbeStatus.AVAILABLE) {
            return fail(
                model.id,
                FailureReason.BACKEND_ERROR,
                "${smoke.errorCode ?: "TTS_LOAD_FAILED"}: ${smoke.errorMessage ?: smoke.outputPreview}",
            )
        }

        loadedModelId = model.id
        voiceRoot = root
        loadState = LoadState.Loaded(System.currentTimeMillis())
        runtimeState = RuntimeState(RuntimeState.Lifecycle.READY)
        return LoadResult.Success(loadState as LoadState.Loaded)
    }

    override fun execute(model: ModelDescriptor, input: InferenceInput) = flow {
        val root = voiceRoot ?: throw IllegalStateException("TTS model ${model.id} is not loaded.")
        if (model.id != loadedModelId) {
            throw IllegalStateException("TTS model ${model.id} is not loaded in this runtime.")
        }
        if (input.task != InferenceInput.Task.TextToSpeech) {
            throw IllegalArgumentException("Sherpa TTS runtime only supports TEXT_TO_SPEECH.")
        }
        val text = input.parts.filterIsInstance<InferenceInput.Part.Text>()
            .joinToString("\n") { it.content }
            .trim()
        require(text.isNotBlank()) { "TEXT_TO_SPEECH requires textPrompt." }

        val requestedVoice = input.metadata["tts.voice"].orEmpty()
        val voiceId = when {
            requestedVoice.contains("ar", ignoreCase = true) || containsArabic(text) -> ARABIC_VOICE_ID
            else -> ENGLISH_VOICE_ID
        }
        val output = createPrivateOutputFile()
        val started = System.currentTimeMillis()
        runtimeState = RuntimeState(RuntimeState.Lifecycle.BUSY, activeRequestCount = 1)
        try {
            val result = try {
                adapter.synthesize(
                    modelDir = voiceDir(root, voiceId),
                    text = text,
                    outputWav = output,
                    voiceId = voiceId,
                )
            } catch (failure: Throwable) {
                output.delete()
                throw failure
            }
            val summary = buildString {
                appendLine("TTS proof result")
                appendLine("finish=COMPLETED")
                appendLine("voiceId=${result.voiceId}")
                appendLine("sampleRate=${result.sampleRate}")
                appendLine("durationSeconds=${String.format(Locale.US, "%.3f", result.durationSeconds)}")
                appendLine("sampleCount=${result.sampleCount}")
                appendLine("outputPath=${result.outputWav.absolutePath}")
                append("runtime=Sherpa-ONNX Piper")
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
            voiceRoot = null
            loadState = LoadState.NotLoaded
            runtimeState = RuntimeState(RuntimeState.Lifecycle.IDLE)
        }
        return UnloadResult.Success
    }

    override fun getRuntimeState(): RuntimeState = runtimeState

    private fun fail(modelId: String, reason: FailureReason, message: String): LoadResult.Failure {
        loadedModelId = modelId
        voiceRoot = null
        loadState = LoadState.Failed(reason, message)
        runtimeState = RuntimeState(
            RuntimeState.Lifecycle.ERROR,
            lastFailure = RuntimeState.RuntimeFailure(reason, message),
        )
        return LoadResult.Failure(reason, message)
    }

    private fun voiceDir(root: File, voiceId: String): File = File(root, voiceId)

    private fun createPrivateOutputFile(): File {
        val root = prepareOutputDirectory()
        val output = File.createTempFile("tts-", ".wav", root).canonicalFile
        check(output.parentFile == root) { "Generated TTS output escaped the private output directory." }
        return output
    }

    private fun prepareOutputDirectory(): File {
        if (!outputDirectory.exists() && !outputDirectory.mkdirs()) {
            error("Unable to create the private TTS output directory.")
        }
        require(outputDirectory.isDirectory) { "The private TTS output path is not a directory." }
        return outputDirectory.canonicalFile.also { canonicalRoot ->
            require(canonicalRoot.isDirectory) { "The private TTS output directory is unavailable." }
        }
    }

    private fun containsArabic(text: String): Boolean =
        text.any { it in '\u0600'..'\u06FF' || it in '\u0750'..'\u077F' || it in '\u08A0'..'\u08FF' }
}
