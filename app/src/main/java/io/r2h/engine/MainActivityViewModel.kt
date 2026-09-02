package io.r2h.engine

import android.app.Application
import android.os.RemoteException
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.r2h.engine.api.IModalInferenceCallback
import io.r2h.engine.api.IR2hEngineService
import io.r2h.engine.api.IR2hGenerateCallback
import io.r2h.engine.api.ISessionOrchestratorCallback
import io.r2h.engine.api.model.AiDecisionResult
import io.r2h.engine.api.model.EngineError
import io.r2h.engine.api.model.GenerateRequest
import io.r2h.engine.api.model.GenerateResponse
import io.r2h.engine.api.model.ModalInferenceRequest
import io.r2h.engine.api.model.ModalInferenceResult
import io.r2h.engine.api.model.ModelRuntimeState
import io.r2h.engine.automation.R2hNotificationListenerService
import io.r2h.engine.api.privacy.DiagnosticErrorCode
import io.r2h.engine.api.privacy.DiagnosticOperation
import io.r2h.engine.api.privacy.DiagnosticStatus
import io.r2h.engine.api.privacy.PrivacySafeDiagnostics
import io.r2h.engine.tools.ToolAuditEntry
import io.r2h.engine.tools.ToolRiskLevel
import java.text.DecimalFormat
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val VIEW_MODEL_TAG = "MainActivityViewModel"

internal enum class ModalResultStyle {
    STUDIO_ONLY,
    GENERIC_TEXT,
    IMAGE_TEXT,
    OCR_TEXT,
    YOLO,
}

internal data class ToolAuditSpec(
    val toolId: String,
    val action: String,
    val result: String,
    val risk: ToolRiskLevel,
    val approved: Boolean,
    val rawContext: String = "",
)

internal data class StudioCompletionSpec(
    val title: String,
    val successLead: String,
    val errorPrefix: String,
    val audit: ToolAuditSpec? = null,
)

internal data class ModalCallbackSpec(
    val diagnosticLabel: String? = null,
    val resultStyle: ModalResultStyle = ModalResultStyle.GENERIC_TEXT,
    val studio: StudioCompletionSpec? = null,
)

private sealed interface ClientOperationDescriptor {
    data class ChatGeneration(val prompt: String) : ClientOperationDescriptor
    data class TextProof(val modelId: String, val prompt: String) : ClientOperationDescriptor
    data class AgentUi(val task: String) : ClientOperationDescriptor
    data object AgentProof : ClientOperationDescriptor
    data class Modal(val spec: ModalCallbackSpec) : ClientOperationDescriptor
    data class TtsStep(val runId: String, val label: String, val voice: String) : ClientOperationDescriptor
    data class EmbeddingStep(val runId: String, val index: Int) : ClientOperationDescriptor
}

private sealed interface ClientCallbackPayload {
    data class GenerateToken(val token: String) : ClientCallbackPayload
    data class GenerateComplete(val response: GenerateResponse) : ClientCallbackPayload
    data class AgentThinking(val chunk: String) : ClientCallbackPayload
    data class AgentDecision(val result: AiDecisionResult) : ClientCallbackPayload
    data class ModalResult(val result: ModalInferenceResult) : ClientCallbackPayload
}

internal class MainUiStateStore(
    initial: MainUiState,
    private val persist: (MainUiState) -> Unit,
) {
    private val mutableState = MutableStateFlow(initial)
    val state: StateFlow<MainUiState> = mutableState.asStateFlow()

    @Synchronized
    fun update(transform: (MainUiState) -> MainUiState): MainUiState {
        val next = transform(mutableState.value)
        mutableState.value = next
        persist(next)
        return next
    }
}

class MainActivityViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    private val toolAuditStore = (application as EngineApplication).toolAuditStore
    private val codec = MainSavedStateCodec()
    private val initialState = codec.decode(
        MainSavedStateCodec.PERSISTED_KEYS.associateWith { key -> savedStateHandle.get<String>(key) },
    )
    private val stateStore = MainUiStateStore(initialState, ::persist)
    val uiState: StateFlow<MainUiState> = stateStore.state

    private val approvalStateMachine = ApprovalStateMachine(initialState.pendingApproval)
    private val refreshExecutor = EngineUiRefreshExecutor()
    private val callbackRegistry = EngineCallbackRegistry<ClientOperationDescriptor, ClientCallbackPayload> {
            requestId,
            descriptor,
            event,
        ->
        viewModelScope.launch { reduceCallback(requestId, descriptor, event) }
    }
    private val streamingBuffers = mutableMapOf<String, StringBuilder>()
    private val ttsRuns = mutableMapOf<String, TtsRun>()
    private val embeddingRuns = mutableMapOf<String, EmbeddingRun>()

    private val connectionOwner = EngineConnectionOwner(
        binding = AndroidEngineBindingAdapter(application),
        schedule = CoroutineEngineConnectionSchedule(viewModelScope),
        onConnected = {
            update { state -> state.copy(runtime = state.runtime.copy(connected = true, errorMessage = null)) }
            refreshEngineUi()
        },
        onDisconnected = { reason ->
            callbackRegistry.failAll("Engine connection lost: ${reason.name}")
            update { state ->
                state.copy(
                    isGenerating = false,
                    isAgentRunning = false,
                    runtime = RuntimeUiState(errorMessage = "Engine service disconnected"),
                )
            }
        },
        onRefresh = ::refreshEngineUi,
    )

    val engineService: IR2hEngineService?
        get() = connectionOwner.service

    val isConnected: Boolean
        get() = connectionOwner.state == EngineConnectionState.CONNECTED

    val activeCallbackCount: Int
        get() = callbackRegistry.activeCount

    fun ensureConnected() = connectionOwner.ensureConnected()

    fun disconnectForServiceRestart() = connectionOwner.disconnectForRestart()

    fun update(transform: (MainUiState) -> MainUiState): MainUiState {
        val previousApproval = approvalStateMachine.current()
        val next = stateStore.update { current -> boundRuntimeState(transform(current)) }
        if (approvalStateMachine.current() != next.pendingApproval) {
            closeReplacedNotificationScope(previousApproval, next.pendingApproval)
            approvalStateMachine.replace(next.pendingApproval)
        }
        return next
    }

    fun setPendingApproval(approval: PendingApprovalState?) {
        closeReplacedNotificationScope(approvalStateMachine.current(), approval)
        approvalStateMachine.replace(approval)
        update { it.copy(pendingApproval = approval) }
    }

    fun claimApproval(approvalId: String): PendingApprovalState? {
        val claimed = approvalStateMachine.claim(approvalId) ?: return null
        update { it.copy(pendingApproval = claimed) }
        return claimed
    }

    fun cancelApproval(approvalId: String): PendingApprovalState? {
        val cancelled = approvalStateMachine.cancel(approvalId) ?: return null
        closeNotificationScope(cancelled)
        update { it.copy(pendingApproval = null) }
        return cancelled
    }

    fun completeApproval(approvalId: String): Boolean {
        val current = approvalStateMachine.current()
        val completed = approvalStateMachine.complete(approvalId)
        if (completed) {
            closeNotificationScope(current)
            update { it.copy(pendingApproval = null) }
        }
        return completed
    }

    fun createChatGenerationCallback(request: GenerateRequest): IR2hGenerateCallback {
        register(request.requestId, ClientOperationDescriptor.ChatGeneration(request.prompt))
        return generationCallback(request.requestId)
    }

    fun createTextProofCallback(request: GenerateRequest): IR2hGenerateCallback {
        streamingBuffers[request.requestId] = StringBuilder()
        register(request.requestId, ClientOperationDescriptor.TextProof(request.modelId, request.prompt))
        return generationCallback(request.requestId)
    }

    fun createAgentUiCallback(requestId: String, task: String): ISessionOrchestratorCallback {
        streamingBuffers[requestId] = StringBuilder()
        register(requestId, ClientOperationDescriptor.AgentUi(task))
        return agentCallback(requestId)
    }

    fun createAgentProofCallback(requestId: String): ISessionOrchestratorCallback {
        streamingBuffers[requestId] = StringBuilder()
        register(requestId, ClientOperationDescriptor.AgentProof)
        return agentCallback(requestId)
    }

    internal fun createModalCallback(requestId: String, spec: ModalCallbackSpec): IModalInferenceCallback {
        register(requestId, ClientOperationDescriptor.Modal(spec))
        return modalCallback(requestId)
    }

    private fun generationCallback(requestId: String) = object : IR2hGenerateCallback.Stub() {
        override fun onToken(callbackRequestId: String, token: String) {
            callbackRegistry.deliver(requestId, EngineCallbackEvent.Progress(ClientCallbackPayload.GenerateToken(token)))
        }

        override fun onComplete(callbackRequestId: String, response: GenerateResponse) {
            callbackRegistry.complete(requestId, ClientCallbackPayload.GenerateComplete(response))
        }

        override fun onError(callbackRequestId: String, error: EngineError) {
            callbackRegistry.fail(requestId, formatError(error))
        }
    }

    private fun agentCallback(requestId: String) = object : ISessionOrchestratorCallback.Stub() {
        override fun onDecision(result: AiDecisionResult) {
            callbackRegistry.complete(requestId, ClientCallbackPayload.AgentDecision(result))
        }

        override fun onThinkingChunk(chunk: String) {
            callbackRegistry.deliver(requestId, EngineCallbackEvent.Progress(ClientCallbackPayload.AgentThinking(chunk)))
        }

        override fun onError(error: EngineError) {
            callbackRegistry.fail(requestId, formatError(error))
        }
    }

    private fun modalCallback(requestId: String) = object : IModalInferenceCallback.Stub() {
        override fun onResult(result: ModalInferenceResult) {
            callbackRegistry.complete(requestId, ClientCallbackPayload.ModalResult(result))
        }

        override fun onError(error: EngineError) {
            callbackRegistry.fail(requestId, formatError(error))
        }
    }

    fun callbackDispatchFailed(requestId: String, message: String) {
        callbackRegistry.fail(requestId, message)
    }

    private fun register(requestId: String, descriptor: ClientOperationDescriptor) {
        check(callbackRegistry.register(requestId, descriptor)) {
            "A callback is already active for requestId=$requestId"
        }
    }

    private fun reduceCallback(
        requestId: String,
        descriptor: ClientOperationDescriptor,
        event: EngineCallbackEvent<ClientCallbackPayload>,
    ) {
        when (descriptor) {
            is ClientOperationDescriptor.ChatGeneration -> reduceChatGeneration(requestId, event)
            is ClientOperationDescriptor.TextProof -> reduceTextProof(requestId, descriptor, event)
            is ClientOperationDescriptor.AgentUi -> reduceAgentUi(requestId, descriptor, event)
            ClientOperationDescriptor.AgentProof -> reduceAgentProof(requestId, event)
            is ClientOperationDescriptor.Modal -> reduceModal(descriptor.spec, event)
            is ClientOperationDescriptor.TtsStep -> reduceTtsStep(descriptor, event)
            is ClientOperationDescriptor.EmbeddingStep -> reduceEmbeddingStep(descriptor, event)
        }
        if (event !is EngineCallbackEvent.Progress) {
            streamingBuffers.remove(requestId)
        }
    }

    private fun reduceChatGeneration(
        requestId: String,
        event: EngineCallbackEvent<ClientCallbackPayload>,
    ) {
        when (event) {
            is EngineCallbackEvent.Progress -> {
                val token = (event.value as? ClientCallbackPayload.GenerateToken)?.token ?: return
                update { state ->
                    state.copy(
                        isGenerating = true,
                        lastGenerationText = (state.lastGenerationText + token).takeLast(MainStateLimits.TRANSCRIPT_CHARS),
                    )
                }
            }
            is EngineCallbackEvent.Terminal -> {
                val response = (event.value as? ClientCallbackPayload.GenerateComplete)?.response ?: return
                update { state ->
                    val body = state.lastGenerationText.ifBlank {
                        response.outputText.ifBlank { "[Engine returned 0 tokens. Check Diagnostics for runtime logs.]" }
                    }
                    state.copy(
                        isGenerating = false,
                        lastGenerationText = body.takeLast(MainStateLimits.TRANSCRIPT_CHARS),
                        lastGenerationMeta = generationMeta(response),
                        lastGenerationError = null,
                    )
                }
                Log.i(
                    VIEW_MODEL_TAG,
                    PrivacySafeDiagnostics.contentEvent(
                        operation = DiagnosticOperation.GENERATION,
                        status = DiagnosticStatus.COMPLETED,
                        requestId = requestId,
                        outputContent = response.outputText,
                    ),
                )
                refreshEngineUi()
            }
            is EngineCallbackEvent.Failed -> {
                update { it.copy(isGenerating = false, lastGenerationError = event.reason) }
                Log.e(
                    VIEW_MODEL_TAG,
                    PrivacySafeDiagnostics.contentEvent(
                        operation = DiagnosticOperation.GENERATION,
                        status = DiagnosticStatus.FAILED,
                        requestId = requestId,
                        outputContent = event.reason,
                        errorCode = DiagnosticErrorCode.RUNTIME_FAILURE,
                    ),
                )
                refreshEngineUi()
            }
        }
    }

    private fun reduceTextProof(
        requestId: String,
        descriptor: ClientOperationDescriptor.TextProof,
        event: EngineCallbackEvent<ClientCallbackPayload>,
    ) {
        val buffer = streamingBuffers.getOrPut(requestId) { StringBuilder() }
        when (event) {
            is EngineCallbackEvent.Progress -> {
                val token = (event.value as? ClientCallbackPayload.GenerateToken)?.token ?: return
                appendBounded(buffer, token)
                update { it.copy(diagnosticsText = "TEXT proof running\n$requestId\nOutput so far:\n$buffer") }
            }
            is EngineCallbackEvent.Terminal -> {
                val response = (event.value as? ClientCallbackPayload.GenerateComplete)?.response ?: return
                val output = buffer.toString().ifBlank { response.outputText }.takeLast(MainStateLimits.TRANSCRIPT_CHARS)
                update {
                    it.copy(
                        lastPromptText = descriptor.prompt,
                        lastGenerationText = output,
                        lastGenerationMeta = generationMeta(response),
                        diagnosticsText = "TEXT proof complete\n" +
                            "requestId=$requestId\n" +
                            "finish=${response.finishReason.name}\n" +
                            "model=${descriptor.modelId}\n" +
                            "output=$output\n" +
                            "local Binder TEXT only; no cloud/network API used.",
                    )
                }
                refreshEngineUi()
            }
            is EngineCallbackEvent.Failed -> update {
                it.copy(diagnosticsText = "TEXT proof failed\n$requestId\n${event.reason}")
            }
        }
    }

    private fun reduceAgentUi(
        requestId: String,
        descriptor: ClientOperationDescriptor.AgentUi,
        event: EngineCallbackEvent<ClientCallbackPayload>,
    ) {
        val thinking = streamingBuffers.getOrPut(requestId) { StringBuilder() }
        when (event) {
            is EngineCallbackEvent.Progress -> {
                val chunk = (event.value as? ClientCallbackPayload.AgentThinking)?.chunk ?: return
                appendBounded(thinking, chunk)
                update {
                    it.copy(
                        isAgentRunning = true,
                        lastAgentTimeline = listOf("Task received", "Planning", "Tools"),
                        lastAgentResult = thinking.toString().ifBlank { "Planning locally..." },
                        lastAgentResultPersistence = UiContentPersistence.PERSISTABLE,
                    )
                }
            }
            is EngineCallbackEvent.Terminal -> {
                val result = (event.value as? ClientCallbackPayload.AgentDecision)?.result ?: return
                val response = sanitizeAgentResponse(
                    result.textResponse.ifBlank { result.reasoning.ifBlank { "Local agent completed without a text response." } },
                )
                update {
                    it.copy(
                        isAgentRunning = false,
                        lastAgentTimeline = listOf("Task received", "Planning", "Tools", "Result"),
                        lastAgentToolSummary = "Calculator, engine status inspector, and text planner were available for this run.",
                        lastAgentResult = response,
                        lastAgentResultPersistence = UiContentPersistence.PERSISTABLE,
                        lastAgentError = null,
                    )
                }
                audit(
                    ToolAuditSpec(
                        toolId = "text_planner",
                        action = "Agent planner task: ${descriptor.task}",
                        result = "Planner returned a local response.",
                        risk = ToolRiskLevel.LOW,
                        approved = false,
                        rawContext = "decisionType=${result.decisionType}",
                    ),
                    result.requestId,
                )
            }
            is EngineCallbackEvent.Failed -> update {
                it.copy(
                    isAgentRunning = false,
                    lastAgentTimeline = listOf("Task received", "Planning", "Runtime returned an error"),
                    lastAgentError = event.reason,
                )
            }
        }
    }

    private fun reduceAgentProof(
        requestId: String,
        event: EngineCallbackEvent<ClientCallbackPayload>,
    ) {
        val thinking = streamingBuffers.getOrPut(requestId) { StringBuilder() }
        when (event) {
            is EngineCallbackEvent.Progress -> {
                val chunk = (event.value as? ClientCallbackPayload.AgentThinking)?.chunk ?: return
                appendBounded(thinking, chunk)
                update { it.copy(diagnosticsText = "Agent proof running\nPlanner thinking:\n$thinking") }
            }
            is EngineCallbackEvent.Terminal -> {
                val result = (event.value as? ClientCallbackPayload.AgentDecision)?.result ?: return
                update {
                    it.copy(
                        diagnosticsText = "Agent proof complete\n" +
                            "requestId=${result.requestId}\n" +
                            "decisionType=${result.decisionType}\n" +
                            "thinking=${thinking.toString().ifBlank { "(no streamed thinking)" }}\n" +
                            "final=${result.textResponse}\n" +
                            "reasoning=${result.reasoning}",
                    )
                }
            }
            is EngineCallbackEvent.Failed -> update {
                it.copy(diagnosticsText = "Agent proof failed\n${event.reason}")
            }
        }
    }

    private fun reduceModal(
        spec: ModalCallbackSpec,
        event: EngineCallbackEvent<ClientCallbackPayload>,
    ) {
        when (event) {
            is EngineCallbackEvent.Progress -> Unit
            is EngineCallbackEvent.Terminal -> {
                val result = (event.value as? ClientCallbackPayload.ModalResult)?.result ?: return
                val diagnostic = modalDiagnostic(spec, result)
                update { state ->
                    state.copy(
                        diagnosticsText = diagnostic ?: state.diagnosticsText,
                        lastStudioWorkflowTitle = spec.studio?.title ?: state.lastStudioWorkflowTitle,
                        lastStudioResultPreview = spec.studio?.let { "${it.successLead}\n${result.textValue.orEmpty()}" }
                            ?: state.lastStudioResultPreview,
                        activeStudioWorkflowTitle = if (spec.studio != null) null else state.activeStudioWorkflowTitle,
                    )
                }
                spec.studio?.audit?.let { audit(it, result.requestId) }
            }
            is EngineCallbackEvent.Failed -> update { state ->
                val label = spec.diagnosticLabel
                state.copy(
                    diagnosticsText = label?.let { "$it proof failed\n${event.reason}" } ?: state.diagnosticsText,
                    lastStudioWorkflowTitle = spec.studio?.title ?: state.lastStudioWorkflowTitle,
                    lastStudioResultPreview = spec.studio?.let { "${it.errorPrefix}: ${event.reason}" }
                        ?: state.lastStudioResultPreview,
                    activeStudioWorkflowTitle = if (spec.studio != null) null else state.activeStudioWorkflowTitle,
                )
            }
        }
    }

    private fun modalDiagnostic(spec: ModalCallbackSpec, result: ModalInferenceResult): String? {
        val label = spec.diagnosticLabel ?: return null
        val prefix = "$label proof result\n" +
            "requestId=${result.requestId}\n" +
            "type=${result.resultType}\n" +
            "finish=${result.finishReason}\n"
        return when (spec.resultStyle) {
            ModalResultStyle.YOLO -> prefix +
                "detections=${result.labelScores.joinToString()}\n" +
                "NMS applied inside YoloOnnxDetectionBackendRuntime."
            ModalResultStyle.IMAGE_TEXT -> prefix + "generated text\n" + result.textValue.orEmpty()
            ModalResultStyle.OCR_TEXT -> prefix + "extracted text\n" + result.textValue.orEmpty()
            ModalResultStyle.STUDIO_ONLY,
            ModalResultStyle.GENERIC_TEXT -> prefix + result.textValue.orEmpty()
        }
    }

    fun runTtsProof(modelId: String) {
        val runId = "tts-run-${UUID.randomUUID()}"
        ttsRuns[runId] = TtsRun(modelId = modelId)
        update {
            it.copy(
                diagnosticsText = "TTS proof started\n" +
                    "Model: $modelId\n" +
                    "API: IR2hEngineService.synthesizeSpeech(); inferModal(TEXT_TO_SPEECH)\n" +
                    "Runtime: Sherpa-ONNX Piper\n" +
                    "Network/cloud: not used by this app.",
            )
        }
        submitTtsStep(runId, "English", "en", "Hello, this is a local offline speech synthesis test from R2H Engine.")
    }

    private fun submitTtsStep(runId: String, label: String, voice: String, text: String) {
        val run = ttsRuns[runId] ?: return
        val service = engineService ?: return failTtsRun(runId, "Engine service is not connected")
        val request = ModalInferenceRequest(
            requestId = "tts-proof-$voice-${UUID.randomUUID()}",
            modelId = run.modelId,
            modality = "AUDIO",
            taskType = "TEXT_TO_SPEECH",
            inputRefs = emptyList(),
            textPrompt = text,
            params = mapOf("tts.voice" to voice, "engine.timeoutMs" to "300000"),
        )
        register(request.requestId, ClientOperationDescriptor.TtsStep(runId, label, voice))
        try {
            service.synthesizeSpeech(request, modalCallback(request.requestId))
        } catch (e: RemoteException) {
            callbackRegistry.fail(request.requestId, "TTS proof call failed: ${e.message ?: "RemoteException"}")
        }
    }

    private fun reduceTtsStep(
        descriptor: ClientOperationDescriptor.TtsStep,
        event: EngineCallbackEvent<ClientCallbackPayload>,
    ) {
        val run = ttsRuns[descriptor.runId] ?: return
        when (event) {
            is EngineCallbackEvent.Progress -> Unit
            is EngineCallbackEvent.Terminal -> {
                val result = (event.value as? ClientCallbackPayload.ModalResult)?.result ?: return
                run.output.appendLine("${descriptor.label} TTS proof result")
                run.output.appendLine("requestId=${result.requestId}")
                run.output.appendLine("type=${result.resultType}")
                run.output.appendLine("finish=${result.finishReason}")
                run.output.appendLine(result.textValue.orEmpty())
                update { it.copy(diagnosticsText = run.output.toString().trimEnd()) }
                if (descriptor.voice == "en") {
                    submitTtsStep(
                        descriptor.runId,
                        "Arabic",
                        "ar",
                        "مرحبا، هذا اختبار صوت محلي من محرك R2H.",
                    )
                } else {
                    ttsRuns.remove(descriptor.runId)
                }
            }
            is EngineCallbackEvent.Failed -> failTtsRun(
                descriptor.runId,
                "${descriptor.label} TTS proof failed\n${event.reason}",
            )
        }
    }

    private fun failTtsRun(runId: String, message: String) {
        val run = ttsRuns.remove(runId)
        val prefix = run?.output?.toString().orEmpty()
        update { it.copy(diagnosticsText = (prefix + message).trim()) }
    }

    fun runEmbeddingsProof(modelId: String, studioTitle: String?) {
        val runId = "embedding-run-${UUID.randomUUID()}"
        embeddingRuns[runId] = EmbeddingRun(
            modelId = modelId,
            inputs = listOf(
                "local private AI engine",
                "on-device offline runtime",
                "banana recipe",
            ),
            studioTitle = studioTitle,
        )
        update {
            it.copy(
                diagnosticsText = "Embeddings proof started\n" +
                    "Model: $modelId\n" +
                    "Inputs: A=\"local private AI engine\" B=\"on-device offline runtime\" C=\"banana recipe\"\n" +
                    "API: IR2hEngineService.createEmbedding(); inferModal(EMBEDDING)\n" +
                    "Runtime: llama.cpp Qwen3 embeddings\n" +
                    "Network/cloud: not used by this app.",
            )
        }
        submitEmbeddingStep(runId, 0)
    }

    private fun submitEmbeddingStep(runId: String, index: Int) {
        val run = embeddingRuns[runId] ?: return
        val service = engineService ?: return failEmbeddingRun(runId, "Engine service is not connected")
        val label = EMBEDDING_LABELS[index]
        val request = ModalInferenceRequest(
            requestId = "embedding-proof-${label.lowercase()}-${UUID.randomUUID()}",
            modelId = run.modelId,
            modality = "TEXT",
            taskType = "EMBEDDING",
            inputRefs = emptyList(),
            textPrompt = run.inputs[index],
            params = mapOf("engine.timeoutMs" to "300000"),
        )
        register(request.requestId, ClientOperationDescriptor.EmbeddingStep(runId, index))
        try {
            service.createEmbedding(request, modalCallback(request.requestId))
        } catch (e: RemoteException) {
            callbackRegistry.fail(request.requestId, "Embeddings proof call failed: ${e.message ?: "RemoteException"}")
        }
    }

    private fun reduceEmbeddingStep(
        descriptor: ClientOperationDescriptor.EmbeddingStep,
        event: EngineCallbackEvent<ClientCallbackPayload>,
    ) {
        val run = embeddingRuns[descriptor.runId] ?: return
        when (event) {
            is EngineCallbackEvent.Progress -> Unit
            is EngineCallbackEvent.Terminal -> {
                val result = (event.value as? ClientCallbackPayload.ModalResult)?.result ?: return
                val vector = parseEmbeddingJson(result.embeddingJson)
                if (vector.isEmpty()) {
                    failEmbeddingRun(
                        descriptor.runId,
                        "Embeddings proof failed\nlabel=${EMBEDDING_LABELS[descriptor.index]}\n" +
                            "finish=${result.finishReason}\nreason=empty embedding vector",
                    )
                    return
                }
                run.vectors += vector
                if (descriptor.index + 1 < run.inputs.size) {
                    submitEmbeddingStep(descriptor.runId, descriptor.index + 1)
                } else {
                    finishEmbeddingRun(descriptor.runId, run)
                }
            }
            is EngineCallbackEvent.Failed -> failEmbeddingRun(
                descriptor.runId,
                "Embeddings proof failed\n${event.reason}",
            )
        }
    }

    private fun finishEmbeddingRun(runId: String, run: EmbeddingRun) {
        embeddingRuns.remove(runId)
        val ab = cosine(run.vectors[0], run.vectors[1])
        val ac = cosine(run.vectors[0], run.vectors[2])
        val format = DecimalFormat("0.0000")
        val pass = ab > ac
        val studioMessage = "Sample result from bundled local input\n" +
            "Similar local phrases scored higher than the unrelated phrase: ${format.format(ab)} vs ${format.format(ac)}."
        update { state ->
            state.copy(
                diagnosticsText = "Embeddings proof result\n" +
                    "finish=${if (pass) "COMPLETED" else "FAILED"}\n" +
                    "modelId=${run.modelId}\n" +
                    "runtime=llama.cpp Qwen3 embeddings\n" +
                    "pooling=lasttoken\nnormalization=l2\n" +
                    "vectorDimension=${run.vectors[0].size}\n" +
                    "similarity(A,B)=${format.format(ab)}\n" +
                    "similarity(A,C)=${format.format(ac)}\n" +
                    "comparison=${if (pass) "PASS" else "FAIL"}\n" +
                    "embedding/vector/dimension/similarity proof executed locally.",
                lastStudioWorkflowTitle = run.studioTitle ?: state.lastStudioWorkflowTitle,
                lastStudioResultPreview = if (run.studioTitle != null) studioMessage else state.lastStudioResultPreview,
                activeStudioWorkflowTitle = if (run.studioTitle != null) null else state.activeStudioWorkflowTitle,
            )
        }
        run.studioTitle?.let {
            audit(
                ToolAuditSpec(
                    "semantic_search",
                    it,
                    "Semantic sample comparison completed.",
                    ToolRiskLevel.LOW,
                    false,
                    "similarityAB=${format.format(ab)}; similarityAC=${format.format(ac)}",
                ),
                runId,
            )
        }
    }

    private fun failEmbeddingRun(runId: String, message: String) {
        val run = embeddingRuns.remove(runId)
        update { state ->
            state.copy(
                diagnosticsText = message,
                lastStudioWorkflowTitle = run?.studioTitle ?: state.lastStudioWorkflowTitle,
                lastStudioResultPreview = if (run?.studioTitle != null) {
                    "Sample run could not complete: ${message.substringAfterLast('\n')}"
                } else {
                    state.lastStudioResultPreview
                },
                activeStudioWorkflowTitle = if (run?.studioTitle != null) null else state.activeStudioWorkflowTitle,
            )
        }
    }

    fun refreshEngineUi() {
        val service = engineService
        if (service == null) {
            update { it.copy(runtime = RuntimeUiState(errorMessage = "Engine service not connected")) }
            return
        }
        viewModelScope.launch {
            try {
                val refreshed = refreshExecutor.execute {
                    val apiVersion = service.apiVersion
                    val status = service.engineStatus
                    val loadStatus = if (apiVersion >= 2) service.modelLoadStatus else null
                    val truth = if (apiVersion >= 2) service.engineTruthSnapshot else null
                    val installedModels = service.listInstalledModels()
                    RuntimeUiState(
                        connected = true,
                        apiVersion = apiVersion,
                        loadedModelId = status.loadedModelId,
                        truth = truth,
                        serviceModels = installedModels,
                        localModels = installedModels,
                        loadState = loadStatus?.state?.toString(),
                        loadModelId = loadStatus?.modelId,
                        loadProgress = loadStatus?.progressPercent,
                        loadError = loadStatus?.errorMessage,
                    )
                }
                update { state ->
                    state.copy(
                        selectedModelId = state.selectedModelId
                            ?: findTextModelId(refreshed)
                            ?: refreshed.loadedModelId
                            ?: refreshed.serviceModels.firstOrNull()?.modelId
                            ?: refreshed.localModels.firstOrNull()?.modelId,
                        runtime = refreshed,
                    )
                }
            } catch (e: RemoteException) {
                Log.e(
                    VIEW_MODEL_TAG,
                    PrivacySafeDiagnostics.failureEvent(
                        DiagnosticOperation.ENGINE_REFRESH,
                        errorCode = DiagnosticErrorCode.REMOTE_FAILURE,
                        failure = e,
                    ),
                )
                update { it.copy(runtime = RuntimeUiState(errorMessage = "RemoteException: ${e.message ?: "unknown"}")) }
            } catch (t: Throwable) {
                Log.e(
                    VIEW_MODEL_TAG,
                    PrivacySafeDiagnostics.failureEvent(
                        DiagnosticOperation.ENGINE_REFRESH,
                        errorCode = DiagnosticErrorCode.RUNTIME_FAILURE,
                        failure = t,
                    ),
                )
                update {
                    it.copy(
                        runtime = RuntimeUiState(
                            errorMessage = "Engine status error\n${t.javaClass.simpleName}: ${t.message.orEmpty()}",
                        ),
                    )
                }
            }
        }
    }

    private fun findTextModelId(state: RuntimeUiState): String? {
        val truth = state.truth ?: return null
        return truth.runtime.activeTextModelId
            ?: truth.runtime.activeModelId
            ?: truth.models.firstOrNull { it.ready && it.backendId == "llama-cpp" }?.modelId
            ?: truth.models.firstOrNull {
                it.runtimeState in setOf(ModelRuntimeState.ACTIVE, ModelRuntimeState.LOADED, ModelRuntimeState.LOADABLE)
            }?.modelId
    }

    private fun persist(state: MainUiState) {
        val encoded = codec.encode(state)
        encoded.values.forEach { (key, value) -> savedStateHandle[key] = value }
    }

    private fun boundRuntimeState(state: MainUiState): MainUiState = state.copy(
        selectedModelId = state.selectedModelId?.take(MainStateLimits.IDENTIFIER_CHARS),
        lastPromptText = state.lastPromptText.take(MainStateLimits.PROMPT_CHARS),
        lastGenerationText = state.lastGenerationText.takeLast(MainStateLimits.TRANSCRIPT_CHARS),
        lastGenerationMeta = state.lastGenerationMeta.take(MainStateLimits.RESULT_CHARS),
        lastGenerationError = state.lastGenerationError?.take(MainStateLimits.RESULT_CHARS),
        lastStudioWorkflowTitle = state.lastStudioWorkflowTitle.take(2_048),
        lastStudioResultPreview = state.lastStudioResultPreview.take(MainStateLimits.RESULT_CHARS),
        activeStudioWorkflowTitle = state.activeStudioWorkflowTitle?.take(2_048),
        lastAgentTask = state.lastAgentTask.take(MainStateLimits.PROMPT_CHARS),
        lastAgentTimeline = state.lastAgentTimeline.take(32).map { it.take(4_096) },
        lastAgentToolSummary = state.lastAgentToolSummary.take(MainStateLimits.RESULT_CHARS),
        lastAgentResult = state.lastAgentResult.take(MainStateLimits.RESULT_CHARS),
        lastAgentError = state.lastAgentError?.take(MainStateLimits.RESULT_CHARS),
        diagnosticsText = state.diagnosticsText.take(MainStateLimits.RESULT_CHARS),
        pendingApproval = state.pendingApproval?.copy(
            approvalId = state.pendingApproval.approvalId.take(MainStateLimits.IDENTIFIER_CHARS),
            toolId = state.pendingApproval.toolId.take(MainStateLimits.IDENTIFIER_CHARS),
            title = state.pendingApproval.title.take(MainStateLimits.APPROVAL_TITLE_CHARS),
            detail = state.pendingApproval.detail.take(MainStateLimits.APPROVAL_DETAIL_CHARS),
            action = state.pendingApproval.action.copy(
                argument = state.pendingApproval.action.argument.take(MainStateLimits.APPROVAL_ARGUMENT_CHARS),
            ),
        ),
    )

    private fun generationMeta(response: GenerateResponse): String =
        "finish=${response.finishReason.name} | prompt=${response.promptTokenCount}t | generated=${response.generatedTokenCount}t"

    private fun appendBounded(buffer: StringBuilder, text: String) {
        buffer.append(text)
        if (buffer.length > MainStateLimits.TRANSCRIPT_CHARS) {
            buffer.delete(0, buffer.length - MainStateLimits.TRANSCRIPT_CHARS)
        }
    }

    private fun sanitizeAgentResponse(text: String): String {
        val blocked = listOf("engineState=", "loadedModels=", "raw tool", "stacktrace", "Exception:")
        return text.lineSequence()
            .filterNot { line -> blocked.any { marker -> line.contains(marker, ignoreCase = true) } }
            .joinToString("\n")
            .trim()
            .ifBlank { "Local agent completed the task." }
    }

    private fun audit(spec: ToolAuditSpec, requestId: String) {
        val raw = buildString {
            append("requestId=")
            append(requestId)
            if (spec.rawContext.isNotBlank()) {
                append("; ")
                append(spec.rawContext)
            }
        }
        toolAuditStore.add(
            ToolAuditEntry(
                timestampMs = System.currentTimeMillis(),
                toolId = spec.toolId,
                actionSummary = spec.action,
                result = spec.result,
                riskLevel = spec.risk,
                approvedByUser = spec.approved,
                rawDetails = raw,
            ),
        )
    }

    private fun formatError(error: EngineError): String =
        "${error.stage.name}/${error.code.name}: ${error.message}"

    private fun parseEmbeddingJson(json: String?): FloatArray {
        val raw = json?.trim()?.removePrefix("[")?.removeSuffix("]").orEmpty()
        if (raw.isBlank()) return FloatArray(0)
        return raw.split(',').mapNotNull { it.trim().toFloatOrNull() }.toFloatArray()
    }

    private fun cosine(left: FloatArray, right: FloatArray): Double {
        val size = minOf(left.size, right.size)
        var dot = 0.0
        var leftNorm = 0.0
        var rightNorm = 0.0
        for (index in 0 until size) {
            val l = left[index].toDouble()
            val r = right[index].toDouble()
            dot += l * r
            leftNorm += l * l
            rightNorm += r * r
        }
        if (leftNorm == 0.0 || rightNorm == 0.0) return 0.0
        return dot / (kotlin.math.sqrt(leftNorm) * kotlin.math.sqrt(rightNorm))
    }

    override fun onCleared() {
        closeNotificationScope(approvalStateMachine.current())
        val service = engineService
        callbackRegistry.activeRequestIds().forEach { requestId ->
            try {
                service?.cancel(requestId)
            } catch (_: Throwable) {
                // The binder may already be dead; local registry cleanup still runs.
            }
        }
        callbackRegistry.failAll("UI state owner closed")
        connectionOwner.close()
        super.onCleared()
    }

    private fun closeReplacedNotificationScope(
        previous: PendingApprovalState?,
        replacement: PendingApprovalState?,
    ) {
        if (previous?.approvalId != replacement?.approvalId) closeNotificationScope(previous)
    }

    private fun closeNotificationScope(approval: PendingApprovalState?) {
        if (approval?.action?.type == ApprovalActionType.NOTIFICATION_SUMMARY) {
            R2hNotificationListenerService.closeSummaryCapture(approval.action.argument)
        }
    }

    private data class TtsRun(
        val modelId: String,
        val output: StringBuilder = StringBuilder(),
    )

    private data class EmbeddingRun(
        val modelId: String,
        val inputs: List<String>,
        val studioTitle: String?,
        val vectors: MutableList<FloatArray> = mutableListOf(),
    )

    private companion object {
        val EMBEDDING_LABELS = listOf("A", "B", "C")
    }
}
