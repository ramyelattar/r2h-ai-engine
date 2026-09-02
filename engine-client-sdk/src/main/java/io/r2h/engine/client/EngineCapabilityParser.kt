package io.r2h.engine.client

import io.r2h.engine.api.model.EngineStateCode
import io.r2h.engine.api.model.EngineTruthSnapshot
import io.r2h.engine.api.model.ModelLoadStatus
import io.r2h.engine.api.model.ModelRuntimeState

object EngineCapabilityParser {
    fun parse(truth: EngineTruthSnapshot, loadStatus: ModelLoadStatus? = null): EngineCapabilityState {
        val runtimeLoading = truth.models.any { it.runtimeState == ModelRuntimeState.LOADING } ||
            loadStatus?.state?.let { state ->
                state.equals("Loading", ignoreCase = true) ||
                    state.equals("Preparing", ignoreCase = true)
            } == true
        val runtimeFailed = truth.runtime.engineState == EngineStateCode.UNAVAILABLE ||
            truth.models.any { it.runtimeState == ModelRuntimeState.FAILED } ||
            truth.runtime.lastError != null
        val modelMissing = truth.models.none { it.ready } && truth.modalityState.none { it.activeModelId != null }
        val unavailableReason = when {
            runtimeFailed -> truth.runtime.lastError?.message ?: "Engine runtime is unavailable or failed."
            runtimeLoading -> "Engine runtime is loading."
            modelMissing -> "No ready model is available for the requested capability."
            else -> null
        }
        return EngineCapabilityState(
            textGeneration = detail(truth, EngineModalities.TEXT, EngineTaskTypes.GENERATE_TEXT, runtimeLoading, runtimeFailed, modelMissing),
            imageUnderstanding = detail(truth, EngineModalities.IMAGE, EngineTaskTypes.IMAGE_UNDERSTANDING, runtimeLoading, runtimeFailed, modelMissing),
            ocr = detail(truth, EngineModalities.IMAGE, EngineTaskTypes.OCR, runtimeLoading, runtimeFailed, modelMissing),
            multimodal = detail(truth, EngineModalities.IMAGE, EngineTaskTypes.IMAGE_TEXT_MULTIMODAL, runtimeLoading, runtimeFailed, modelMissing),
            imageClassification = detail(truth, EngineModalities.IMAGE, EngineTaskTypes.CLASSIFICATION, runtimeLoading, runtimeFailed, modelMissing),
            audioUnderstanding = detailWithAliases(truth, EngineModalities.AUDIO, listOf(EngineTaskTypes.AUDIO_ANALYSIS, EngineTaskTypes.AUDIO_TAGGING), runtimeLoading, runtimeFailed, modelMissing),
            speechToText = detail(truth, EngineModalities.AUDIO, EngineTaskTypes.SPEECH_TO_TEXT, runtimeLoading, runtimeFailed, modelMissing),
            textToSpeech = detail(truth, EngineModalities.AUDIO, EngineTaskTypes.TEXT_TO_SPEECH, runtimeLoading, runtimeFailed, modelMissing),
            videoObjectDetection = detail(truth, EngineModalities.VIDEO, EngineTaskTypes.VIDEO_OBJECT_DETECTION, runtimeLoading, runtimeFailed, modelMissing),
            videoFullAnalysis = detail(truth, EngineModalities.VIDEO, EngineTaskTypes.VIDEO_FULL_ANALYSIS, runtimeLoading, runtimeFailed, modelMissing),
            embeddings = detail(truth, EngineModalities.TEXT, EngineTaskTypes.EMBEDDING, runtimeLoading, runtimeFailed, modelMissing),
            toolsAgents = detail(truth, EngineModalities.TEXT, EngineTaskTypes.GENERATE_TEXT, runtimeLoading, runtimeFailed, modelMissing).copy(
                taskType = EngineTaskTypes.TOOLS_AGENTS,
                exactReason = "TOOLS_AGENTS uses the local TEXT planner and tool orchestration path.",
            ),
            reranking = detail(truth, EngineModalities.TEXT, EngineTaskTypes.RERANKING, runtimeLoading, runtimeFailed, modelMissing),
            modelMissing = modelMissing,
            runtimeLoading = runtimeLoading,
            runtimeFailed = runtimeFailed,
            unavailableReason = unavailableReason,
        )
    }

    private fun detail(
        truth: EngineTruthSnapshot,
        modality: String,
        taskType: String,
        runtimeLoading: Boolean,
        runtimeFailed: Boolean,
        modelMissing: Boolean,
    ): EngineCapabilityDetail {
        val modalityState = truth.modalityState.firstOrNull { it.modality.equals(modality, ignoreCase = true) }
        val activeModelId = modalityState?.activeModelId
        val lastError = modalityState?.lastError ?: truth.runtime.lastError
        return when {
            runtimeFailed -> EngineCapabilityDetail(
                availability = CapabilityAvailability.RUNTIME_FAILED,
                activeModelId = activeModelId,
                modality = modality,
                taskType = taskType,
                exactReason = lastError?.message ?: "Engine runtime failed.",
                technicalReason = lastError?.toString(),
            )
            runtimeLoading -> EngineCapabilityDetail(
                availability = CapabilityAvailability.RUNTIME_LOADING,
                activeModelId = activeModelId,
                modality = modality,
                taskType = taskType,
                exactReason = "Engine runtime is loading.",
            )
            modelMissing || modalityState == null || activeModelId == null -> EngineCapabilityDetail(
                availability = CapabilityAvailability.MODEL_MISSING,
                activeModelId = activeModelId,
                modality = modality,
                taskType = taskType,
                exactReason = "No active $modality model is available.",
            )
            !modalityState.ready -> EngineCapabilityDetail(
                availability = CapabilityAvailability.UNAVAILABLE,
                activeModelId = activeModelId,
                modality = modality,
                taskType = taskType,
                exactReason = lastError?.message ?: "$modality runtime is not ready.",
                technicalReason = lastError?.toString(),
            )
            modalityState.supportedTaskTypes.any { it.equals(taskType, ignoreCase = true) } -> EngineCapabilityDetail(
                availability = CapabilityAvailability.AVAILABLE,
                activeModelId = activeModelId,
                modality = modality,
                taskType = taskType,
                exactReason = "$taskType is available through model $activeModelId.",
            )
            else -> EngineCapabilityDetail(
                availability = CapabilityAvailability.UNAVAILABLE,
                activeModelId = activeModelId,
                modality = modality,
                taskType = taskType,
                exactReason = "$taskType is not listed in engine truth snapshot for $modality.",
                technicalReason = "supportedTaskTypes=${modalityState.supportedTaskTypes}",
            )
        }
    }

    private fun detailWithAliases(
        truth: EngineTruthSnapshot,
        modality: String,
        taskTypes: List<String>,
        runtimeLoading: Boolean,
        runtimeFailed: Boolean,
        modelMissing: Boolean,
    ): EngineCapabilityDetail {
        val first = detail(truth, modality, taskTypes.first(), runtimeLoading, runtimeFailed, modelMissing)
        if (first.available) return first
        return taskTypes.drop(1).asSequence()
            .map { detail(truth, modality, it, runtimeLoading, runtimeFailed, modelMissing) }
            .firstOrNull { it.available }
            ?: first
    }
}
