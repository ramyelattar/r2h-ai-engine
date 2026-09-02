package io.r2h.engine.core

import io.r2h.engine.nativebridge.YoloOnnxRuntimeAdapter
import kotlinx.coroutines.flow.flow
import java.io.File

private const val YOLO_BACKEND_KEY = "yolo-onnx"

class YoloOnnxDetectionBackendRuntime(
    private val adapter: YoloOnnxRuntimeAdapter = YoloOnnxRuntimeAdapter(),
) : BackendRuntime {
    override val descriptor: RuntimeDescriptor = RuntimeDescriptor(
        key = YOLO_BACKEND_KEY,
        displayName = "YOLO ONNX Runtime Android",
        supportedModelTypes = setOf(ModelType.DETECTION),
        supportedCapabilities = setOf(
            ModelCapability.Input.Image,
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
        val required = setOf(ModelCapability.Input.Image, ModelCapability.Output.Labels, ModelCapability.Execution.Local)
        val missing = required - model.capabilities
        if (missing.isNotEmpty()) {
            return CompatibilityResult.Incompatible(CompatibilityResult.Reason.CapabilityNotSupported)
        }
        return CompatibilityResult.Compatible("Local YOLO ONNX model is loadable by ONNX Runtime Android.")
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
            ?: throw IllegalStateException("YOLO model ${model.id} is not loaded.")
        if (model.id != loadedModelId) {
            throw IllegalStateException("YOLO model ${model.id} is not loaded in this runtime.")
        }
        val image = input.parts.filterIsInstance<InferenceInput.Part.Image>().firstOrNull()
            ?: throw IllegalArgumentException("YOLO detection requires an image input reference.")
        val imageFile = File(image.artifact.ref)
        if (!imageFile.isFile) {
            throw IllegalArgumentException("Image input does not exist: ${imageFile.absolutePath}")
        }

        val confidence = input.metadata["yolo.confidenceThreshold"]?.toFloatOrNull()?.coerceIn(0.01f, 0.99f) ?: 0.25f
        val iou = input.metadata["yolo.iouThreshold"]?.toFloatOrNull()?.coerceIn(0.01f, 0.99f) ?: 0.45f
        val started = System.currentTimeMillis()
        runtimeState = RuntimeState(RuntimeState.Lifecycle.BUSY, activeRequestCount = 1)
        try {
            val detections = adapter.detectObjects(
                modelFile = modelFile,
                imageFile = imageFile,
                confidenceThreshold = confidence,
                iouThreshold = iou,
            )
            val labels = if (detections.isEmpty()) {
                listOf(InferenceOutput.LabelScore("no_objects_detected", 1.0f))
            } else {
                detections.map { detection ->
                    InferenceOutput.LabelScore(
                        label = "${detection.label}@${detection.left.toInt()},${detection.top.toInt()},${detection.right.toInt()},${detection.bottom.toInt()}",
                        score = detection.score,
                    )
                }
            }
            emit(
                InferenceOutput(
                    requestId = input.requestId,
                    phase = InferenceOutput.Phase.Final,
                    items = listOf(InferenceOutput.Item.Labels(labels)),
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
}
