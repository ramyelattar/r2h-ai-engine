package io.r2h.engine.runtime

import android.content.Context
import io.r2h.engine.model.ModelPackExtractionResult
import io.r2h.engine.model.ModelPackExtractor
import io.r2h.engine.model.ModelPackManifestReader
import io.r2h.engine.model.ModelPackVerificationResult
import io.r2h.engine.model.ModelPackVerifier
import io.r2h.engine.model.QWEN_MMPROJ_ARTIFACT_PATH
import io.r2h.engine.model.QWEN_TEXT_GGUF_ARTIFACT_PATH
import io.r2h.engine.model.RERANKER_ONNX_ARTIFACT_PATH
import io.r2h.engine.model.RERANKER_TOKENIZER_JSON_ARTIFACT_PATH
import io.r2h.engine.model.YOLO_NCNN_BIN_ARTIFACT_PATH
import io.r2h.engine.model.YOLO_NCNN_PARAM_ARTIFACT_PATH
import io.r2h.engine.model.YOLO_ONNX_ARTIFACT_PATH
import io.r2h.engine.nativebridge.QwenImageRuntimeAdapter
import io.r2h.engine.nativebridge.QwenTextRuntimeAdapter
import io.r2h.engine.nativebridge.RerankerOnnxRuntimeAdapter
import io.r2h.engine.nativebridge.RuntimeProbeResult
import io.r2h.engine.nativebridge.RuntimeProbeStatus
import io.r2h.engine.nativebridge.SherpaOnnxTtsRuntimeAdapter
import io.r2h.engine.nativebridge.WhisperCppRuntimeAdapter
import io.r2h.engine.nativebridge.YoloNcnnRuntimeAdapter
import io.r2h.engine.nativebridge.YoloOnnxRuntimeAdapter
import java.io.File

