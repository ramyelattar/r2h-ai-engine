package io.r2h.engine.model

import android.content.Context
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private const val MODEL_PACK_ASSET_ROOT = "model-pack"
const val QWEN_TEXT_GGUF_ARTIFACT_PATH = "models/qwen2vl/Qwen2-VL-2B-Instruct-Q4_K_M.gguf"
const val QWEN_MMPROJ_ARTIFACT_PATH = "models/qwen2vl/mmproj-Qwen2-VL-2B-Instruct-Q8_0.gguf"
const val YOLO_ONNX_ARTIFACT_PATH = "models/detection/yolo11n.onnx"
const val YOLO_NCNN_BIN_ARTIFACT_PATH = "models/detection/yolo11n_ncnn/model.ncnn.bin"
const val YOLO_NCNN_PARAM_ARTIFACT_PATH = "models/detection/yolo11n_ncnn/model.ncnn.param"
const val RERANKER_ONNX_ARTIFACT_PATH = "models/reranker/model.onnx"
const val RERANKER_TOKENIZER_JSON_ARTIFACT_PATH = "models/reranker/tokenizer.json"

sealed interface ModelPackExtractionResult {
    data class Success(
        val outputFiles: List<File>,
        val copiedArtifactPaths: List<String>,
        val skippedArtifactPaths: List<String>,
    ) : ModelPackExtractionResult

    data class Failed(
        val artifactPath: String,
        val reason: String,
        val verificationResult: ModelPackVerificationResult? = null,
    ) : ModelPackExtractionResult
}

class ModelPackExtractor(
    private val context: Context,
    private val verifier: ModelPackVerifier = ModelPackVerifier(),
) {
    private val outputRoot = File(context.filesDir, MODEL_PACK_ASSET_ROOT)

    fun extractQwenTextGguf(manifest: ModelPackManifest): ModelPackExtractionResult =
        extractSelected(manifest, listOf(QWEN_TEXT_GGUF_ARTIFACT_PATH))

    fun extractedFileFor(artifactPath: String): File =
        File(outputRoot, artifactPath)

    fun extractSelected(
        manifest: ModelPackManifest,
        artifactPaths: List<String>,
    ): ModelPackExtractionResult {
        val copied = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val outputs = mutableListOf<File>()

        for (artifactPath in artifactPaths) {
            val artifact = manifest.findArtifact(artifactPath)
                ?: return ModelPackExtractionResult.Failed(
                    artifactPath = artifactPath,
                    reason = "ARTIFACT_NOT_IN_MANIFEST: $artifactPath",
                )

            val target = safeTargetFile(artifact.path)
                ?: return ModelPackExtractionResult.Failed(
                    artifactPath = artifact.path,
                    reason = "UNSAFE_ARTIFACT_PATH: ${artifact.path}",
                )

            val existingVerification = verifier.verify(target, artifact)
            if (existingVerification is ModelPackVerificationResult.Verified) {
                skipped.add(artifact.path)
                outputs.add(target)
                continue
            }

            val copyResult = copyAssetAtomically(artifact, target)
            if (copyResult != null) {
                return ModelPackExtractionResult.Failed(
                    artifactPath = artifact.path,
                    reason = copyResult,
                )
            }

            val verification = verifier.verify(target, artifact)
            if (verification !is ModelPackVerificationResult.Verified) {
                target.delete()
                return ModelPackExtractionResult.Failed(
                    artifactPath = artifact.path,
                    reason = verification.reason,
                    verificationResult = verification,
                )
            }

            copied.add(artifact.path)
            outputs.add(target)
        }

        return ModelPackExtractionResult.Success(
            outputFiles = outputs,
            copiedArtifactPaths = copied,
            skippedArtifactPaths = skipped,
        )
    }

    private fun safeTargetFile(artifactPath: String): File? {
        val root = outputRoot.canonicalFile
        val target = File(root, artifactPath).canonicalFile
        return if (target.startsWith(root)) target else null
    }

    private fun copyAssetAtomically(artifact: ModelPackArtifact, target: File): String? {
        val parent = target.parentFile ?: return "NO_TARGET_PARENT: ${target.absolutePath}"
        if (!parent.exists() && !parent.mkdirs()) {
            return "CREATE_TARGET_DIR_FAILED: ${parent.absolutePath}"
        }

        val tmp = File(parent, "${target.name}.tmp-${System.nanoTime()}")
        return try {
            context.assets.open("$MODEL_PACK_ASSET_ROOT/${artifact.path}").use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }

            moveIntoPlace(tmp, target)
            null
        } catch (t: Throwable) {
            tmp.delete()
            "COPY_FAILED: ${artifact.path} ${t.message ?: t.javaClass.simpleName}"
        }
    }

    private fun moveIntoPlace(tmp: File, target: File) {
        try {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (atomicFailure: Throwable) {
            Files.move(
                tmp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }
}
