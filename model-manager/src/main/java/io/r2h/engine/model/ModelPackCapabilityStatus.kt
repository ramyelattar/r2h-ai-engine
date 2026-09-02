package io.r2h.engine.model

enum class ModelPackCapabilityKey {
    QWEN_TEXT,
    QWEN_IMAGE,
    YOLO_ONNX,
    YOLO_NCNN,
    RERANKER,
    SPEECH_TO_TEXT,
    TEXT_TO_SPEECH,
}

object ModelPackCapabilityState {
    const val MODEL_PRESENT_NATIVE_RUNTIME_PRESENT_SMOKE_PENDING =
        "MODEL_PRESENT_NATIVE_RUNTIME_PRESENT_SMOKE_PENDING"
    const val MODEL_PRESENT_MMPROJ_PRESENT_NATIVE_IMAGE_RUNTIME_MISSING =
        "MODEL_PRESENT_MMPROJ_PRESENT_NATIVE_IMAGE_RUNTIME_MISSING"
    const val MODEL_PRESENT_RUNTIME_MISSING = "MODEL_PRESENT_RUNTIME_MISSING"
    const val MODEL_MISSING = "MODEL_MISSING"
    const val AVAILABLE = "AVAILABLE"
    const val SMOKE_FAILED = "SMOKE_FAILED"
}

object ModelPackCapabilityStatusMapper {
    fun baselineStatuses(manifest: ModelPackManifest): Map<ModelPackCapabilityKey, String> =
        mapOf(
            ModelPackCapabilityKey.QWEN_TEXT to statusIfArtifactsPresent(
                manifest,
                listOf(QWEN_TEXT_GGUF_ARTIFACT_PATH),
                ModelPackCapabilityState.MODEL_PRESENT_NATIVE_RUNTIME_PRESENT_SMOKE_PENDING,
            ),
            ModelPackCapabilityKey.QWEN_IMAGE to statusIfArtifactsPresent(
                manifest,
                listOf(
                    QWEN_TEXT_GGUF_ARTIFACT_PATH,
                    QWEN_MMPROJ_ARTIFACT_PATH,
                ),
                ModelPackCapabilityState.MODEL_PRESENT_MMPROJ_PRESENT_NATIVE_IMAGE_RUNTIME_MISSING,
            ),
            ModelPackCapabilityKey.YOLO_ONNX to statusIfArtifactsPresent(
                manifest,
                listOf(YOLO_ONNX_ARTIFACT_PATH),
                ModelPackCapabilityState.MODEL_PRESENT_RUNTIME_MISSING,
            ),
            ModelPackCapabilityKey.YOLO_NCNN to statusIfArtifactsPresent(
                manifest,
                listOf(
                    YOLO_NCNN_BIN_ARTIFACT_PATH,
                    YOLO_NCNN_PARAM_ARTIFACT_PATH,
                    "models/detection/yolo11n_ncnn/metadata.yaml",
                ),
                ModelPackCapabilityState.MODEL_PRESENT_RUNTIME_MISSING,
            ),
            ModelPackCapabilityKey.RERANKER to statusIfArtifactsPresent(
                manifest,
                listOf(RERANKER_ONNX_ARTIFACT_PATH),
                ModelPackCapabilityState.MODEL_PRESENT_RUNTIME_MISSING,
            ),
            ModelPackCapabilityKey.SPEECH_TO_TEXT to statusIfArtifactsPresent(
                manifest,
                listOf("models/stt/faster-whisper-tiny/model.bin"),
                ModelPackCapabilityState.MODEL_PRESENT_RUNTIME_MISSING,
            ),
            ModelPackCapabilityKey.TEXT_TO_SPEECH to statusIfArtifactsPresent(
                manifest,
                listOf(
                    "models/tts/piper/voices/ar_JO-kareem-medium.onnx",
                    "models/tts/piper/voices/en_US-lessac-high.onnx",
                ),
                ModelPackCapabilityState.MODEL_PRESENT_RUNTIME_MISSING,
            ),
        )

    fun qwenTextAfterSmoke(probeStatusName: String): String =
        when (probeStatusName) {
            "AVAILABLE" -> ModelPackCapabilityState.AVAILABLE
            "SMOKE_FAILED" -> ModelPackCapabilityState.SMOKE_FAILED
            else -> ModelPackCapabilityState.MODEL_PRESENT_NATIVE_RUNTIME_PRESENT_SMOKE_PENDING
        }

    private fun statusIfArtifactsPresent(
        manifest: ModelPackManifest,
        requiredArtifactPaths: List<String>,
        presentStatus: String,
    ): String =
        if (requiredArtifactPaths.all { manifest.findArtifact(it) != null }) {
            presentStatus
        } else {
            ModelPackCapabilityState.MODEL_MISSING
        }
}