class RuntimeSmokeTestRunner(
    private val context: Context,
    private val manifestReader: ModelPackManifestReader = ModelPackManifestReader(context),
    private val extractor: ModelPackExtractor = ModelPackExtractor(context),
    private val verifier: ModelPackVerifier = ModelPackVerifier(),
    private val qwenTextRuntimeAdapter: QwenTextRuntimeAdapter = QwenTextRuntimeAdapter(),
    private val yoloOnnxRuntimeAdapter: YoloOnnxRuntimeAdapter = YoloOnnxRuntimeAdapter(),
    private val rerankerOnnxRuntimeAdapter: RerankerOnnxRuntimeAdapter = RerankerOnnxRuntimeAdapter(),
    private val yoloNcnnRuntimeAdapter: YoloNcnnRuntimeAdapter = YoloNcnnRuntimeAdapter(),
    private val whisperCppRuntimeAdapter: WhisperCppRuntimeAdapter = WhisperCppRuntimeAdapter(),
    private val sherpaOnnxTtsRuntimeAdapter: SherpaOnnxTtsRuntimeAdapter = SherpaOnnxTtsRuntimeAdapter(),
    private val qwenImageRuntimeAdapter: QwenImageRuntimeAdapter = QwenImageRuntimeAdapter(),
) {
    fun runQwenTextSmoke(): RuntimeProbeResult =
        withExtractedArtifacts("QWEN_TEXT", "qwen-text-smoke-result.json", listOf(QWEN_TEXT_GGUF_ARTIFACT_PATH)) {
            qwenTextRuntimeAdapter.runSmoke(extractor.extractedFileFor(QWEN_TEXT_GGUF_ARTIFACT_PATH).absolutePath)
        }

    fun runYoloOnnxSmoke(): RuntimeProbeResult =
        withExtractedArtifacts("YOLO_ONNX", "yolo-onnx-smoke-result.json", listOf(YOLO_ONNX_ARTIFACT_PATH)) {
            yoloOnnxRuntimeAdapter.runSmoke(extractor.extractedFileFor(YOLO_ONNX_ARTIFACT_PATH))
        }

    fun runRerankerSmoke(): RuntimeProbeResult =
        withExtractedArtifacts(
            "RERANKER",
            "reranker-smoke-result.json",
            listOf(RERANKER_ONNX_ARTIFACT_PATH, RERANKER_TOKENIZER_JSON_ARTIFACT_PATH),
        ) {
            rerankerOnnxRuntimeAdapter.runSmoke(
                modelFile = extractor.extractedFileFor(RERANKER_ONNX_ARTIFACT_PATH),
                tokenizerJson = extractor.extractedFileFor(RERANKER_TOKENIZER_JSON_ARTIFACT_PATH),
            )
        }

    fun runYoloNcnnSmoke(): RuntimeProbeResult =
        withExtractedArtifacts(
            "YOLO_NCNN",
            "yolo-ncnn-smoke-result.json",
            listOf(YOLO_NCNN_PARAM_ARTIFACT_PATH, YOLO_NCNN_BIN_ARTIFACT_PATH),
        ) {
            yoloNcnnRuntimeAdapter.runSmoke(
                paramFile = extractor.extractedFileFor(YOLO_NCNN_PARAM_ARTIFACT_PATH),
                binFile = extractor.extractedFileFor(YOLO_NCNN_BIN_ARTIFACT_PATH),
            )
        }

    fun runSttSmoke(): RuntimeProbeResult =
        withDebugAssets("SPEECH_TO_TEXT", "stt-smoke-result.json") {
            val modelFile = copyAssetToFiles("runtime-smoke/whisper/ggml-tiny.bin", "runtime-smoke/whisper/ggml-tiny.bin")
            val wavFile = copyAssetToFiles("runtime-smoke/whisper/jfk.wav", "runtime-smoke/whisper/jfk.wav")
            whisperCppRuntimeAdapter.runSmoke(modelFile, wavFile)
        }

    fun runTtsSmoke(): RuntimeProbeResult =
        withDebugAssets("TEXT_TO_SPEECH", "tts-smoke-result.json") {
            val english = copyAssetDirectory(
                "runtime-smoke/tts/vits-piper-en_US-lessac-low-int8",
                "runtime-smoke/tts/vits-piper-en_US-lessac-low-int8",
            )
            val arabic = copyAssetDirectory(
                "runtime-smoke/tts/vits-piper-ar_JO-kareem-medium-int8",
                "runtime-smoke/tts/vits-piper-ar_JO-kareem-medium-int8",
            )
            sherpaOnnxTtsRuntimeAdapter.runSmoke(
                englishModelDir = english,
                arabicModelDir = arabic,
                outputWav = File(context.filesDir, "runtime-integration/evidence/tts-smoke-output.wav"),
            )
        }

    fun runQwenImageSmoke(): RuntimeProbeResult =
        withExtractedArtifacts(
            "QWEN_IMAGE",
            "qwen-image-smoke-result.json",
            listOf(QWEN_TEXT_GGUF_ARTIFACT_PATH, QWEN_MMPROJ_ARTIFACT_PATH),
        ) {
            val image = copyAssetToFiles("runtime-smoke/qwen-image/test-1.jpeg", "runtime-smoke/qwen-image/test-1.jpeg")
            qwenImageRuntimeAdapter.runSmoke(
                modelFile = extractor.extractedFileFor(QWEN_TEXT_GGUF_ARTIFACT_PATH),
                mmprojFile = extractor.extractedFileFor(QWEN_MMPROJ_ARTIFACT_PATH),
                imageFile = image,
            )
        }

    fun evidenceFile(fileName: String = "qwen-text-smoke-result.json"): File =
        File(context.filesDir, "runtime-integration/evidence/$fileName")

    private fun withExtractedArtifacts(
        capability: String,
        evidenceFileName: String,
        artifactPaths: List<String>,
        smoke: () -> RuntimeProbeResult,
    ): RuntimeProbeResult {
        val started = System.currentTimeMillis()
        val result = try {
            val manifest = manifestReader.read()
            val missing = artifactPaths.firstOrNull { manifest.findArtifact(it) == null }
            if (missing != null) {
                probe(
                    RuntimeProbeStatus.MODEL_MISSING,
                    "model-pack",
                    "",
                    System.currentTimeMillis() - started,
                    "ARTIFACT_NOT_IN_MANIFEST",
                    missing,
                )
            } else {
                when (val extraction = extractor.extractSelected(manifest, artifactPaths)) {
                    is ModelPackExtractionResult.Failed ->
                        extraction.toProbe(System.currentTimeMillis() - started)
                    is ModelPackExtractionResult.Success -> {
                        val failedVerification = artifactPaths.firstNotNullOfOrNull { path ->
                            val artifact = manifest.findArtifact(path) ?: return@firstNotNullOfOrNull null
                            val verification = verifier.verify(extractor.extractedFileFor(path), artifact)
                            if (verification is ModelPackVerificationResult.Verified) null else verification
                        }
                        if (failedVerification != null) {
                            verificationFailureProbe(failedVerification, System.currentTimeMillis() - started)
                        } else {
                            smoke()
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            probe(
                RuntimeProbeStatus.SMOKE_FAILED,
                "runtime-smoke-runner",
                "",
                System.currentTimeMillis() - started,
                t.javaClass.simpleName,
                t.message ?: t.javaClass.name,
            )
        }
        writeEvidence(capability, evidenceFileName, result, artifactPaths)
        return result
    }

    private fun withDebugAssets(
        capability: String,
        evidenceFileName: String,
        smoke: () -> RuntimeProbeResult,
    ): RuntimeProbeResult {
        val started = System.currentTimeMillis()
        val result = try {
            smoke()
        } catch (t: Throwable) {
            probe(
                RuntimeProbeStatus.SMOKE_FAILED,
                "runtime-smoke-runner",
                "",
                System.currentTimeMillis() - started,
                t.javaClass.simpleName,
                t.message ?: t.javaClass.name,
            )
        }
        writeEvidence(capability, evidenceFileName, result, emptyList())
        return result
    }

    private fun ModelPackExtractionResult.Failed.toProbe(elapsedMs: Long): RuntimeProbeResult {
        val status = if (reason.startsWith("ARTIFACT_NOT_IN_MANIFEST")) {
            RuntimeProbeStatus.MODEL_MISSING
        } else {
            RuntimeProbeStatus.SMOKE_FAILED
        }
        return probe(
            status,
            "model-pack",
            "",
            elapsedMs,
            reason.substringBefore(':'),
            reason,
        )
    }

    private fun verificationFailureProbe(result: ModelPackVerificationResult, elapsedMs: Long): RuntimeProbeResult =
        probe(
            RuntimeProbeStatus.SMOKE_FAILED,
            "model-pack",
            "",
            elapsedMs,
            result.reason.substringBefore(':'),
            result.reason,
        )

    private fun probe(
        status: RuntimeProbeStatus,
        backendLabel: String,
        outputPreview: String,
        elapsedMs: Long,
        errorCode: String?,
        errorMessage: String?,
    ): RuntimeProbeResult =
        RuntimeProbeResult(status, backendLabel, outputPreview, elapsedMs, errorCode, errorMessage)

    private fun writeEvidence(
        capability: String,
        evidenceFileName: String,
        result: RuntimeProbeResult,
        artifactPaths: List<String>,
    ) {
        val file = evidenceFile(evidenceFileName)
        file.parentFile?.mkdirs()
        file.writeText(result.toJson(capability, artifactPaths), Charsets.UTF_8)
    }

    private fun copyAssetToFiles(assetPath: String, outputPath: String): File {
        val output = File(context.filesDir, outputPath)
        output.parentFile?.mkdirs()
        context.assets.open(assetPath).use { input ->
            output.outputStream().use { outputStream ->
                input.copyTo(outputStream, bufferSize = 256 * 1024)
                outputStream.fd.sync()
            }
        }
        return output
    }

    private fun copyAssetDirectory(assetPath: String, outputPath: String): File {
        val output = File(context.filesDir, outputPath)
        output.mkdirs()
        copyAssetDirectoryRecursive(assetPath, output)
        return output
    }

    private fun copyAssetDirectoryRecursive(assetPath: String, output: File) {
        val entries = context.assets.list(assetPath).orEmpty()
        if (entries.isEmpty()) {
            output.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                output.outputStream().use { outputStream -> input.copyTo(outputStream) }
            }
            return
        }
        output.mkdirs()
        entries.forEach { child ->
            copyAssetDirectoryRecursive("$assetPath/$child", File(output, child))
        }
    }

    private fun RuntimeProbeResult.toJson(capability: String, artifactPaths: List<String>): String =
        """
        {
          "capability": "$capability",
          "status": "${status.name}",
          "backendLabel": "${backendLabel.jsonEscape()}",
          "outputPreview": "${outputPreview.jsonEscape()}",
          "elapsedMs": $elapsedMs,
          "errorCode": ${errorCode?.let { "\"${it.jsonEscape()}\"" } ?: "null"},
          "errorMessage": ${errorMessage?.let { "\"${it.jsonEscape()}\"" } ?: "null"},
          "artifactPaths": [${artifactPaths.joinToString(", ") { "\"${it.jsonEscape()}\"" }}],
          "evidenceKind": "DEVICE_RUNTIME_SMOKE"
        }
        """.trimIndent()

    private fun String.jsonEscape(): String =
        buildString {
            this@jsonEscape.forEach { c ->
                when (c) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(c)
                }
            }
        }
}
