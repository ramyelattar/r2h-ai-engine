package io.r2h.engine.client

import io.r2h.engine.api.IModalInferenceCallback
import io.r2h.engine.api.IR2hGenerateCallback
import io.r2h.engine.api.ISessionOrchestratorCallback
import io.r2h.engine.api.model.AiDecisionResult
import io.r2h.engine.api.model.EngineError
import io.r2h.engine.api.model.ErrorCode
import io.r2h.engine.api.model.GenerateRequest
import io.r2h.engine.api.model.GenerateResponse
import io.r2h.engine.api.model.ModalInferenceRequest
import io.r2h.engine.api.model.ModalInferenceResult
import io.r2h.engine.api.model.SessionConfig
import io.r2h.engine.api.model.SessionContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class EngineInferenceClient internal constructor(
    private val accessor: EngineServiceAccessor,
    private val truthClient: EngineTruthClient = EngineTruthClient(accessor),
    private val defaultTimeoutMs: Long = 60_000L,
) {
    constructor(
        connector: EngineServiceConnector,
        truthClient: EngineTruthClient = EngineTruthClient(connector),
        defaultTimeoutMs: Long = 60_000L,
    ) : this(connector.accessor, truthClient, defaultTimeoutMs)

    suspend fun generateText(
        prompt: String,
        modelId: String? = null,
        params: GenerateTextParams = GenerateTextParams(),
        timeoutMs: Long = defaultTimeoutMs,
    ): EngineClientResult<TextGenerationResult> {
        val capability = requireCapability { it.textGeneration }
        if (capability is EngineClientResult.Failure) return capability
        val detail = (capability as EngineClientResult.Success).value
        val resolvedModelId = modelId ?: detail.activeModelId
            ?: return missingModel(EngineTaskTypes.GENERATE_TEXT)
        val requestId = newRequestId()
        val request = GenerateRequest(
            requestId = requestId,
            modelId = resolvedModelId,
            prompt = prompt,
            streaming = params.streaming,
            sessionConfig = params.toSessionConfigOrNull(),
        )
        val result = accessor.withService { service ->
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<EngineClientResult<TextGenerationResult>> { continuation ->
                    val terminal = AtomicBoolean(false)
                    val tokenBuffer = StringBuilder()
                    val callback = object : IR2hGenerateCallback.Stub() {
                        override fun onToken(requestId: String, token: String) {
                            if (!terminal.get()) tokenBuffer.append(token)
                        }

                        override fun onComplete(requestId: String, response: GenerateResponse) {
                            if (terminal.compareAndSet(false, true)) {
                                val text = response.outputText.ifBlank { tokenBuffer.toString() }
                                continuation.resume(
                                    EngineClientResult.Success(
                                        TextGenerationResult(
                                            requestId = requestId,
                                            text = text,
                                            finishReason = response.finishReason,
                                            promptTokenCount = response.promptTokenCount,
                                            generatedTokenCount = response.generatedTokenCount,
                                        ),
                                    ),
                                )
                            }
                        }

                        override fun onError(requestId: String, error: EngineError) {
                            if (terminal.compareAndSet(false, true)) {
                                continuation.resume(EngineClientResult.Failure(error.toClientError()))
                            }
                        }
                    }
                    continuation.invokeOnCancellation {
                        if (terminal.compareAndSet(false, true)) {
                            runCatching { service.cancel(requestId) }
                        }
                    }
                    service.generate(request, callback)
                }
            } ?: EngineClientResult.Failure(
                EngineClientError(
                    code = EngineClientErrorCode.TIMEOUT,
                    message = "Text generation timed out.",
                    technicalReason = "requestId=$requestId timeoutMs=$timeoutMs",
                ),
            )
        }
        return flatten(result)
    }

    suspend fun inferImage(
        inputRefs: List<String>,
        taskType: String = EngineTaskTypes.IMAGE_UNDERSTANDING,
        textPrompt: String? = null,
        modelId: String? = null,
        params: Map<String, String> = emptyMap(),
        timeoutMs: Long = defaultTimeoutMs,
    ): EngineClientResult<ModalInferenceResponse> {
        val detailSelector: (EngineCapabilityState) -> EngineCapabilityDetail =
            if (taskType.equals(EngineTaskTypes.CLASSIFICATION, ignoreCase = true)) {
                { it.imageClassification }
            } else {
                { it.imageUnderstanding }
            }
        val capability = requireCapability(detailSelector)
        if (capability is EngineClientResult.Failure) return capability
        return inferModal(
            modality = EngineModalities.IMAGE,
            taskType = taskType,
            inputRefs = inputRefs,
            textPrompt = textPrompt,
            modelId = modelId ?: (capability as EngineClientResult.Success).value.activeModelId,
            params = params,
            timeoutMs = timeoutMs,
        )
    }

    suspend fun transcribeAudio(
        inputRefs: List<String>,
        modelId: String? = null,
        timeoutMs: Long = defaultTimeoutMs,
    ): EngineClientResult<ModalInferenceResponse> {
        val capability = requireCapability { it.speechToText }
        if (capability is EngineClientResult.Failure) return capability
        return inferModal(
            modality = EngineModalities.AUDIO,
            taskType = EngineTaskTypes.SPEECH_TO_TEXT,
            inputRefs = inputRefs,
            modelId = modelId ?: (capability as EngineClientResult.Success).value.activeModelId,
            timeoutMs = timeoutMs,
        )
    }

    suspend fun runOcr(
        inputRefs: List<String>,
        textPrompt: String = "Read all visible text in this image. Preserve line breaks.",
        modelId: String? = null,
        timeoutMs: Long = defaultTimeoutMs,
    ): EngineClientResult<ModalInferenceResponse> {
        val capability = requireCapability { it.ocr }
        if (capability is EngineClientResult.Failure) return capability
        return inferModal(
            modality = EngineModalities.IMAGE,
            taskType = EngineTaskTypes.OCR,
            inputRefs = inputRefs,
            textPrompt = textPrompt,
            modelId = modelId ?: (capability as EngineClientResult.Success).value.activeModelId,
            params = mapOf("engine.maxTokens" to "160", "engine.temperature" to "0.0"),
            timeoutMs = timeoutMs,
        )
    }

    suspend fun generateMultimodal(
        inputRefs: List<String>,
        textPrompt: String,
        modelId: String? = null,
        timeoutMs: Long = defaultTimeoutMs,
    ): EngineClientResult<ModalInferenceResponse> {
        val capability = requireCapability { it.multimodal }
        if (capability is EngineClientResult.Failure) return capability
        return inferModal(
            modality = EngineModalities.IMAGE,
            taskType = EngineTaskTypes.IMAGE_TEXT_MULTIMODAL,
            inputRefs = inputRefs,
            textPrompt = textPrompt,
            modelId = modelId ?: (capability as EngineClientResult.Success).value.activeModelId,
            params = mapOf("engine.maxTokens" to "220", "engine.temperature" to "0.2"),
            timeoutMs = timeoutMs,
        )
    }

    suspend fun analyzeAudio(
        inputRefs: List<String>,
        modelId: String? = null,
        timeoutMs: Long = defaultTimeoutMs,
    ): EngineClientResult<ModalInferenceResponse> {
        val capability = requireCapability { it.audioUnderstanding }
        if (capability is EngineClientResult.Failure) return capability
        val detail = (capability as EngineClientResult.Success).value
        val taskType = detail.taskType
        return inferModal(
            modality = EngineModalities.AUDIO,
            taskType = taskType,
            inputRefs = inputRefs,
            modelId = modelId ?: detail.activeModelId,
            params = mapOf("audio.topK" to "5"),
            timeoutMs = timeoutMs,
        )
    }

    suspend fun analyzeVideo(
        inputRefs: List<String>,
        textPrompt: String = "Describe each sampled video frame and produce a short temporal video summary.",
        modelId: String? = null,
        timeoutMs: Long = defaultTimeoutMs,
    ): EngineClientResult<ModalInferenceResponse> {
        val capability = requireCapability { it.videoFullAnalysis }
        if (capability is EngineClientResult.Failure) return capability
        return inferModal(
            modality = EngineModalities.VIDEO,
            taskType = EngineTaskTypes.VIDEO_FULL_ANALYSIS,
            inputRefs = inputRefs,
            textPrompt = textPrompt,
            modelId = modelId ?: (capability as EngineClientResult.Success).value.activeModelId,
            params = mapOf("video.sampleCount" to "2", "video.frameMaxTokens" to "96"),
            timeoutMs = timeoutMs,
        )
    }

    suspend fun runAgent(
        context: SessionContext,
        timeoutMs: Long = defaultTimeoutMs,
    ): EngineClientResult<AgentRunResult> {
        val capability = requireCapability { it.toolsAgents }
        if (capability is EngineClientResult.Failure) return capability
        val result = accessor.withService { service ->
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<EngineClientResult<AgentRunResult>> { continuation ->
                    val terminal = AtomicBoolean(false)
                    val thinking = StringBuilder()
                    val callback = object : ISessionOrchestratorCallback.Stub() {
                        override fun onDecision(result: AiDecisionResult) {
                            if (terminal.compareAndSet(false, true)) {
                                val text = result.textResponse.ifBlank { result.reasoning }
                                continuation.resume(
                                    EngineClientResult.Success(
                                        AgentRunResult(
                                            requestId = result.requestId,
                                            decisionType = result.decisionType,
                                            textResponse = text,
                                            reasoning = result.reasoning,
                                            toolTraceSummary = thinking.toString().ifBlank { "No streamed tool trace." },
                                        ),
                                    ),
                                )
                            }
                        }

                        override fun onThinkingChunk(chunk: String) {
                            if (!terminal.get()) thinking.append(chunk)
                        }

                        override fun onError(error: EngineError) {
                            if (terminal.compareAndSet(false, true)) {
                                continuation.resume(EngineClientResult.Failure(error.toClientError()))
                            }
                        }
                    }
                    continuation.invokeOnCancellation {
                        if (terminal.compareAndSet(false, true)) {
                            runCatching { service.cancel(context.requestId) }
                        }
                    }
                    service.runAgent(context, callback)
                }
            } ?: EngineClientResult.Failure(
                EngineClientError(
                    code = EngineClientErrorCode.TIMEOUT,
                    message = "Agent run timed out.",
                    technicalReason = "requestId=${context.requestId} timeoutMs=$timeoutMs",
                ),
            )
        }
        return flatten(result)
    }

    suspend fun synthesizeSpeech(
        text: String,
        modelId: String? = null,
        timeoutMs: Long = defaultTimeoutMs,
    ): EngineClientResult<ModalInferenceResponse> {
        val capability = requireCapability { it.textToSpeech }
        if (capability is EngineClientResult.Failure) return capability
        return inferModal(
            modality = EngineModalities.AUDIO,
            taskType = EngineTaskTypes.TEXT_TO_SPEECH,
            inputRefs = emptyList(),
            textPrompt = text,
            modelId = modelId ?: (capability as EngineClientResult.Success).value.activeModelId,
            timeoutMs = timeoutMs,
        )
    }

    suspend fun rerank(
        query: String,
        documents: List<String>,
        modelId: String? = null,
        timeoutMs: Long = defaultTimeoutMs,
    ): EngineClientResult<ModalInferenceResponse> {
        val capability = requireCapability { it.reranking }
        if (capability is EngineClientResult.Failure) return capability
        return inferModal(
            modality = EngineModalities.TEXT,
            taskType = EngineTaskTypes.RERANKING,
            inputRefs = emptyList(),
            textPrompt = query,
            modelId = modelId ?: (capability as EngineClientResult.Success).value.activeModelId,
            params = documents.mapIndexed { index, document -> "document.$index" to document }.toMap(),
            timeoutMs = timeoutMs,
        )
    }

    fun buildModalRequest(
        modality: String,
        taskType: String,
        inputRefs: List<String>,
        textPrompt: String? = null,
        modelId: String,
        params: Map<String, String> = emptyMap(),
        requestId: String = newRequestId(),
    ): ModalInferenceRequest = ModalInferenceRequest(
        requestId = requestId,
        modelId = modelId,
        modality = modality,
        taskType = taskType,
        inputRefs = inputRefs,
        textPrompt = textPrompt,
        params = params,
    )

    private suspend fun inferModal(
        modality: String,
        taskType: String,
        inputRefs: List<String>,
        textPrompt: String? = null,
        modelId: String?,
        params: Map<String, String> = emptyMap(),
        timeoutMs: Long,
    ): EngineClientResult<ModalInferenceResponse> {
        val resolvedModelId = modelId ?: return missingModel(taskType)
        val request = buildModalRequest(
            modality = modality,
            taskType = taskType,
            inputRefs = inputRefs,
            textPrompt = textPrompt,
            modelId = resolvedModelId,
            params = params,
        )
        val result = accessor.withService { service ->
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<EngineClientResult<ModalInferenceResponse>> { continuation ->
                    val terminal = AtomicBoolean(false)
                    val frames = mutableListOf<ModalInferenceResult>()
                    val callback = object : IModalInferenceCallback.Stub() {
                        override fun onResult(result: ModalInferenceResult) {
                            if (terminal.get()) return
                            frames += result
                            if (!result.finishReason.equals("STREAMING", ignoreCase = true)) {
                                if (terminal.compareAndSet(false, true)) {
                                    continuation.resume(
                                        EngineClientResult.Success(
                                            ModalInferenceResponse(request.requestId, frames.toList()),
                                        ),
                                    )
                                }
                            }
                        }

                        override fun onError(error: EngineError) {
                            if (terminal.compareAndSet(false, true)) {
                                continuation.resume(EngineClientResult.Failure(error.toClientError()))
                            }
                        }
                    }
                    continuation.invokeOnCancellation {
                        if (terminal.compareAndSet(false, true)) {
                            runCatching { service.cancel(request.requestId) }
                        }
                    }
                    service.inferModal(request, callback)
                }
            } ?: EngineClientResult.Failure(
                EngineClientError(
                    code = EngineClientErrorCode.TIMEOUT,
                    message = "Modal inference timed out.",
                    technicalReason = "requestId=${request.requestId} timeoutMs=$timeoutMs",
                ),
            )
        }
        return flatten(result)
    }

    private suspend fun requireCapability(
        selector: (EngineCapabilityState) -> EngineCapabilityDetail,
    ): EngineClientResult<EngineCapabilityDetail> {
        val caps = truthClient.getCapabilities()
        if (caps is EngineClientResult.Failure) return caps
        val detail = selector((caps as EngineClientResult.Success).value)
        if (!detail.available) {
            return EngineClientResult.Failure(
                EngineClientError(
                    code = detail.availability.toErrorCode(),
                    message = detail.exactReason,
                    technicalReason = detail.technicalReason,
                ),
            )
        }
        return EngineClientResult.Success(detail)
    }

    private fun GenerateTextParams.toSessionConfigOrNull(): SessionConfig? =
        if (maxTokens == null && temperature == null && systemPrompt == null) {
            null
        } else {
            SessionConfig(
                maxTokens = maxTokens ?: 512,
                temperature = temperature ?: 0.7f,
                systemPrompt = systemPrompt.orEmpty(),
            )
        }

    private fun CapabilityAvailability.toErrorCode(): EngineClientErrorCode =
        when (this) {
            CapabilityAvailability.MODEL_MISSING -> EngineClientErrorCode.MODEL_MISSING
            CapabilityAvailability.RUNTIME_LOADING -> EngineClientErrorCode.RUNTIME_LOADING
            CapabilityAvailability.RUNTIME_FAILED -> EngineClientErrorCode.RUNTIME_FAILED
            CapabilityAvailability.UNAVAILABLE -> EngineClientErrorCode.CAPABILITY_UNAVAILABLE
            CapabilityAvailability.AVAILABLE -> EngineClientErrorCode.INTERNAL_ERROR
        }

    private fun EngineError.toClientError(): EngineClientError =
        EngineClientError(
            code = if (code == ErrorCode.RATE_LIMITED) {
                EngineClientErrorCode.RATE_LIMITED
            } else {
                EngineClientErrorCode.ENGINE_ERROR
            },
            message = message,
            technicalReason = "stage=$stage code=$code",
            engineError = this,
        )

    private fun missingModel(taskType: String): EngineClientResult.Failure =
        EngineClientResult.Failure(
            EngineClientError(
                code = EngineClientErrorCode.MODEL_MISSING,
                message = "No active model is available for $taskType.",
            ),
        )

    private fun <T> flatten(result: EngineClientResult<EngineClientResult<T>>): EngineClientResult<T> =
        when (result) {
            is EngineClientResult.Success -> result.value
            is EngineClientResult.Failure -> result
        }

    private fun newRequestId(): String = UUID.randomUUID().toString()

    companion object {
        fun truthfulUnavailable(taskType: String, reason: String): EngineClientResult.Failure =
            EngineClientResult.Failure(
                EngineClientError(
                    code = EngineClientErrorCode.CAPABILITY_UNAVAILABLE,
                    message = reason,
                    technicalReason = "taskType=$taskType",
                ),
            )
    }
}
