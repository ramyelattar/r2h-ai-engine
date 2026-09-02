package io.r2h.engine.nativebridge

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.File
import java.lang.reflect.Array
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min

class YoloOnnxRuntimeAdapter {
    fun runSmoke(modelFile: File): RuntimeProbeResult {
        val started = System.currentTimeMillis()
        if (!modelFile.isFile) {
            return modelMissingProbeResult("ONNX Runtime Android", 0L, modelFile.absolutePath)
        }
        return try {
            val env = OrtEnvironment.getEnvironment()
            OrtSession.SessionOptions().use { options ->
                env.createSession(modelFile.absolutePath, options).use { session ->
                    val inputName = session.inputNames.first()
                    val inputInfo = session.inputInfo[inputName]?.info as? TensorInfo
                        ?: return smokeFailedProbeResult(
                            "ONNX Runtime Android",
                            System.currentTimeMillis() - started,
                            "UNSUPPORTED_INPUT_INFO",
                            "YOLO input '$inputName' is not a tensor.",
                        )
                    val shape = yoloInputShape(inputInfo.shape)
                    val elements = shape.fold(1L) { acc, value -> acc * value }.toInt()
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(elements)), shape).use { input ->
                        session.run(mapOf(inputName to input)).use { outputs ->
                            val preview = "input=$inputName shape=${shape.contentToString()} outputs=${outputs.size()}"
                            if (outputs.size() > 0) {
                                RuntimeProbeResult(
                                    status = RuntimeProbeStatus.AVAILABLE,
                                    backendLabel = "ONNX Runtime Android",
                                    outputPreview = preview,
                                    elapsedMs = System.currentTimeMillis() - started,
                                    errorCode = null,
                                    errorMessage = null,
                                )
                            } else {
                                smokeFailedProbeResult(
                                    "ONNX Runtime Android",
                                    System.currentTimeMillis() - started,
                                    "EMPTY_OUTPUTS",
                                    "YOLO ONNX session returned no outputs.",
                                )
                            }
                        }
                    }
                }
            }
        } catch (missing: UnsatisfiedLinkError) {
            runtimeMissingProbeResult(
                "ONNX Runtime Android",
                System.currentTimeMillis() - started,
                "ONNX_NATIVE_LIBRARY_MISSING",
                missing.message ?: missing.javaClass.simpleName,
            )
        } catch (missing: NoClassDefFoundError) {
            runtimeMissingProbeResult(
                "ONNX Runtime Android",
                System.currentTimeMillis() - started,
                "ONNX_CLASS_MISSING",
                missing.message ?: missing.javaClass.simpleName,
            )
        } catch (e: OrtException) {
            smokeFailedProbeResult(
                "ONNX Runtime Android",
                System.currentTimeMillis() - started,
                e.javaClass.simpleName,
                e.message ?: e.javaClass.name,
            )
        } catch (t: Throwable) {
            smokeFailedProbeResult(
                "ONNX Runtime Android",
                System.currentTimeMillis() - started,
                t.javaClass.simpleName,
                t.message ?: t.javaClass.name,
            )
        }
    }

    fun detectObjects(
        modelFile: File,
        imageFile: File,
        confidenceThreshold: Float = 0.25f,
        iouThreshold: Float = 0.45f,
        maxDetections: Int = 25,
    ): List<YoloDetection> {
        require(modelFile.isFile) { "YOLO model is missing: ${modelFile.absolutePath}" }
        require(imageFile.isFile) { "Image input is missing: ${imageFile.absolutePath}" }

        val source = BitmapFactory.decodeFile(imageFile.absolutePath)
            ?: error("Unable to decode image input: ${imageFile.absolutePath}")

        return try {
            val env = OrtEnvironment.getEnvironment()
            OrtSession.SessionOptions().use { options ->
                env.createSession(modelFile.absolutePath, options).use { session ->
                    val inputName = session.inputNames.first()
                    val inputInfo = session.inputInfo[inputName]?.info as? TensorInfo
                        ?: error("YOLO input '$inputName' is not a tensor.")
                    val shape = yoloInputShape(inputInfo.shape)
                    val inputHeight = shape[2].toInt().coerceAtLeast(1)
                    val inputWidth = shape[3].toInt().coerceAtLeast(1)
                    val prepared = source.toLetterboxedTensor(inputWidth, inputHeight)
                    OnnxTensor.createTensor(env, FloatBuffer.wrap(prepared.tensor), shape).use { input ->
                        session.run(mapOf(inputName to input)).use { outputs ->
                            val tensor = outputs.get(0) as? OnnxTensor
                                ?: error("YOLO output 0 is not an ONNX tensor.")
                            val outputInfo = tensor.info as? TensorInfo
                                ?: error("YOLO output 0 does not expose tensor info.")
                            parseYoloOutput(
                                output = tensor,
                                shape = outputInfo.shape,
                                letterbox = prepared.letterbox,
                                sourceWidth = source.width,
                                sourceHeight = source.height,
                                confidenceThreshold = confidenceThreshold,
                                iouThreshold = iouThreshold,
                                maxDetections = maxDetections,
                            )
                        }
                    }
                }
            }
        } finally {
            source.recycle()
        }
    }

    private fun yoloInputShape(raw: LongArray): LongArray =
        if (raw.size == 4) {
            longArrayOf(
                raw[0].takeIf { it > 0 } ?: 1L,
                raw[1].takeIf { it > 0 } ?: 3L,
                raw[2].takeIf { it > 0 } ?: 640L,
                raw[3].takeIf { it > 0 } ?: 640L,
            )
        } else {
            raw.map { if (it > 0) it else 1L }.toLongArray()
        }

    private fun Bitmap.toLetterboxedTensor(inputWidth: Int, inputHeight: Int): PreparedYoloInput {
        val scale = min(inputWidth / width.toFloat(), inputHeight / height.toFloat())
        val resizedWidth = (width * scale).toInt().coerceAtLeast(1)
        val resizedHeight = (height * scale).toInt().coerceAtLeast(1)
        val padX = (inputWidth - resizedWidth) / 2f
        val padY = (inputHeight - resizedHeight) / 2f

        val letterboxed = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(letterboxed)
        canvas.drawColor(Color.rgb(114, 114, 114))
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(this, null, RectF(padX, padY, padX + resizedWidth, padY + resizedHeight), paint)

        val pixels = IntArray(inputWidth * inputHeight)
        letterboxed.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
        val tensor = FloatArray(1 * 3 * inputWidth * inputHeight)
        val planeSize = inputWidth * inputHeight
        for (i in pixels.indices) {
            val pixel = pixels[i]
            tensor[i] = ((pixel shr 16) and 0xff) / 255f
            tensor[planeSize + i] = ((pixel shr 8) and 0xff) / 255f
            tensor[planeSize * 2 + i] = (pixel and 0xff) / 255f
        }
        letterboxed.recycle()
        return PreparedYoloInput(tensor, Letterbox(scale, padX, padY))
    }

    private fun parseYoloOutput(
        output: OnnxValue,
        shape: LongArray,
        letterbox: Letterbox,
        sourceWidth: Int,
        sourceHeight: Int,
        confidenceThreshold: Float,
        iouThreshold: Float,
        maxDetections: Int,
    ): List<YoloDetection> {
        val floats = flattenFloats(output.value)
        val dims = shape.filter { it > 0 }
        require(dims.size >= 2) { "Unsupported YOLO output shape: ${shape.contentToString()}" }

        val matrixDims = if (dims.size >= 3 && dims[0] == 1L) dims.drop(1) else dims.takeLast(2)
        require(matrixDims.size == 2) { "Unsupported YOLO output matrix: ${shape.contentToString()}" }
        val first = matrixDims[0].toInt()
        val second = matrixDims[1].toInt()
        val channelsFirst = first in 5..512 && second > first
        val attributes = if (channelsFirst) first else second
        val anchors = if (channelsFirst) second else first
        require(attributes >= 5 && anchors > 0) {
            "Unsupported YOLO attributes=$attributes anchors=$anchors shape=${shape.contentToString()}"
        }

        fun value(anchor: Int, attr: Int): Float =
            if (channelsFirst) floats[attr * anchors + anchor] else floats[anchor * attributes + attr]

        val hasObjectness = attributes == 85 || attributes > 84
        val classStart = if (hasObjectness) 5 else 4
        val classCount = (attributes - classStart).coerceAtLeast(1)
        val candidates = mutableListOf<YoloDetection>()
        for (anchor in 0 until anchors) {
            var bestClass = 0
            var bestClassScore = Float.NEGATIVE_INFINITY
            for (classIndex in 0 until classCount) {
                val classScore = value(anchor, classStart + classIndex)
                if (classScore > bestClassScore) {
                    bestClassScore = classScore
                    bestClass = classIndex
                }
            }
            val objectness = if (hasObjectness) value(anchor, 4) else 1f
            val score = objectness * bestClassScore
            if (score < confidenceThreshold) continue

            val cx = value(anchor, 0)
            val cy = value(anchor, 1)
            val w = value(anchor, 2)
            val h = value(anchor, 3)
            val left = ((cx - w / 2f) - letterbox.padX) / letterbox.scale
            val top = ((cy - h / 2f) - letterbox.padY) / letterbox.scale
            val right = ((cx + w / 2f) - letterbox.padX) / letterbox.scale
            val bottom = ((cy + h / 2f) - letterbox.padY) / letterbox.scale
            candidates += YoloDetection(
                label = cocoLabels.getOrElse(bestClass) { "class_$bestClass" },
                score = score.coerceIn(0f, 1f),
                left = left.coerceIn(0f, sourceWidth.toFloat()),
                top = top.coerceIn(0f, sourceHeight.toFloat()),
                right = right.coerceIn(0f, sourceWidth.toFloat()),
                bottom = bottom.coerceIn(0f, sourceHeight.toFloat()),
            )
        }
        return nonMaximumSuppression(candidates, iouThreshold).take(maxDetections)
    }

    private fun nonMaximumSuppression(detections: List<YoloDetection>, iouThreshold: Float): List<YoloDetection> {
        val selected = mutableListOf<YoloDetection>()
        val remaining = detections.sortedByDescending { it.score }.toMutableList()
        while (remaining.isNotEmpty()) {
            val current = remaining.removeAt(0)
            selected += current
            remaining.removeAll { other -> other.label == current.label && current.iou(other) > iouThreshold }
        }
        return selected
    }

    private fun YoloDetection.iou(other: YoloDetection): Float {
        val x1 = max(left, other.left)
        val y1 = max(top, other.top)
        val x2 = min(right, other.right)
        val y2 = min(bottom, other.bottom)
        val intersection = max(0f, x2 - x1) * max(0f, y2 - y1)
        val areaA = max(0f, right - left) * max(0f, bottom - top)
        val areaB = max(0f, other.right - other.left) * max(0f, other.bottom - other.top)
        val union = areaA + areaB - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun flattenFloats(value: Any?): FloatArray {
        val out = ArrayList<Float>()
        fun visit(node: Any?) {
            when (node) {
                is Float -> out += node
                is Double -> out += node.toFloat()
                is Number -> out += node.toFloat()
                null -> Unit
                else -> {
                    val type = node.javaClass
                    require(type.isArray) { "Unsupported YOLO output value type: ${type.name}" }
                    for (i in 0 until Array.getLength(node)) {
                        visit(Array.get(node, i))
                    }
                }
            }
        }
        visit(value)
        return out.toFloatArray()
    }

    private data class PreparedYoloInput(
        val tensor: FloatArray,
        val letterbox: Letterbox,
    )

    private data class Letterbox(
        val scale: Float,
        val padX: Float,
        val padY: Float,
    )

    private companion object {
        val cocoLabels = listOf(
            "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck", "boat",
            "traffic light", "fire hydrant", "stop sign", "parking meter", "bench", "bird", "cat",
            "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra", "giraffe", "backpack",
            "umbrella", "handbag", "tie", "suitcase", "frisbee", "skis", "snowboard", "sports ball",
            "kite", "baseball bat", "baseball glove", "skateboard", "surfboard", "tennis racket",
            "bottle", "wine glass", "cup", "fork", "knife", "spoon", "bowl", "banana", "apple",
            "sandwich", "orange", "broccoli", "carrot", "hot dog", "pizza", "donut", "cake",
            "chair", "couch", "potted plant", "bed", "dining table", "toilet", "tv", "laptop",
            "mouse", "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
            "refrigerator", "book", "clock", "vase", "scissors", "teddy bear", "hair drier",
            "toothbrush",
        )
    }
}

data class YoloDetection(
    val label: String,
    val score: Float,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)
