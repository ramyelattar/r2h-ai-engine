package io.r2h.engine.nativebridge.onnx

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import io.r2h.engine.core.InferenceInput
import io.r2h.engine.core.InferenceOutput
import io.r2h.engine.core.ModelCapability
import io.r2h.engine.core.ModelDescriptor
import io.r2h.engine.core.ModelType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Real ONNX-based image understanding engine.
 *
 * Supported operations:
 * - Image classification → [InferenceOutput.Item.Labels] (top-k label scores)
 * - Image embedding → [InferenceOutput.Item.Embedding] (pooled feature vector)
 *
 * Classification label mapping:
 * - The model's `onnx.labels` metadata key may contain comma-separated label names.
 * - If absent, labels are synthesized as "class_N".
 *
 * Image preprocessing defaults:
 * - 224×224 resize, ImageNet normalization (configurable via model metadata).
 */
class RealImageOnnxEngine : ImageOnnxEngine {

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()

    private data class LoadedVisionModel(
        val descriptor: ModelDescriptor,
        val session: OrtSession,
        val contract: ImageOnnxSessionContract,
        val processor: ImageTensorProcessor,
        val labels: List<String>,
    )

    private val loadedModels = ConcurrentHashMap<String, LoadedVisionModel>()
    private val cancellations = ConcurrentHashMap<String, AtomicBoolean>()

    override val version: String? get() = OrtEnvironment.getVersion()

    override val supportedModelTypes: Set<ModelType> = setOf(
        ModelType.VISION,
        ModelType.CLASSIFICATION,
        ModelType.DETECTION,
    )

    override val supportedCapabilities: Set<ModelCapability> = setOf(
        ModelCapability.Input.Image,
        ModelCapability.Output.Labels,
        ModelCapability.Output.EmbeddingVector,
        ModelCapability.Execution.Local,
        ModelCapability.Execution.Cpu,
        ModelCapability.Lifecycle.ExplicitLoad,
        ModelCapability.Lifecycle.ExplicitUnload,
        ModelCapability.Interaction.Cancellation,
    )

    override val supportsConcurrentExecution: Boolean = false

    override suspend fun load(model: ModelDescriptor) {
        val local = model.source as? ModelDescriptor.Source.Local
            ?: throw OnnxRuntimeLoadException(
                OnnxRuntimeLoadException.Reason.MODEL_CONTRACT_UNSUPPORTED,
                "Image ONNX engine requires a Local source.",
            )

        val file = File(local.artifactRef)
        if (!file.exists()) throw OnnxRuntimeLoadException(
            OnnxRuntimeLoadException.Reason.MODEL_CONTRACT_UNSUPPORTED,
            "Image model file not found: ${file.absolutePath}",
        )

        val sessionOpts = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(
                model.metadata["onnx.threads"]?.toIntOrNull() ?: 2
            )
        }

        val session = try {
            env.createSession(file.absolutePath, sessionOpts)
        } catch (e: Exception) {
            throw OnnxRuntimeLoadException(
                OnnxRuntimeLoadException.Reason.SESSION_CREATION_FAILED,
                "Failed to create ONNX session for image model '${model.id}': ${e.message}",
            )
        }

        val contract = ImageOnnxSessionContract.inspect(session)
        if (!contract.isUsable) {
            session.close()
            // IMAGE_CONTRACT_UNSUPPORTED maps to ErrorCode.IMAGE_CONTRACT_UNSUPPORTED via
            // parseLoadStatusError(). A generic MODEL_CONTRACT_UNSUPPORTED would hide the
            // true cause and prevent callers from distinguishing image vs. text contract failures.
            throw OnnxRuntimeLoadException(
                OnnxRuntimeLoadException.Reason.IMAGE_CONTRACT_UNSUPPORTED,
                "Image model '${model.id}' has no recognized image input or usable output. " +
                    "Expected 'pixel_values' input and at least logits or hidden-state output. " +
                    "Inputs found: ${session.inputNames.joinToString()}",
            )
        }

        val targetW = model.metadata["image.width"]?.toIntOrNull()
            ?: model.capabilityProfile?.expectedImageSize?.widthPx ?: 224
        val targetH = model.metadata["image.height"]?.toIntOrNull()
            ?: model.capabilityProfile?.expectedImageSize?.heightPx ?: 224
        val normPreset = model.metadata["image.normalization"] ?: "imagenet"
        val (mean, std) = when (normPreset.lowercase()) {
            "clip" -> Pair(ImageTensorProcessor.CLIP_MEAN, ImageTensorProcessor.CLIP_STD)
            "unit", "none" -> Pair(ImageTensorProcessor.UNIT_MEAN, ImageTensorProcessor.UNIT_STD)
            else -> Pair(ImageTensorProcessor.IMAGENET_MEAN, ImageTensorProcessor.IMAGENET_STD)
        }

        val processor = ImageTensorProcessor(targetW, targetH, mean, std)
        val labels = parseLabels(model.metadata["onnx.labels"])

