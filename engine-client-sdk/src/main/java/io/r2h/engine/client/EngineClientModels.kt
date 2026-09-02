package io.r2h.engine.client

import io.r2h.engine.api.model.EngineError
import io.r2h.engine.api.model.FinishReason
import io.r2h.engine.api.model.ModalInferenceResult

object EngineContract {
    const val ENGINE_PACKAGE: String = "io.r2h.engine"
    const val ENGINE_SERVICE_CLASS: String = "io.r2h.engine.EngineService"
    const val BIND_PERMISSION: String = "r2h.permission.BIND_ENGINE"
    const val API_VERSION: Int = 3
}

enum class EngineConnectionStatus {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ENGINE_NOT_INSTALLED,
    PERMISSION_MISSING,
    API_VERSION_MISMATCH,
    BINDER_DIED,
    ERROR,
}

data class EngineConnectionState(
    val status: EngineConnectionStatus,
    val humanReadableMessage: String,
    val technicalReason: String? = null,
    val timestamp: Long = System.currentTimeMillis(),
) {
    companion object {
        fun disconnected(reason: String? = null): EngineConnectionState =
            EngineConnectionState(
                status = EngineConnectionStatus.DISCONNECTED,
                humanReadableMessage = "AI Engine is disconnected.",
                technicalReason = reason,
            )
    }
}

enum class CapabilityAvailability {
    AVAILABLE,
    UNAVAILABLE,
    MODEL_MISSING,
    RUNTIME_LOADING,
    RUNTIME_FAILED,
}

data class EngineCapabilityDetail(
    val availability: CapabilityAvailability,
    val activeModelId: String? = null,
    val modality: String,
    val taskType: String,
    val exactReason: String,
    val technicalReason: String? = null,
) {
    val available: Boolean = availability == CapabilityAvailability.AVAILABLE
}

data class EngineCapabilityState(
    val textGeneration: EngineCapabilityDetail,
    val imageUnderstanding: EngineCapabilityDetail,
    val ocr: EngineCapabilityDetail,
    val multimodal: EngineCapabilityDetail,
    val imageClassification: EngineCapabilityDetail,
    val audioUnderstanding: EngineCapabilityDetail,
    val speechToText: EngineCapabilityDetail,
    val textToSpeech: EngineCapabilityDetail,
    val videoObjectDetection: EngineCapabilityDetail,
    val videoFullAnalysis: EngineCapabilityDetail,
    val embeddings: EngineCapabilityDetail,
    val toolsAgents: EngineCapabilityDetail,
    val reranking: EngineCapabilityDetail,
    val modelMissing: Boolean,
    val runtimeLoading: Boolean,
    val runtimeFailed: Boolean,
    val unavailableReason: String? = null,
) {
    companion object {
        fun unavailable(reason: String, technicalReason: String? = null): EngineCapabilityState {
            fun detail(modality: String, taskType: String) = EngineCapabilityDetail(
                availability = CapabilityAvailability.UNAVAILABLE,
                modality = modality,
                taskType = taskType,
                exactReason = reason,
                technicalReason = technicalReason,
            )
            return EngineCapabilityState(
                textGeneration = detail(EngineModalities.TEXT, EngineTaskTypes.GENERATE_TEXT),
                imageUnderstanding = detail(EngineModalities.IMAGE, EngineTaskTypes.IMAGE_UNDERSTANDING),
                ocr = detail(EngineModalities.IMAGE, EngineTaskTypes.OCR),
                multimodal = detail(EngineModalities.IMAGE, EngineTaskTypes.IMAGE_TEXT_MULTIMODAL),
                imageClassification = detail(EngineModalities.IMAGE, EngineTaskTypes.CLASSIFICATION),
                audioUnderstanding = detail(EngineModalities.AUDIO, EngineTaskTypes.AUDIO_TAGGING),
                speechToText = detail(EngineModalities.AUDIO, EngineTaskTypes.SPEECH_TO_TEXT),
                textToSpeech = detail(EngineModalities.AUDIO, EngineTaskTypes.TEXT_TO_SPEECH),
                videoObjectDetection = detail(EngineModalities.VIDEO, EngineTaskTypes.VIDEO_OBJECT_DETECTION),
                videoFullAnalysis = detail(EngineModalities.VIDEO, EngineTaskTypes.VIDEO_FULL_ANALYSIS),
                embeddings = detail(EngineModalities.TEXT, EngineTaskTypes.EMBEDDING),
                toolsAgents = detail(EngineModalities.TEXT, EngineTaskTypes.TOOLS_AGENTS),
                reranking = detail(EngineModalities.TEXT, EngineTaskTypes.RERANKING),
                modelMissing = true,
                runtimeLoading = false,
                runtimeFailed = false,
                unavailableReason = reason,
            )
        }
    }
}

