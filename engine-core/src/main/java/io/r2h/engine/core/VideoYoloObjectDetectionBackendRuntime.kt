package io.r2h.engine.core

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import io.r2h.engine.api.privacy.DiagnosticOperation
import io.r2h.engine.api.privacy.DiagnosticStatus
import io.r2h.engine.api.privacy.PrivacySafeDiagnostics
import io.r2h.engine.nativebridge.YoloOnnxRuntimeAdapter
import java.io.File
import kotlin.math.roundToLong
import kotlinx.coroutines.flow.flow

private const val VIDEO_YOLO_BACKEND_KEY = "video-yolo-onnx"
private const val VIDEO_YOLO_TAG = "VideoYoloObjectDetection"

class VideoYoloObjectDetectionBackendRuntime(
    private val adapter: YoloOnnxRuntimeAdapter = YoloOnnxRuntimeAdapter(),
) : BackendRuntime {
    override val descriptor: RuntimeDescriptor = RuntimeDescriptor(
        key = VIDEO_YOLO_BACKEND_KEY,
        displayName = "Video Object Detection via YOLO ONNX",
        supportedModelTypes = setOf(ModelType.DETECTION),
        supportedCapabilities = setOf(
            ModelCapability.Input.Video,
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
    @Volatile private var loadedModelFile: File? = null
    @Volatile private var loadState: LoadState = LoadState.NotLoaded
    @Volatile private var runtimeState: RuntimeState = RuntimeState(RuntimeState.Lifecycle.IDLE)

    override fun assessCompatibility(model: ModelDescriptor): CompatibilityResult {
        if (model.backendKey != descriptor.key) {
            return CompatibilityResult.Incompatible(CompatibilityResult.Reason.BackendKeyMismatch)
        }
        if (model.modelType != ModelType.DETECTION) {
            return CompatibilityResult.Incompatible(CompatibilityResult.Reason.ModelTypeNotSupported)
        }
        val local = model.source as? ModelDescriptor.Source.Local
            ?: return CompatibilityResult.Incompatible(CompatibilityResult.Reason.LocalityNotSupported)
        if (!local.format.equals("onnx", ignoreCase = true) || !File(local.artifactRef).isFile) {
            return CompatibilityResult.Incompatible(CompatibilityResult.Reason.ModelTypeNotSupported)
        }
        val required = setOf(ModelCapability.Input.Video, ModelCapability.Output.Text, ModelCapability.Execution.Local)
        val missing = required - model.capabilities
        if (missing.isNotEmpty()) {
            return CompatibilityResult.Incompatible(CompatibilityResult.Reason.CapabilityNotSupported)
        }
        return CompatibilityResult.Compatible("Video frame extraction delegates sampled frames to local YOLO ONNX.")
    }

    override fun getLoadState(modelId: String): LoadState =
        if (modelId == loadedModelId) loadState else LoadState.NotLoaded

    override suspend fun loadModel(model: ModelDescriptor): LoadResult {
        runtimeState = RuntimeState(RuntimeState.Lifecycle.LOADING)
        val compatibility = assessCompatibility(model)
        if (compatibility !is CompatibilityResult.Compatible) {
            return fail(model.id, FailureReason.INVALID_MODEL, "Model is not compatible with ${descriptor.key}.")
        }

        val modelFile = File((model.source as ModelDescriptor.Source.Local).artifactRef)
        val smoke = adapter.runSmoke(modelFile)
        if (smoke.status != io.r2h.engine.nativebridge.RuntimeProbeStatus.AVAILABLE) {
            return fail(
                model.id,
                FailureReason.BACKEND_ERROR,
                "${smoke.errorCode ?: "YOLO_LOAD_FAILED"}: ${smoke.errorMessage ?: smoke.outputPreview}",
            )
        }

        loadedModelId = model.id
        loadedModelFile = modelFile
        loadState = LoadState.Loaded(System.currentTimeMillis())
        runtimeState = RuntimeState(RuntimeState.Lifecycle.READY)
        return LoadResult.Success(loadState as LoadState.Loaded)
    }

    override fun execute(model: ModelDescriptor, input: InferenceInput) = flow {
        val modelFile = loadedModelFile
            ?: throw IllegalStateException("Video YOLO model ${model.id} is not loaded.")
        if (model.id != loadedModelId) {
            throw IllegalStateException("Video YOLO model ${model.id} is not loaded in this runtime.")
        }
        if (input.task != InferenceInput.Task.Detection) {
            throw IllegalArgumentException("Video object detection requires OBJECT_DETECTION task.")
        }
        val video = input.parts.filterIsInstance<InferenceInput.Part.Video>().firstOrNull()
            ?: throw IllegalArgumentException("Video object detection requires a video input reference.")
        val videoFile = File(video.artifact.ref)
        if (!videoFile.isFile) {
            throw IllegalArgumentException("Video input does not exist: ${videoFile.absolutePath}")
        }

        val confidence = input.metadata["yolo.confidenceThreshold"]?.toFloatOrNull()?.coerceIn(0.01f, 0.99f) ?: 0.05f
        val iou = input.metadata["yolo.iouThreshold"]?.toFloatOrNull()?.coerceIn(0.01f, 0.99f) ?: 0.45f
        val requestedSamples = input.metadata["video.sampleCount"]?.toIntOrNull()?.coerceIn(1, 12) ?: 3
        val started = System.currentTimeMillis()
        runtimeState = RuntimeState(RuntimeState.Lifecycle.BUSY, activeRequestCount = 1)
        try {
            val frames = extractFrames(videoFile, input.requestId, requestedSamples)
            val timeline = StringBuilder()
            val allLabels = linkedSetOf<String>()
            timeline.appendLine("Video object detection proof result")
            timeline.appendLine("finish=COMPLETED")
            timeline.appendLine("modelId=${model.id}")
            timeline.appendLine("runtime=Video frame extraction + YOLO ONNX")
            timeline.appendLine("videoPath=${videoFile.absolutePath}")
            timeline.appendLine("videoDurationMs=${frames.durationMs}")
            timeline.appendLine("sampledFrameCount=${frames.frames.size}")
            timeline.appendLine("apiPath=IR2hEngineService.analyzeVideo(VIDEO, OBJECT_DETECTION)")
            timeline.appendLine("timeline:")
            frames.frames.forEach { frame ->
                val detections = adapter.detectObjects(
                    modelFile = modelFile,
                    imageFile = frame.file,
                    confidenceThreshold = confidence,
                    iouThreshold = iou,
                )
                val summary = if (detections.isEmpty()) {
                    "no_objects_detected"
                } else {
                    detections.joinToString { detection ->
                        allLabels += detection.label
                        "${detection.label}@${detection.left.toInt()},${detection.top.toInt()},${detection.right.toInt()},${detection.bottom.toInt()}:${detection.score}"
                    }
                }
                Log.i(
                    VIDEO_YOLO_TAG,
                    PrivacySafeDiagnostics.contentEvent(
                        operation = DiagnosticOperation.VIDEO_ANALYSIS,
                        status = DiagnosticStatus.UPDATED,
                        requestId = input.requestId,
                        outputContent = summary,
                        recordCount = detections.size,
                        durationMs = frame.timeMs,
                    ),
                )
                timeline.appendLine("frame=${frame.index} timeMs=${frame.timeMs} detections=$summary")
            }
            timeline.append("timelineSummary=")
            timeline.append(if (allLabels.isEmpty()) "no_objects_detected" else allLabels.joinToString())

            emit(
                InferenceOutput(
                    requestId = input.requestId,
                    phase = InferenceOutput.Phase.Final,
                    items = listOf(InferenceOutput.Item.Text(timeline.toString())),
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
            loadedModelFile = null
            loadState = LoadState.NotLoaded
            runtimeState = RuntimeState(RuntimeState.Lifecycle.IDLE)
        }
        return UnloadResult.Success
    }

    override fun getRuntimeState(): RuntimeState = runtimeState

    private fun extractFrames(videoFile: File, requestId: String, sampleCount: Int): ExtractedVideoFrames {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(videoFile.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?.coerceAtLeast(1L)
                ?: 1L
            val frameDir = File(videoFile.parentFile, "video-proof-frames/$requestId").apply { mkdirs() }
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
                bitmap.useCompressedJpeg(output)
                ExtractedFrame(index = index, timeMs = timeMs, file = output)
            }
            ExtractedVideoFrames(durationMs = durationMs, frames = frames)
        } finally {
            retriever.release()
        }
    }

    private fun Bitmap.useCompressedJpeg(target: File) {
        target.parentFile?.mkdirs()
        target.outputStream().use { output ->
            compress(Bitmap.CompressFormat.JPEG, 95, output)
        }
        recycle()
    }

    private fun fail(modelId: String, reason: FailureReason, message: String): LoadResult.Failure {
        loadedModelId = modelId
        loadedModelFile = null
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
}