        loadedModels[model.id] = LoadedVisionModel(model, session, contract, processor, labels)
    }

    override suspend fun unload(model: ModelDescriptor) {
        loadedModels.remove(model.id)?.session?.close()
    }

    override fun execute(
        model: ModelDescriptor,
        input: InferenceInput,
        onFinished: (requestId: String) -> Unit,
    ): Flow<InferenceOutput> = flow {
        val loaded = loadedModels[model.id]
            ?: throw IllegalStateException("Image model '${model.id}' is not loaded.")

        val cancelFlag = AtomicBoolean(false)
        cancellations[input.requestId] = cancelFlag

        try {
            val imagePart = input.parts.filterIsInstance<InferenceInput.Part.Image>().firstOrNull()
                ?: input.parts.filterIsInstance<InferenceInput.Part.Text>().firstOrNull()
                    ?.let { null }  // no image part found

            if (imagePart == null) {
                emit(
                    InferenceOutput(
                        requestId = input.requestId,
                        phase = InferenceOutput.Phase.Final,
                        items = listOf(
                            InferenceOutput.Item.Text(
                                "No image input found in request.",
                                isDelta = false,
                            )
                        ),
                        completion = InferenceOutput.CompletionStatus.Terminal(
                            InferenceOutput.FinishReason.REJECTED,
                        ),
                    )
                )
                return@flow
            }

            if (cancelFlag.get()) {
                emit(cancelled(input.requestId))
                return@flow
            }

            val imageFile = File(imagePart.artifact.ref)
            val buf = loaded.processor.prepareBuffer(imageFile)
            val shape = loaded.processor.tensorShape()

            val inputs = mutableMapOf<String, OnnxTensor>()
            val pixelTensor = OnnxTensor.createTensor(env, buf, shape)
            inputs[loaded.contract.pixelValuesInputName!!] = pixelTensor

            val results = loaded.session.run(inputs)
            pixelTensor.close()

            val outputItems = mutableListOf<InferenceOutput.Item>()

            if (loaded.contract.supportsClassification && loaded.contract.logitsOutputName != null) {
                val logitsTensor = results[loaded.contract.logitsOutputName]
                    ?: results.iterator().next().value
                val logitsArray = (logitsTensor.value as Array<*>).first()
                    .let { it as? FloatArray }
                    ?: run {
                        val v = logitsTensor.value
                        if (v is FloatArray) v else null
                    }

                if (logitsArray != null) {
                    val scores = softmax(logitsArray)
                    val topK = minOf(5, scores.size)
                    val top = scores.indices.sortedByDescending { scores[it] }.take(topK)
                    val labelScores = top.map { idx ->
                        val label = if (idx < loaded.labels.size) loaded.labels[idx] else "class_$idx"
                        InferenceOutput.LabelScore(label, scores[idx].toDouble())
                    }
                    outputItems += InferenceOutput.Item.Labels(labelScores)
                }
                logitsTensor.close()
            }

            if (outputItems.isEmpty() && loaded.contract.supportsEmbedding) {
                val hiddenName = loaded.contract.lastHiddenStateOutputName
                    ?: loaded.contract.poolerOutputName
                val hiddenTensor = hiddenName?.let { results[it] }
                if (hiddenTensor != null) {
                    val embedding = extractEmbedding(hiddenTensor.value)
                    if (embedding != null) {
                        outputItems += InferenceOutput.Item.Embedding(embedding.map { it.toDouble() })
                    }
                    hiddenTensor.close()
                }
            }

            results.close()

            if (outputItems.isEmpty()) {
                outputItems += InferenceOutput.Item.Text("Image processed but no output was extracted.")
            }

            emit(
                InferenceOutput(
                    requestId = input.requestId,
                    phase = InferenceOutput.Phase.Final,
                    items = outputItems,
                    completion = InferenceOutput.CompletionStatus.Terminal(InferenceOutput.FinishReason.COMPLETED),
                )
            )
        } finally {
            cancellations.remove(input.requestId)
            onFinished(input.requestId)
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun cancel(requestId: String) {
        cancellations[requestId]?.set(true)
    }

    // ─── helpers ─────────────────────────────────────────────────────────────

    private fun softmax(logits: FloatArray): FloatArray {
        val max = logits.max()
        val exp = FloatArray(logits.size) { kotlin.math.exp((logits[it] - max).toDouble()).toFloat() }
        val sum = exp.sum()
        return FloatArray(exp.size) { exp[it] / sum }
    }

    private fun extractEmbedding(value: Any?): FloatArray? = when (value) {
        is FloatArray -> value
        is Array<*> -> {
            // [1, seq, hidden] or [1, hidden]
            val first = value.firstOrNull()
            when (first) {
                is FloatArray -> first
                is Array<*> -> (first.firstOrNull() as? FloatArray)
                else -> null
            }
        }
        else -> null
    }

    private fun parseLabels(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(",").map { it.trim() }
    }

    private fun cancelled(requestId: String) = InferenceOutput(
        requestId = requestId,
        phase = InferenceOutput.Phase.Final,
        completion = InferenceOutput.CompletionStatus.Terminal(InferenceOutput.FinishReason.CANCELLED),
    )
}