object EngineModalities {
    const val TEXT: String = "TEXT"
    const val IMAGE: String = "IMAGE"
    const val AUDIO: String = "AUDIO"
    const val VIDEO: String = "VIDEO"
    const val MULTIMODAL: String = "MULTIMODAL"
}

object EngineTaskTypes {
    const val GENERATE_TEXT: String = "GENERATE_TEXT"
    const val IMAGE_UNDERSTANDING: String = "IMAGE_UNDERSTANDING"
    const val IMAGE_TEXT_MULTIMODAL: String = "IMAGE_TEXT_MULTIMODAL"
    const val OCR: String = "OCR"
    const val CLASSIFICATION: String = "CLASSIFICATION"
    const val AUDIO_ANALYSIS: String = "AUDIO_ANALYSIS"
    const val AUDIO_TAGGING: String = "AUDIO_TAGGING"
    const val SPEECH_TO_TEXT: String = "SPEECH_TO_TEXT"
    const val TEXT_TO_SPEECH: String = "TEXT_TO_SPEECH"
    const val VIDEO_OBJECT_DETECTION: String = "OBJECT_DETECTION"
    const val VIDEO_FULL_ANALYSIS: String = "VIDEO_FULL_ANALYSIS"
    const val EMBEDDING: String = "EMBEDDING"
    const val TOOLS_AGENTS: String = "TOOLS_AGENTS"
    const val RERANKING: String = "RERANKING"
}

enum class EngineClientErrorCode {
    ENGINE_UNAVAILABLE,
    PERMISSION_MISSING,
    API_VERSION_MISMATCH,
    CAPABILITY_UNAVAILABLE,
    MODEL_MISSING,
    RUNTIME_LOADING,
    RUNTIME_FAILED,
    ENGINE_ERROR,
    TIMEOUT,
    CANCELLED,
    BINDER_DIED,
    INTERNAL_ERROR,
    RATE_LIMITED,
}

data class EngineClientError(
    val code: EngineClientErrorCode,
    val message: String,
    val technicalReason: String? = null,
    val engineError: EngineError? = null,
)

sealed class EngineClientResult<out T> {
    data class Success<T>(val value: T) : EngineClientResult<T>()
    data class Failure(val error: EngineClientError) : EngineClientResult<Nothing>()
}

data class GenerateTextParams(
    val maxTokens: Int? = null,
    val temperature: Float? = null,
    val systemPrompt: String? = null,
    val streaming: Boolean = false,
)

data class TextGenerationResult(
    val requestId: String,
    val text: String,
    val finishReason: FinishReason,
    val promptTokenCount: Int,
    val generatedTokenCount: Int,
)

data class ModalInferenceResponse(
    val requestId: String,
    val frames: List<ModalInferenceResult>,
) {
    val textValue: String?
        get() = frames.mapNotNull { it.textValue }.joinToString(separator = "").ifBlank { null }
}

data class AgentRunResult(
    val requestId: String,
    val decisionType: String,
    val textResponse: String,
    val reasoning: String,
    val toolTraceSummary: String,
)

enum class EngineUiStatus {
    READY,
    CONNECTING,
    ENGINE_MISSING,
    PERMISSION_MISSING,
    API_MISMATCH,
    CAPABILITY_UNAVAILABLE,
    MODEL_MISSING,
    RUNTIME_LOADING,
    RUNTIME_FAILED,
    INFERENCE_FAILED,
    BINDER_DIED_RECONNECTING,
    DISCONNECTED,
}

data class EngineUiState(
    val status: EngineUiStatus,
    val title: String,
    val message: String,
    val technicalReason: String? = null,
)
