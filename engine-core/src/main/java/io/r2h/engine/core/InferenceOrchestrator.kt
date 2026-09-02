package io.r2h.engine.core

import android.os.RemoteException
import android.util.Log
import io.r2h.engine.api.privacy.DiagnosticErrorCode
import io.r2h.engine.api.privacy.DiagnosticOperation
import io.r2h.engine.api.privacy.DiagnosticStatus
import io.r2h.engine.api.privacy.PrivacySafeDiagnostics
import io.r2h.engine.api.model.EngineError
import io.r2h.engine.api.model.EngineStateCode
import io.r2h.engine.api.model.EngineStatus
import io.r2h.engine.api.model.ErrorCode
import io.r2h.engine.api.model.ErrorStage
import io.r2h.engine.api.model.FinishReason
import io.r2h.engine.api.model.GenerateResponse
import io.r2h.engine.api.model.ModelLoadStatus
import io.r2h.engine.nativebridge.NativeErrorCode
import io.r2h.engine.nativebridge.NativeFinishCode
import io.r2h.engine.nativebridge.NativeGenerateResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "InferenceOrchestrator"
private const val DEFAULT_MAX_TOKENS = 512
private const val DEFAULT_TEMPERATURE = 0.7f

internal class InferenceOrchestrator(
    private val scope: CoroutineScope,
    private val queue: RequestQueue,
    private val runtimeRegistry: RuntimeRegistry = DefaultRuntimeRegistry(),
    private val taskRouter: TaskRouter = DefaultTaskRouter(),
    private val nativeRuntime: NativeRuntimePort = JniNativeRuntimePort,
    private val eventSink: InferenceEventSink = InferenceEventSink.NoOp,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val _state = MutableStateFlow<OrchestratorState>(OrchestratorState.Unavailable)
    val state: StateFlow<OrchestratorState> = _state.asStateFlow()

    // Legacy native context handle (used by the generate() path)
    private var contextHandle: Long = 0L

    @Volatile private var activeDescriptor: ModelDescriptor? = null
    @Volatile private var loadedModelIdInternal: String? = null
    @Volatile private var loadStatusState: String = "Idle"
    @Volatile private var loadProgressPercent: Int = 0
    @Volatile private var loadErrorMessage: String? = null
    @Volatile private var lastSuccessfulInferenceEpochMs: Long? = null
    @Volatile private var lastErrorInternal: EngineError? = null
    @Volatile private var loadStartEpochMs: Long = 0L

    init {
        scope.launch(dispatcher) { consumeLoop() }
    }

    // region Status accessors

    fun activeModelId(): String? = activeDescriptor?.id ?: loadedModelIdInternal

    fun activeBackendId(): String? = activeDescriptor?.backendKey

    fun activeBackendLabel(): String? = activeDescriptor?.backendKey?.let { key ->
        runtimeRegistry.getRuntime(key)?.descriptor?.displayName
    }

    fun isReadyForInference(): Boolean {
        val state = _state.value
        return (state == OrchestratorState.Idle) && (activeDescriptor != null || contextHandle != 0L)
    }

    fun lastSuccessfulInferenceEpochMs(): Long? = lastSuccessfulInferenceEpochMs

    fun lastError(): EngineError? = lastErrorInternal

    fun currentLoadStatus(): ModelLoadStatus = ModelLoadStatus(
        modelId = activeDescriptor?.id ?: loadedModelIdInternal,
        state = loadStatusState,
        progressPercent = loadProgressPercent,
        errorMessage = loadErrorMessage,
    )

    fun currentStatus(): EngineStatus = EngineStatus(
        state = when (_state.value) {
            is OrchestratorState.Idle -> EngineStateCode.IDLE
            is OrchestratorState.Generating -> EngineStateCode.GENERATING
            is OrchestratorState.LoadingModel -> EngineStateCode.LOADING_MODEL
            is OrchestratorState.Unavailable -> EngineStateCode.UNAVAILABLE
        },
        loadedModelId = activeDescriptor?.id ?: loadedModelIdInternal,
        activeRequestCount = queue.pendingCount,
    )

    // endregion

    // region Model loading

    fun loadModel(descriptor: ModelDescriptor) {
        val modelId = descriptor.id
        loadStartEpochMs = System.currentTimeMillis()
        _state.value = OrchestratorState.LoadingModel(modelId)
        loadStatusState = "Loading"
        loadProgressPercent = 0
        loadErrorMessage = null

        val runtime = runtimeRegistry.getRuntime(descriptor.backendKey)
        if (runtime == null) {
            val msg = "No runtime registered for backend '${descriptor.backendKey}'"
            logModelFailure(msg)
            _state.value = OrchestratorState.Unavailable
            loadStatusState = "Failed"
            loadErrorMessage = msg
            lastErrorInternal = EngineError(ErrorStage.ENGINE, ErrorCode.NO_ACTIVE_RUNTIME, msg)
            eventSink.onModelLoadFailed(modelId, msg)
            return
        }

        scope.launch(Dispatchers.IO) {
            val result = runtime.loadModel(descriptor)
            val elapsed = System.currentTimeMillis() - loadStartEpochMs
            when (result) {
                is LoadResult.Success -> {
                    // The generate() Binder path still uses the orchestrator's native
                    // context handle. Do not mark the service ready for llama.cpp unless
                    // that handle exists and can service requests.
                    if (descriptor.backendKey == "llama-cpp") {
                        val runtimeHandle = (runtime as? JniTextBackendRuntime)?.loadedContextHandle() ?: 0L
                        contextHandle = if (runtimeHandle != 0L) {
                            runtimeHandle
                        } else {
                            val source = descriptor.source
                            if (source is ModelDescriptor.Source.Local) {
                                nativeRuntime.createContext(
                                    source.artifactRef,
                                    descriptor.constraints.maxContextWindow,
                                    4,
                                )
                            } else {
                                0L
                            }
                        }

                        if (contextHandle == 0L) {
                            val msg = "Native context creation failed for backend '${descriptor.backendKey}'"
                            _state.value = OrchestratorState.Unavailable
                            loadStatusState = "Failed"
                            loadErrorMessage = msg
                            lastErrorInternal = EngineError(
                                ErrorStage.MODEL_LOADING,
                                ErrorCode.MODEL_NOT_LOADABLE,
                                msg,
                            )
                            eventSink.onModelLoadFailed(modelId, msg)
                            logModelFailure(msg)
                            return@launch
                        }
                        if (runtimeHandle == 0L) {
                            Log.i(TAG, "Native context created for legacy generate() path")
                        }
                    }

                    activeDescriptor = descriptor
                    loadedModelIdInternal = modelId
                    loadStatusState = "Loaded"
                    loadProgressPercent = 100
                    _state.value = OrchestratorState.Idle
                    lastErrorInternal = null
                    eventSink.onModelLoaded(modelId, elapsed)
                    Log.i(
                        TAG,
                        PrivacySafeDiagnostics.contentEvent(
                            operation = DiagnosticOperation.MODEL_RUNTIME,
                            status = DiagnosticStatus.COMPLETED,
                            durationMs = elapsed,
                        ),
                    )
                }
                is LoadResult.Failure -> {
                    _state.value = OrchestratorState.Unavailable
                    loadStatusState = "Failed"
                    loadErrorMessage = result.message
                    lastErrorInternal = EngineError(
                        ErrorStage.MODEL_LOADING,
                        ErrorCode.MODEL_NOT_LOADABLE,
                        result.message,
                    )
                    eventSink.onModelLoadFailed(modelId, result.message)
                    logModelFailure(result.message)
                }
            }
        }
    }

    fun loadModel(modelPath: String, modelId: String, maxContextWindow: Int, threads: Int) {
        val descriptor = ModelDescriptor(
            id = modelId,
            displayName = modelId,
            modelType = ModelType.TEXT_GENERATION,
            capabilities = setOf(
                ModelCapability.Input.Text,
                ModelCapability.Output.Text,
                ModelCapability.Execution.Local,
            ),
            backendKey = "llama-cpp",
            source = ModelDescriptor.Source.Local(modelPath, "gguf"),
            constraints = ModelDescriptor.Constraints(maxContextWindow = maxContextWindow),
        )
        val runtime = object : BackendRuntime {
            override val descriptor = RuntimeDescriptor(
                key = "llama-cpp",
                displayName = "llama.cpp",
                supportedModelTypes = setOf(ModelType.TEXT_GENERATION),
                supportedCapabilities = descriptor.capabilities,
                supportedLocalities = setOf(ExecutionLocality.LOCAL),
            )

            override fun assessCompatibility(model: ModelDescriptor): CompatibilityResult =
                CompatibilityResult.Compatible()

            override fun getLoadState(modelId: String): LoadState = LoadState.Loaded(System.currentTimeMillis())

            override suspend fun loadModel(model: ModelDescriptor): LoadResult {
                val handle = nativeRuntime.createContext(modelPath, maxContextWindow, threads)
                return if (handle == 0L) {
                    LoadResult.Failure(FailureReason.BACKEND_ERROR, "Native context creation failed")
                } else {
                    contextHandle = handle
                    LoadResult.Success(LoadState.Loaded(System.currentTimeMillis()))
                }
            }

            override fun execute(model: ModelDescriptor, input: InferenceInput) =
                kotlinx.coroutines.flow.emptyFlow<InferenceOutput>()

            override suspend fun cancel(requestId: String): CancellationResult = CancellationResult.RequestNotFound

            override suspend fun unloadModel(modelId: String): UnloadResult = UnloadResult.Success

            override fun getRuntimeState(): RuntimeState = RuntimeState(RuntimeState.Lifecycle.READY)
        }
        runtimeRegistry.register(runtime, RegistrationPolicy.REPLACE_ON_DUPLICATE)
        loadModel(descriptor)
    }

    // endregion

    // region Legacy generate() queue path

    private suspend fun consumeLoop() {
        for (internalRequest in queue.receiveChannel) {
            try {
                processOne(internalRequest)
            } finally {
                queue.markConsumed()
            }
        }
    }

    private fun processOne(ir: InferenceRequest) {
        val requestId = ir.request.requestId

        if (queue.isCancelledAndClear(requestId)) {
            deliverCancelled(ir)
            return
        }
        if (contextHandle == 0L) {
            deliverError(ir, ErrorCode.MODEL_NOT_LOADED, "No model is currently loaded")
            return
        }

        _state.value = OrchestratorState.Generating(requestId)
        try {
            runInference(ir)
        } finally {
            _state.value = OrchestratorState.Idle
        }
    }

    private fun runInference(ir: InferenceRequest) {
        val req = ir.request
        val config = req.sessionConfig
        val maxTokens = config?.maxTokens ?: DEFAULT_MAX_TOKENS
        val temperature = config?.temperature ?: DEFAULT_TEMPERATURE
        val startMs = System.currentTimeMillis()
        var firstTokenMs = -1L

        val collectedText = StringBuilder()

        val nativeResult: NativeGenerateResult = nativeRuntime.generate(
            contextHandle = contextHandle,
            prompt = req.prompt,
            maxTokens = maxTokens,
            temperature = temperature,
            tokenCallback = { token ->
                if (firstTokenMs < 0) firstTokenMs = System.currentTimeMillis() - startMs

                if (req.streaming) {
                    deliverToken(ir, token)
                } else {
                    collectedText.append(token)
                }
            },
        )

        val totalMs = System.currentTimeMillis() - startMs
        if (nativeResult.errorCode == NativeErrorCode.SUCCESS) {
            lastSuccessfulInferenceEpochMs = System.currentTimeMillis()
        }
        dispatchResult(
            ir = ir,
            result = nativeResult,
            firstTokenMs = firstTokenMs.coerceAtLeast(0),
            totalMs = totalMs,
            finalText = if (req.streaming) "" else collectedText.toString(),
        )
    }

    private fun dispatchResult(
        ir: InferenceRequest,
        result: NativeGenerateResult,
        firstTokenMs: Long,
        totalMs: Long,
        finalText: String,
    ) {
        when (result.errorCode) {
            NativeErrorCode.SUCCESS, NativeErrorCode.CANCELLED -> {
                val finishReason = mapFinishReason(result.finishReasonCode)
                emitCompletedEvent(ir, result, finishReason, firstTokenMs, totalMs)
                deliverComplete(ir, result, finishReason, finalText)
            }
            NativeErrorCode.INVALID_HANDLE ->
                deliverError(ir, ErrorCode.INTERNAL_ERROR, "Invalid native context handle")
            NativeErrorCode.OOM ->
                deliverError(ir, ErrorCode.INTERNAL_ERROR, "Out of memory during inference")
            else ->
                deliverError(ir, ErrorCode.INTERNAL_ERROR, "Native error code: ${result.errorCode}")
        }
    }

    private fun emitCompletedEvent(
        ir: InferenceRequest,
        result: NativeGenerateResult,
        reason: FinishReason,
        firstTokenMs: Long,
        totalMs: Long,
    ) {
        eventSink.onInferenceCompleted(
            modelId = activeDescriptor?.id ?: loadedModelIdInternal ?: "unknown",
            callerUid = ir.callerUid,
            promptTokenCount = result.promptTokenCount,
            generatedTokenCount = result.generatedTokenCount,
            firstTokenMs = firstTokenMs,
            totalMs = totalMs,
            finishReason = reason.name,
        )
    }

    private fun mapFinishReason(code: Int): FinishReason = when (code) {
        NativeFinishCode.MAX_TOKENS -> FinishReason.MAX_TOKENS
        NativeFinishCode.CANCELLED -> FinishReason.CANCELLED
        else -> FinishReason.COMPLETE
    }

    private fun deliverToken(ir: InferenceRequest, token: String) {
        try {
            ir.callback.onToken(ir.request.requestId, token)
        } catch (e: RemoteException) {
            logRemoteCallbackFailure(ir.request.requestId)
            nativeRuntime.cancel(contextHandle)
        }
    }

    private fun deliverComplete(
        ir: InferenceRequest,
        result: NativeGenerateResult,
        reason: FinishReason,
        finalText: String,
    ) {
        val response = GenerateResponse(
            requestId = ir.request.requestId,
            outputText = finalText,
            finishReason = reason,
            promptTokenCount = result.promptTokenCount,
            generatedTokenCount = result.generatedTokenCount,
        )
        try {
            ir.callback.onComplete(ir.request.requestId, response)
        } catch (e: RemoteException) {
            logRemoteCallbackFailure(ir.request.requestId)
        }
    }

    private fun deliverCancelled(ir: InferenceRequest) {
        val response = GenerateResponse(
            requestId = ir.request.requestId,
            outputText = "",
            finishReason = FinishReason.CANCELLED,
            promptTokenCount = 0,
            generatedTokenCount = 0,
        )
        try {
            ir.callback.onComplete(ir.request.requestId, response)
        } catch (e: RemoteException) {
            logRemoteCallbackFailure(ir.request.requestId)
        }
    }

    private fun deliverError(ir: InferenceRequest, code: ErrorCode, message: String) {
        try {
            ir.callback.onError(ir.request.requestId, EngineError(ErrorStage.INFERENCE, code, message))
        } catch (e: RemoteException) {
            logRemoteCallbackFailure(ir.request.requestId)
        }
    }

    fun cancelActive(requestId: String) {
        val current = _state.value
        if (current is OrchestratorState.Generating && current.requestId == requestId) {
            nativeRuntime.cancel(contextHandle)
        }
    }

    private fun logModelFailure(detail: CharSequence) {
        Log.e(
            TAG,
            PrivacySafeDiagnostics.contentEvent(
                operation = DiagnosticOperation.MODEL_RUNTIME,
                status = DiagnosticStatus.FAILED,
                outputContent = detail,
                errorCode = DiagnosticErrorCode.MODEL_LOAD_FAILED,
            ),
        )
    }

    private fun logRemoteCallbackFailure(requestId: String) {
        Log.w(
            TAG,
            PrivacySafeDiagnostics.contentEvent(
                operation = DiagnosticOperation.GENERATION,
                status = DiagnosticStatus.FAILED,
                requestId = requestId,
                errorCode = DiagnosticErrorCode.REMOTE_FAILURE,
            ),
        )
    }

    // endregion
}

