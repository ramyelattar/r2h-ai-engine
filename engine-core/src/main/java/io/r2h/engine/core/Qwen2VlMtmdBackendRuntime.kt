package io.r2h.engine.core

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import io.r2h.engine.api.privacy.DiagnosticOperation
import io.r2h.engine.api.privacy.DiagnosticStatus
import io.r2h.engine.api.privacy.PrivacySafeDiagnostics
import io.r2h.engine.nativebridge.NativeErrorCode
import io.r2h.engine.nativebridge.NativeFinishCode
import io.r2h.engine.nativebridge.Qwen2VlMtmdRuntimeAdapter
import kotlinx.coroutines.flow.flow
import java.io.File
import kotlin.math.roundToLong

class Qwen2VlMtmdBackendRuntime(
    private val adapter: Qwen2VlMtmdRuntimeAdapter = Qwen2VlMtmdRuntimeAdapter(),
) : BackendRuntime {
    override val descriptor: RuntimeDescriptor = RuntimeDescriptor(
        key = "qwen2vl-mtmd",
        displayName = "llama.cpp mtmd / Qwen2VL",
        supportedModelTypes = setOf(ModelType.VISION, ModelType.GGUF),
        supportedCapabilities = setOf(
            ModelCapability.Input.Image,
            ModelCapability.Input.Text,
            ModelCapability.Input.Video,
            ModelCapability.Output.Text,
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
    @Volatile private var loadedModelPath: String = ""
    @Volatile private var loadedMmprojPath: String = ""

    override fun assessCompatibility(model: ModelDescriptor): CompatibilityResult {
        if (model.backendKey != descriptor.key) {
            return CompatibilityResult.Incompatible(CompatibilityResult.Reason.BackendKeyMismatch)
        }
        if (model.modelType !in descriptor.supportedModelTypes) {
            return CompatibilityResult.Incompatible(CompatibilityResult.Reason.ModelTypeNotSupported)
        }
        val local = model.source as? ModelDescriptor.Source.Local
            ?: return CompatibilityResult.Incompatible(CompatibilityResult.Reason.LocalityNotSupported)
        val modelFile = File(local.artifactRef)
        val mmprojFile = File(model.metadata["mmprojPath"].orEmpty())
        if (!modelFile.isFile || !mmprojFile.isFile) {
            return CompatibilityResult.Incompatible(CompatibilityResult.Reason.ModelTypeNotSupported)
        }
        val required = setOf(ModelCapability.Input.Image, ModelCapability.Output.Text, ModelCapability.Execution.Local)
        val missing = required - model.capabilities
        if (missing.isNotEmpty()) {
            return CompatibilityResult.Incompatible(CompatibilityResult.Reason.CapabilityNotSupported)
        }
        return CompatibilityResult.Compatible("Qwen2VL GGUF plus mmproj are present for llama.cpp mtmd.")
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
        val mmprojPath = model.metadata["mmprojPath"].orEmpty()
        return try {
            val loaded = adapter.load(File(local.artifactRef), File(mmprojPath))
            if (!loaded) {
                fail(model.id, FailureReason.BACKEND_ERROR, "Native mtmd load returned 0.")
            } else {
                loadedModelId = model.id
                loadedModelPath = local.artifactRef
                loadedMmprojPath = mmprojPath
                loadState = LoadState.Loaded(System.currentTimeMillis())
                runtimeState = RuntimeState(RuntimeState.Lifecycle.READY)
                LoadResult.Success(loadState as LoadState.Loaded)
            }
        } catch (t: Throwable) {
            fail(model.id, FailureReason.BACKEND_ERROR, "Native mtmd load failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    override fun execute(model: ModelDescriptor, input: InferenceInput) = flow {
        if (loadedModelId != model.id || loadState !is LoadState.Loaded) {
            throw IllegalStateException("Model ${model.id} is not loaded.")
        }
        if (input.task == InferenceInput.Task.VideoFullAnalysis) {
            emit(executeVideoFullAnalysis(model, input))
            return@flow
        }
        if (input.task !in setOf(
                InferenceInput.Task.ImageUnderstanding,
                InferenceInput.Task.ImageTextMultimodal,
                InferenceInput.Task.Ocr,
            )
        ) {
            throw IllegalArgumentException("Qwen2VL mtmd supports image+text and OCR tasks only.")
        }
        val imagePath = input.parts.filterIsInstance<InferenceInput.Part.Image>()
            .firstOrNull()?.artifact?.ref
            ?: throw IllegalArgumentException("Qwen2VL mtmd request requires an image path.")
        val prompt = input.parts.filterIsInstance<InferenceInput.Part.Text>()
            .joinToString("\n") { it.content }
            .ifBlank { "Describe this image and list the main visible objects." }
        val maxTokens = input.metadata["engine.maxTokens"]?.toIntOrNull()?.coerceAtLeast(1) ?: 160
        val temperature = input.metadata["engine.temperature"]?.toFloatOrNull()?.coerceIn(0f, 2f) ?: 0.2f

        runtimeState = RuntimeState(RuntimeState.Lifecycle.BUSY, activeRequestCount = 1)
        try {
            val generation = adapter.generateFromImage(
                prompt = prompt,
                imageFile = File(imagePath),
                maxTokens = maxTokens,
                temperature = temperature,
            )
            if (generation.nativeResult.errorCode != NativeErrorCode.SUCCESS &&
                generation.nativeResult.errorCode != NativeErrorCode.CANCELLED
            ) {
                throw IllegalStateException(
                    "Qwen2VL mtmd generation failed with errorCode=${generation.nativeResult.errorCode}.",
                )
            }
            val finish = when (generation.nativeResult.finishReasonCode) {
                NativeFinishCode.CANCELLED -> InferenceOutput.FinishReason.CANCELLED
                NativeFinishCode.MAX_TOKENS -> InferenceOutput.FinishReason.LENGTH
                else -> InferenceOutput.FinishReason.COMPLETED
            }
            val generatedText = generation.text
            if (generatedText.isBlank()) {
                throw IllegalStateException("Qwen2VL mtmd generated no answer text.")
            }
            val proofText = buildString {
                val ocr = input.task == InferenceInput.Task.Ocr
                appendLine(if (ocr) "OCR proof result" else "MULTIMODAL proof result")
                appendLine("finish=$finish")
                appendLine("modelId=${model.id}")
                appendLine("runtime=${if (ocr) "Qwen2VL mtmd OCR" else "llama.cpp mtmd / Qwen2VL"}")
                appendLine("modelPath=$loadedModelPath")
                appendLine("mmprojPath=$loadedMmprojPath")
                appendLine("prompt=$prompt")
                appendLine("imagePath=$imagePath")
                appendLine(if (ocr) "extracted text:" else "generated text:")
                appendLine(generatedText)
                appendLine(
                    if (ocr) {
                        "OCR Qwen2VL mtmd extracted text finish=$finish"
                    } else {
                        "generateFromImage mtmd Qwen2VL mmproj finish=$finish"
                    },
                )
            }.trimEnd()
            emit(
                InferenceOutput(
                    requestId = input.requestId,
                    phase = InferenceOutput.Phase.Final,
                    items = listOf(InferenceOutput.Item.Text(proofText)),
                    completion = InferenceOutput.CompletionStatus.Terminal(finish),
                    latencyMs = generation.elapsedMs,
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
        runCatching { adapter.unload() }
        loadedModelId = null
        loadedModelPath = ""
        loadedMmprojPath = ""
        loadState = LoadState.NotLoaded
        runtimeState = RuntimeState(RuntimeState.Lifecycle.IDLE)
        return UnloadResult.Success
    }

    override fun getRuntimeState(): RuntimeState = runtimeState

    private fun executeVideoFullAnalysis(model: ModelDescriptor, input: InferenceInput): InferenceOutput {
        val videoPath = input.parts.filterIsInstance<InferenceInput.Part.Video>()
            .firstOrNull()?.artifact?.ref
            ?: throw IllegalArgumentException("Qwen2VL mtmd video full analysis requires a video path.")
        val videoFile = File(videoPath)
        if (!videoFile.isFile) {
            throw IllegalArgumentException("Video input does not exist: ${videoFile.absolutePath}")
        }
        val requestedSamples = input.metadata["video.sampleCount"]?.toIntOrNull()?.coerceIn(1, 4) ?: 2
        val maxTokens = input.metadata["video.frameMaxTokens"]?.toIntOrNull()?.coerceAtLeast(32) ?: 96
        val temperature = input.metadata["engine.temperature"]?.toFloatOrNull()?.coerceIn(0f, 2f) ?: 0.2f
        val proofPrompt = input.parts.filterIsInstance<InferenceInput.Part.Text>()
            .joinToString("\n") { it.content }
            .ifBlank { "Analyze this video frame and describe the main visible objects and scene." }

        val started = System.currentTimeMillis()
        runtimeState = RuntimeState(RuntimeState.Lifecycle.BUSY, activeRequestCount = 1)
        return try {
            val frames = extractVideoFrames(videoFile, input.requestId, requestedSamples)
            val frameSummaries = frames.frames.map { frame ->
                val framePrompt = "$proofPrompt\nFrame time: ${frame.timeMs} ms."
                val generation = adapter.generateFromImage(
                    prompt = framePrompt,
                    imageFile = frame.file,
                    maxTokens = maxTokens,
                    temperature = temperature,
                )
                if (generation.nativeResult.errorCode != NativeErrorCode.SUCCESS &&
                    generation.nativeResult.errorCode != NativeErrorCode.CANCELLED
                ) {
                    throw IllegalStateException(
                        "Qwen2VL mtmd video frame generation failed with errorCode=${generation.nativeResult.errorCode}.",
                    )
                }
                val text = generation.text.ifBlank {
                    throw IllegalStateException("Qwen2VL mtmd generated no video frame summary text.")
                }
                Log.i(
                    "Qwen2VlMtmd",
                    PrivacySafeDiagnostics.contentEvent(
                        operation = DiagnosticOperation.VIDEO_ANALYSIS,
                        status = DiagnosticStatus.UPDATED,
                        requestId = input.requestId,
                        outputContent = text,
                        recordCount = 1,
                        durationMs = frame.timeMs,
                    ),
                )
                FrameSummary(frame.index, frame.timeMs, frame.file.absolutePath, text)
            }
            val finalSummary = frameSummaries.joinToString(" ") { summary ->
                "Frame ${summary.index} at ${summary.timeMs}ms: ${summary.text}"
            }.take(1600)
            Log.i(
                "Qwen2VlMtmd",
                "VIDEO full analysis final video summary sampled frames=${frameSummaries.size} mtmd Qwen2VL finish=COMPLETED",
            )
            val proofText = buildString {
                appendLine("VIDEO full analysis proof result")
                appendLine("finish=COMPLETED")
                appendLine("modelId=${model.id}")
                appendLine("runtime=Video frame extraction + Qwen2VL mtmd")
                appendLine("videoPath=${videoFile.absolutePath}")
                appendLine("videoDurationMs=${frames.durationMs}")
                appendLine("sampledFrameCount=${frameSummaries.size}")
                appendLine("prompt=$proofPrompt")
                appendLine("apiPath=IR2hEngineService.analyzeVideo(VIDEO, VIDEO_FULL_ANALYSIS)")
                appendLine("per-frame multimodal summaries:")
                frameSummaries.forEach { summary ->
                    appendLine("frame=${summary.index} timeMs=${summary.timeMs} framePath=${summary.path}")
                    appendLine("frame summary=${summary.text}")
                }
                appendLine("final video summary:")
                appendLine(finalSummary)
                appendLine("optionalYoloTimeline=video object detection proof runner remains available")
                appendLine("VIDEO full analysis sampled frames Qwen2VL mtmd frame summary final video summary finish=COMPLETED")
            }.trimEnd()
            InferenceOutput(
                requestId = input.requestId,
                phase = InferenceOutput.Phase.Final,
                items = listOf(InferenceOutput.Item.Text(proofText)),
                completion = InferenceOutput.CompletionStatus.Terminal(InferenceOutput.FinishReason.COMPLETED),
                latencyMs = System.currentTimeMillis() - started,
            )
        } finally {
            runtimeState = RuntimeState(RuntimeState.Lifecycle.READY)
        }
    }

    private fun extractVideoFrames(videoFile: File, requestId: String, sampleCount: Int): ExtractedVideoFrames {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(1L)
                ?: 1L
            val frameDir = File(videoFile.parentFile, "video-full-analysis-frames/$requestId").apply { mkdirs() }
            val times = if (sampleCount == 1) {
                listOf(durationMs / 2L)
            } else {
                (0 until sampleCount).map { index ->
                    ((durationMs - 1L) * (index.toDouble() / (sampleCount - 1).toDouble())).roundToLong()
                }
            }
            val frames = times.mapIndexed { index, timeMs ->
                val bitmap = retriever.getFrameAtTime(timeMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    ?: throw IllegalStateException("Unable to extract video frame at ${timeMs}ms from ${videoFile.absolutePath}.")
                val output = File(frameDir, "frame-%02d.jpg".format(index))
                bitmap.writeCompressedJpeg(output)
                ExtractedFrame(index, timeMs, output)
            }
            ExtractedVideoFrames(durationMs, frames)
        } finally {
            retriever.release()
        }
    }

    private fun Bitmap.writeCompressedJpeg(target: File) {
        target.parentFile?.mkdirs()
        target.outputStream().use { output ->
            compress(Bitmap.CompressFormat.JPEG, 95, output)
        }
        recycle()
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

    private data class ExtractedVideoFrames(
        val durationMs: Long,
        val frames: List<ExtractedFrame>,
    )

    private data class ExtractedFrame(
        val index: Int,
        val timeMs: Long,
        val file: File,
    )

    private data class FrameSummary(
        val index: Int,
        val timeMs: Long,
        val path: String,
        val text: String,
    )
}
