package io.r2h.engine.core

import android.os.Binder
import android.os.IBinder
import android.os.RemoteException
import android.os.SystemClock
import android.util.Log
import io.r2h.engine.api.IR2hEngineService
import io.r2h.engine.api.IR2hGenerateCallback
import io.r2h.engine.api.IModalInferenceCallback
import io.r2h.engine.api.ISessionOrchestratorCallback
import io.r2h.engine.api.model.SessionContext
import io.r2h.engine.api.model.ClientSessionRegistration
import io.r2h.engine.api.model.ConnectedAppInfo
import io.r2h.engine.api.model.ConnectionState
import io.r2h.engine.api.model.AiDecisionResult
import io.r2h.engine.api.model.EngineDashboardSnapshot
import io.r2h.engine.api.model.EngineError
import io.r2h.engine.api.model.EngineIntegrationInfo
import io.r2h.engine.api.model.EngineRuntimeInfo
import io.r2h.engine.api.model.EngineStatus
import io.r2h.engine.api.model.EngineTruthSnapshot
import io.r2h.engine.api.model.ErrorCode
import io.r2h.engine.api.model.ErrorStage
import io.r2h.engine.api.model.GenerateRequest
import io.r2h.engine.api.model.LiveClientSessionInfo
import io.r2h.engine.api.model.ModalInferenceRequest
import io.r2h.engine.api.model.ModalInferenceResult
import io.r2h.engine.api.model.ModalityRuntimeState
import io.r2h.engine.api.model.ModelInfo
import io.r2h.engine.api.model.ModelInstallationState
import io.r2h.engine.api.model.ModelLoadStatus
import io.r2h.engine.api.model.ModelRuntimeInfo
import io.r2h.engine.api.model.ModelRuntimeState
import io.r2h.engine.api.model.ModelValidationState
import io.r2h.engine.api.privacy.DiagnosticErrorCode
import io.r2h.engine.api.privacy.DiagnosticOperation
import io.r2h.engine.api.privacy.DiagnosticStatus
import io.r2h.engine.api.privacy.PrivacySafeDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File

private const val TAG = "EngineServiceImpl"
private const val TRACE_TAG = "AI_ENGINE_TRACE"

// Default timeout for inferModal() flow collection. Callers may override via
// request.params["engine.timeoutMs"]. Audio STT can be slow on large files;
// 5 minutes covers most practical cases without blocking indefinitely.
private const val DEFAULT_INFER_MODAL_TIMEOUT_MS = 300_000L
private const val STANDARD_INFERENCE_RATE_COST = 4
private const val AGENT_INFERENCE_RATE_COST = 6

/**
 * Implementation of the [IR2hEngineService] AIDL interface.
 *
 * All dependencies are injected via constructor so `:engine-core` remains
 * decoupled from `:security`, `:telemetry`, `:model-manager`, and concrete
 * runtime implementations. The `:app` module wires concrete implementations.
 */
class EngineServiceImpl(
    scope: CoroutineScope,
    private val runtimeRegistry: RuntimeRegistry,
    taskRouter: TaskRouter,
    private val callerRateLimiter: CallerRateLimiterPort,
    private val callerValidator: CallerValidatorPort = CallerValidatorPort {
        CallerValidationResult.Denied("CALLER_VALIDATOR_UNAVAILABLE")
    },
    private val eventSink: InferenceEventSink = InferenceEventSink.NoOp,
    private val modelResolver: ModelResolverPort = ModelResolverPort { null },
    private val modelInfoSource: suspend () -> List<ModelInfo> = { emptyList() },
    private val modelCatalogSource: suspend () -> List<ModelCatalogRecord> = { emptyList() },
    private val integrationCatalogSource: suspend () -> List<InstalledIntegrationRecord> = { emptyList() },
    private val modelDescriptorPort: ModelDescriptorPort = ModelDescriptorPort { null },
    private val modelRegistrationPort: ModelRegistrationPort = ModelRegistrationPort { file ->
        file.nameWithoutExtension
    },
    private val clientSessionRegistry: ClientSessionRegistry = ClientSessionRegistry(),
    private val enginePackageName: String = "",
    private val toolRegistry: ToolRegistry = ToolRegistry(),
    private val inputRefMaterializer: (ref: String, requestId: String, modality: String) -> String = { ref, _, _ -> ref },
    private val callingUidProvider: () -> Int = Binder::getCallingUid,
) : IR2hEngineService.Stub() {

    private val queue = RequestQueue()
    private val orchestrator = InferenceOrchestrator(
        scope = scope,
        queue = queue,
        runtimeRegistry = runtimeRegistry,
        taskRouter = taskRouter,
        eventSink = eventSink,
    )
    private val sessionOrchestrator = SessionOrchestrator(toolRegistry = toolRegistry)
    private val ioScope = scope

    @Volatile
    private var cachedModelList: List<ModelInfo> = emptyList()

    @Volatile
    private var cachedModelCatalog: List<ModelCatalogRecord> = emptyList()

    @Volatile
    private var cachedIntegrationCatalog: List<InstalledIntegrationRecord> = emptyList()

    private val requestOwnership = RequestOwnershipRegistry<IBinder>()
    private val linkedClients = mutableMapOf<IBinder, IBinder.DeathRecipient>()
    private val clientLinkLock = Any()

    init {
        refreshModelList()
        refreshModelCatalog()
        refreshIntegrationCatalog()
    }

    override fun getApiVersion(): Int {
        val caller = requireAuthorizedCaller()
        recordCallerActivity(packageName = caller.packageName, capability = "API_VERSION")
        return IR2hEngineService.API_VERSION
    }

    override fun ping(): Long {
        val caller = requireAuthorizedCaller()
        recordCallerActivity(packageName = caller.packageName, capability = "PING")
        return SystemClock.elapsedRealtime()
    }

    override fun getEngineStatus(): EngineStatus {
        val caller = requireAuthorizedCaller()
        recordCallerActivity(packageName = caller.packageName, capability = "ENGINE_STATUS")
        return orchestrator.currentStatus()
    }

    override fun listInstalledModels(): MutableList<ModelInfo> {
        val caller = requireAuthorizedCaller()
        recordCallerActivity(packageName = caller.packageName, capability = "LIST_MODELS")
        return snapshotInstalledModels()
    }

    override fun getModelLoadStatus(): ModelLoadStatus {
        val caller = requireAuthorizedCaller()
        recordCallerActivity(packageName = caller.packageName, capability = "MODEL_LOAD_STATUS")
        return orchestrator.currentLoadStatus()
    }

    override fun getEngineDashboard(): EngineDashboardSnapshot {
        val caller = requireAuthorizedCaller()
        recordCallerActivity(packageName = caller.packageName, capability = "ENGINE_DASHBOARD")
        val truth = buildTruthSnapshot()
        return EngineDashboardSnapshot(
            orchestrator.currentStatus(),
            orchestrator.currentLoadStatus(),
            snapshotInstalledModels(),
            truth.installedIntegrations.map(::toLegacyConnectedAppInfo),
        )
    }

    override fun registerClientSession(registration: ClientSessionRegistration): String {
        val callerUid = callingUidProvider()
        val caller = requireAuthorizedCaller(callerUid)
        val sessionId = clientSessionRegistry.register(
            registration = registration.copy(packageName = caller.packageName),
            verifiedPackageName = caller.packageName,
            ownerUid = callerUid,
        )
        recordCallerActivity(
            packageName = caller.packageName,
            displayName = registration.displayName,
            capability = "CLIENT_SESSION_REGISTERED",
            protocolVersion = registration.protocolVersion,
        )
        return sessionId
    }

    override fun heartbeatClientSession(sessionId: String) {
        val callerUid = callingUidProvider()
        val caller = requireAuthorizedCaller(callerUid)
        val found = clientSessionRegistry.heartbeat(sessionId, callerUid)
        if (!found) {
            Log.w(TAG, "Heartbeat ignored for a session not owned by the caller")
        }
        recordCallerActivity(
            packageName = caller.packageName,
            capability = "CLIENT_SESSION_HEARTBEAT",
        )
    }

    override fun unregisterClientSession(sessionId: String) {
        val callerUid = callingUidProvider()
        val caller = requireAuthorizedCaller(callerUid)
        clientSessionRegistry.unregister(sessionId, callerUid)
        recordCallerActivity(
            packageName = caller.packageName,
            capability = "CLIENT_SESSION_UNREGISTERED",
        )
    }

    override fun getEngineTruthSnapshot(): EngineTruthSnapshot {
        val caller = requireAuthorizedCaller()
        recordCallerActivity(packageName = caller.packageName, capability = "ENGINE_TRUTH_SNAPSHOT")
        return buildTruthSnapshot()
    }

    @get:JvmName("getEngineTruthSnapshotForTests")
    val engineTruthSnapshot: EngineTruthSnapshot get() = buildTruthSnapshot()

    override fun inferModal(request: ModalInferenceRequest, callback: IModalInferenceCallback) {
        val callerUid = callingUidProvider()
        val caller = callerValidator.validate(callerUid)

        val allowedCaller = when (caller) {
            is CallerValidationResult.Allowed -> caller
            is CallerValidationResult.Denied -> {
                logSecurityRejection(DiagnosticOperation.MODAL_INFERENCE, caller.reason)
                eventSink.onSecurityRejection(callerUid, caller.reason)
                try {
                    callback.onError(
                        EngineError(ErrorStage.SECURITY, ErrorCode.PERMISSION_DENIED,
                            "Caller is not authorized: ${caller.reason}"),
                    )
                } catch (_: RemoteException) { }
                return
            }
        }

        rateLimitDenial(callerUid, STANDARD_INFERENCE_RATE_COST)?.let { denial ->
            try {
                callback.onError(
                    EngineError(ErrorStage.SECURITY, ErrorCode.RATE_LIMITED, rateLimitMessage(denial)),
                )
            } catch (_: RemoteException) { }
            return
        }

        recordCallerActivity(packageName = allowedCaller.packageName, capability = "INFER_MODAL")

        val descriptor = runBlocking(Dispatchers.IO) {
            modelDescriptorPort.resolveDescriptor(request.modelId)
        }

        if (descriptor == null) {
            try {
                callback.onError(
                    EngineError(ErrorStage.MODEL_VALIDATION, ErrorCode.MODEL_NOT_FOUND,
                        "Model '${request.modelId}' not found."),
                )
            } catch (_: RemoteException) { }
            return
        }

        val runtime = runtimeRegistry.getRuntime(descriptor.backendKey)
        if (runtime == null) {
            try {
                callback.onError(
                    EngineError(ErrorStage.ENGINE, ErrorCode.NO_ACTIVE_RUNTIME,
                        "No runtime found for backend '${descriptor.backendKey}'."),
                )
            } catch (_: RemoteException) { }
            return
        }

        if (runtime.getLoadState(descriptor.id) !is LoadState.Loaded) {
            try {
                callback.onError(
                    EngineError(ErrorStage.ENGINE, ErrorCode.MODEL_NOT_LOADED,
                        "Model '${descriptor.id}' is not loaded in runtime '${descriptor.backendKey}'."),
                )
            } catch (_: RemoteException) { }
            return
        }

        // Capability-based task authorization: reject requests for tasks the model doesn't support.
        val requestedTaskType = request.taskType.uppercase().toTaskTypeOrNull()
        if (requestedTaskType != null &&
            descriptor.supportedTaskTypes.isNotEmpty() &&
            requestedTaskType !in descriptor.supportedTaskTypes
        ) {
            try {
                callback.onError(
                    EngineError(
                        ErrorStage.MODEL_VALIDATION,
                        ErrorCode.REQUEST_INVALID,
                        "Model '${descriptor.id}' does not support task '${request.taskType}'. " +
                            "Supported: ${descriptor.supportedTaskTypes.map { it.name }}",
                    ),
                )
            } catch (_: RemoteException) { }
            return
        }
        // Modality is a fallback safety guard only when the request task type is unknown.
        // When supportedTaskTypes is explicit, task compatibility is the source of truth.
        // Empty supportedTaskTypes means "no restriction".
        val requestedModality = request.modality.uppercase()
        if (
            requestedTaskType == null &&
            descriptor.supportedTaskTypes.isNotEmpty() &&
            requestedModality != "TEXT" &&
            requestedModality != descriptor.modality.name
        ) {
            try {
                callback.onError(
                    EngineError(
                        ErrorStage.MODEL_VALIDATION,
                        ErrorCode.REQUEST_INVALID,
                        "Request modality '$requestedModality' does not match model modality '${descriptor.modality.name}'.",
                    ),
                )
            } catch (_: RemoteException) { }
            return
        }
        // Guard: non-text artifact requests must supply at least one input artifact.
        // Without an input ref, the engine has nothing to run on.
        if (requestedModality in setOf("IMAGE", "AUDIO", "VIDEO", "MULTIMODAL") &&
            request.inputRefs.isEmpty() &&
            request.textPrompt.isNullOrBlank()
        ) {
            try {
                callback.onError(
                    EngineError(
                        ErrorStage.MODEL_VALIDATION,
                        ErrorCode.REQUEST_INVALID,
                        "A $requestedModality request must include at least one input reference in inputRefs or a text prompt.",
                    ),
                )
            } catch (_: RemoteException) { }
            return
        }

        val materializedInputRefs = try {
            request.inputRefs.map { inputRefMaterializer(it, request.requestId, requestedModality) }
        } catch (t: Throwable) {
            try {
                callback.onError(
                    EngineError(ErrorStage.MODEL_VALIDATION, ErrorCode.REQUEST_INVALID,
                        "Unable to prepare input reference: ${t.message ?: "unknown error"}"),
                )
            } catch (_: RemoteException) { }
            return
        }

        val inputParts: List<InferenceInput.Part> = buildList {
            for (ref in materializedInputRefs) {
                when (requestedModality) {
                    "IMAGE" -> add(InferenceInput.Part.Image(
                        InferenceInput.ArtifactRef(ref = ref, mimeType = "image/*"),
                    ))
                    "AUDIO" -> add(InferenceInput.Part.Audio(
                        InferenceInput.ArtifactRef(ref = ref, mimeType = "audio/wav"),
                    ))
                    "VIDEO" -> add(InferenceInput.Part.Video(
                        InferenceInput.ArtifactRef(ref = ref, mimeType = "video/*"),
                    ))
                    "MULTIMODAL" -> add(multimodalPartFor(ref, request.params))
                    else    -> { /* text prompt handled below */ }
                }
            }
            request.textPrompt?.takeIf { it.isNotBlank() }?.let {
                add(InferenceInput.Part.Text(it))
            }
            if (isEmpty()) {
                // Fallback: non-empty parts required by InferenceInput invariant
                add(InferenceInput.Part.Text(request.modelId))
            }
        }

        val task: InferenceInput.Task = when (request.taskType.uppercase()) {
            "SPEECH_TO_TEXT"      -> InferenceInput.Task.SpeechToText
            "TEXT_TO_SPEECH"      -> InferenceInput.Task.TextToSpeech
            "AUDIO_ANALYSIS"      -> InferenceInput.Task.AudioAnalysis
            "AUDIO_TAGGING"       -> InferenceInput.Task.AudioTagging
            "IMAGE_GENERATION"    -> InferenceInput.Task.ImageGeneration
            "IMAGE_UNDERSTANDING" -> InferenceInput.Task.ImageUnderstanding
            "CLASSIFICATION"      -> InferenceInput.Task.Classification
            "DETECTION",
            "OBJECT_DETECTION"    -> InferenceInput.Task.Detection
            "SEGMENTATION"        -> InferenceInput.Task.Segmentation
            "OCR"                 -> InferenceInput.Task.Ocr
            "IMAGE_TEXT_MULTIMODAL",
            "MULTIMODAL"          -> InferenceInput.Task.ImageTextMultimodal
            "VIDEO_FULL_ANALYSIS" -> InferenceInput.Task.VideoFullAnalysis
            "EMBEDDING"           -> InferenceInput.Task.Embedding
            "RERANKING"           -> InferenceInput.Task.Reranking
            else                  -> InferenceInput.Task.Chat
        }

        val inferenceInput = InferenceInput(
            requestId = request.requestId,
            task = task,
            parts = inputParts,
            metadata = request.params,
        )

        val timeoutMs = request.params["engine.timeoutMs"]?.toLongOrNull()
            ?: DEFAULT_INFER_MODAL_TIMEOUT_MS

        ioScope.launch(Dispatchers.IO) {
            try {
                withTimeout(timeoutMs) {
                    runtime.execute(descriptor, inferenceInput).collect { output ->
                        val result = mapInferenceOutputToResult(output)
                        try {
                            callback.onResult(result)
                        } catch (_: RemoteException) {
                            return@collect
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                Log.w(
                    TAG,
                    PrivacySafeDiagnostics.contentEvent(
                        operation = DiagnosticOperation.MODAL_INFERENCE,
                        status = DiagnosticStatus.FAILED,
                        requestId = request.requestId,
                        durationMs = timeoutMs,
                        errorCode = DiagnosticErrorCode.RUNTIME_FAILURE,
                    ),
                )
                runtime.cancel(request.requestId)
                try {
                    callback.onError(
                        EngineError(
                            ErrorStage.INFERENCE,
                            ErrorCode.INFERENCE_TIMEOUT,
                            "Request timed out after ${timeoutMs}ms.",
                        ),
                    )
                } catch (_: RemoteException) { }
            } catch (t: Throwable) {
                Log.e(
                    TAG,
                    PrivacySafeDiagnostics.failureEvent(
                        operation = DiagnosticOperation.MODAL_INFERENCE,
                        requestId = request.requestId,
                        errorCode = DiagnosticErrorCode.RUNTIME_FAILURE,
                        failure = t,
                    ),
                )
                try {
                    callback.onError(
                        EngineError(ErrorStage.INFERENCE, ErrorCode.INFERENCE_FAILED, t.message ?: "Unknown error"),
                    )
                } catch (_: RemoteException) { }
            }
        }

        clientSessionRegistry.recordRequestStarted(allowedCaller.packageName)
    }

    override fun generateText(request: GenerateRequest, callback: IR2hGenerateCallback) {
        generate(request, callback)
    }

    override fun analyzeImage(request: ModalInferenceRequest, callback: IModalInferenceCallback) {
        inferModal(request.withCapabilityDefaults("IMAGE", "IMAGE_UNDERSTANDING"), callback)
    }

    override fun transcribeSpeech(request: ModalInferenceRequest, callback: IModalInferenceCallback) {
        inferModal(request.withCapabilityDefaults("AUDIO", "SPEECH_TO_TEXT"), callback)
    }

    override fun synthesizeSpeech(request: ModalInferenceRequest, callback: IModalInferenceCallback) {
        inferModal(request.withCapabilityDefaults("AUDIO", "TEXT_TO_SPEECH"), callback)
    }

    override fun analyzeAudio(request: ModalInferenceRequest, callback: IModalInferenceCallback) {
        inferModal(request.withCapabilityDefaults("AUDIO", "AUDIO_ANALYSIS"), callback)
    }

    override fun analyzeVideo(request: ModalInferenceRequest, callback: IModalInferenceCallback) {
        inferModal(request.withCapabilityDefaults("VIDEO", "VIDEO_ANALYSIS"), callback)
    }

    override fun generateMultimodal(request: ModalInferenceRequest, callback: IModalInferenceCallback) {
        inferModal(request.withCapabilityDefaults("MULTIMODAL", "IMAGE_UNDERSTANDING"), callback)
    }

    override fun runOcr(request: ModalInferenceRequest, callback: IModalInferenceCallback) {
        inferModal(request.withCapabilityDefaults("IMAGE", "OCR"), callback)
    }

    override fun createEmbedding(request: ModalInferenceRequest, callback: IModalInferenceCallback) {
        inferModal(request.withCapabilityDefaults("TEXT", "EMBEDDING"), callback)
    }

    override fun runAgent(context: SessionContext, callback: ISessionOrchestratorCallback) {
        val callerUid = callingUidProvider()
        val caller = callerValidator.validate(callerUid)

        val allowedCaller = when (caller) {
            is CallerValidationResult.Allowed -> caller
            is CallerValidationResult.Denied -> {
                logSecurityRejection(DiagnosticOperation.AGENT, caller.reason)
                eventSink.onSecurityRejection(callerUid, caller.reason)
                try {
                    callback.onError(
                        EngineError(
                            ErrorStage.SECURITY,
                            ErrorCode.PERMISSION_DENIED,
                            "Caller is not authorized: ${caller.reason}",
                        ),
                    )
                } catch (_: RemoteException) { }
                return
            }
        }

        rateLimitDenial(callerUid, AGENT_INFERENCE_RATE_COST)?.let { denial ->
            try {
                callback.onError(
                    EngineError(ErrorStage.SECURITY, ErrorCode.RATE_LIMITED, rateLimitMessage(denial)),
                )
            } catch (_: RemoteException) { }
            return
        }

        recordCallerActivity(packageName = allowedCaller.packageName, capability = "RUN_AGENT")

        val modelId = context.modelId.takeIf { it.isNotBlank() } ?: orchestrator.activeModelId()
        if (modelId == null) {
            try {
                callback.onError(
                    EngineError(
                        ErrorStage.ENGINE,
                        ErrorCode.MODEL_NOT_LOADED,
                        "No model is loaded. Load a TEXT model before calling runAgent.",
                    ),
                )
            } catch (_: RemoteException) { }
            return
        }

        val descriptor = runBlocking(Dispatchers.IO) {
            modelDescriptorPort.resolveDescriptor(modelId)
        }
        if (descriptor == null) {
            try {
                callback.onError(
                    EngineError(
                        ErrorStage.MODEL_VALIDATION,
                        ErrorCode.MODEL_NOT_FOUND,
                        "Model '$modelId' not found.",
                    ),
                )
            } catch (_: RemoteException) { }
            return
        }

        if (descriptor.modality != Modality.TEXT) {
            try {
                callback.onError(
                    EngineError(
                        ErrorStage.MODEL_VALIDATION,
                        ErrorCode.REQUEST_INVALID,
                        "runAgent requires a loaded TEXT planner model. Model '$modelId' has modality ${descriptor.modality}.",
                    ),
                )
            } catch (_: RemoteException) { }
            return
        }

        val runtime = runtimeRegistry.getRuntime(descriptor.backendKey)
        if (runtime == null) {
            try {
                callback.onError(
                    EngineError(
                        ErrorStage.ENGINE,
                        ErrorCode.NO_ACTIVE_RUNTIME,
                        "No runtime found for backend '${descriptor.backendKey}'.",
                    ),
                )
            } catch (_: RemoteException) { }
            return
        }

        if (runtime.getLoadState(descriptor.id) !is LoadState.Loaded) {
            try {
                callback.onError(
                    EngineError(
                        ErrorStage.ENGINE,
                        ErrorCode.MODEL_NOT_LOADED,
                        "Model '$modelId' is not loaded in runtime '${descriptor.backendKey}'.",
                    ),
                )
            } catch (_: RemoteException) { }
            return
        }

        val timeoutMs = context.params["engine.timeoutMs"]?.toLongOrNull()
            ?: DEFAULT_INFER_MODAL_TIMEOUT_MS

        ioScope.launch(Dispatchers.IO) {
            try {
                withTimeout(timeoutMs) {
                    executeLocalAgent(context, descriptor, runtime, callback)
                }
            } catch (_: TimeoutCancellationException) {
                Log.w(
                    TAG,
                    PrivacySafeDiagnostics.contentEvent(
                        operation = DiagnosticOperation.AGENT,
                        status = DiagnosticStatus.FAILED,
                        requestId = context.requestId,
                        durationMs = timeoutMs,
                        errorCode = DiagnosticErrorCode.RUNTIME_FAILURE,
                    ),
                )
                try {
                    callback.onError(
                        EngineError(
                            ErrorStage.INFERENCE,
                            ErrorCode.INFERENCE_TIMEOUT,
                            "Agent request timed out after ${timeoutMs}ms.",
                        ),
                    )
                } catch (_: RemoteException) { }
            } catch (t: Throwable) {
                Log.e(
                    TAG,
                    PrivacySafeDiagnostics.failureEvent(
                        operation = DiagnosticOperation.AGENT,
                        requestId = context.requestId,
                        errorCode = DiagnosticErrorCode.RUNTIME_FAILURE,
                        failure = t,
                    ),
                )
                try {
                    callback.onError(
                        EngineError(
                            ErrorStage.INFERENCE,
                            ErrorCode.INFERENCE_FAILED,
                            t.message ?: "Agent execution failed.",
                        ),
                    )
                } catch (_: RemoteException) { }
            }
        }
    }

    override fun orchestrateSession(context: SessionContext, callback: ISessionOrchestratorCallback) {
        val callerUid = callingUidProvider()
        val caller = callerValidator.validate(callerUid)

        val allowedCaller = when (caller) {
            is CallerValidationResult.Allowed -> caller
            is CallerValidationResult.Denied -> {
                logSecurityRejection(DiagnosticOperation.AGENT, caller.reason)
                eventSink.onSecurityRejection(callerUid, caller.reason)
                try {
                    callback.onError(
                        EngineError(
                            ErrorStage.SECURITY,
                            ErrorCode.PERMISSION_DENIED,
                            "Caller is not authorized: ${caller.reason}",
                        ),
                    )
                } catch (_: RemoteException) { }
                return
            }
        }

        rateLimitDenial(callerUid, AGENT_INFERENCE_RATE_COST)?.let { denial ->
            try {
                callback.onError(
                    EngineError(ErrorStage.SECURITY, ErrorCode.RATE_LIMITED, rateLimitMessage(denial)),
                )
            } catch (_: RemoteException) { }
            return
        }

        recordCallerActivity(packageName = allowedCaller.packageName, capability = "ORCHESTRATE_SESSION")

        // Resolve which model and runtime to use for this orchestration call.
        val modelId = context.modelId.takeIf { it.isNotBlank() } ?: orchestrator.activeModelId()
        if (modelId == null) {
            try {
                callback.onError(
                    EngineError(
                        ErrorStage.ENGINE,
                        ErrorCode.MODEL_NOT_LOADED,
                        "No model is loaded. Load a model before calling orchestrateSession.",
                    ),
                )
            } catch (_: RemoteException) { }
            return
        }

        val descriptor = runBlocking(Dispatchers.IO) {
            modelDescriptorPort.resolveDescriptor(modelId)
        }
        if (descriptor == null) {
            try {
                callback.onError(
                    EngineError(
                        ErrorStage.MODEL_VALIDATION,
                        ErrorCode.MODEL_NOT_FOUND,
                        "Model '$modelId' not found.",
                    ),
                )
            } catch (_: RemoteException) { }
            return
        }

        val runtime = runtimeRegistry.getRuntime(descriptor.backendKey)
        if (runtime == null) {
            try {
                callback.onError(
                    EngineError(
                        ErrorStage.ENGINE,
                        ErrorCode.NO_ACTIVE_RUNTIME,
                        "No runtime found for backend '${descriptor.backendKey}'.",
                    ),
                )
            } catch (_: RemoteException) { }
            return
        }

        if (runtime.getLoadState(descriptor.id) !is LoadState.Loaded) {
            try {
                callback.onError(
                    EngineError(
                        ErrorStage.ENGINE,
                        ErrorCode.MODEL_NOT_LOADED,
                        "Model '$modelId' is not loaded in runtime '${descriptor.backendKey}'.",
                    ),
                )
            } catch (_: RemoteException) { }
            return
        }

        val timeoutMs = context.params["engine.timeoutMs"]?.toLongOrNull()
            ?: DEFAULT_INFER_MODAL_TIMEOUT_MS

        ioScope.launch(Dispatchers.IO) {
            try {
                withTimeout(timeoutMs) {
                    sessionOrchestrator.orchestrate(context, runtime, descriptor, callback)
                }
            } catch (_: TimeoutCancellationException) {
                Log.w(
                    TAG,
                    PrivacySafeDiagnostics.contentEvent(
                        operation = DiagnosticOperation.AGENT,
                        status = DiagnosticStatus.FAILED,
                        requestId = context.requestId,
                        durationMs = timeoutMs,
                        errorCode = DiagnosticErrorCode.RUNTIME_FAILURE,
                    ),
                )
                try {
                    callback.onError(
                        EngineError(
                            ErrorStage.INFERENCE,
                            ErrorCode.INFERENCE_TIMEOUT,
                            "Orchestration request timed out after ${timeoutMs}ms.",
                        ),
                    )
                } catch (_: RemoteException) { }
            } catch (t: Throwable) {
                Log.e(
                    TAG,
                    PrivacySafeDiagnostics.failureEvent(
                        operation = DiagnosticOperation.AGENT,
                        requestId = context.requestId,
                        errorCode = DiagnosticErrorCode.RUNTIME_FAILURE,
                        failure = t,
                    ),
                )
                try {
                    callback.onError(
                        EngineError(
                            ErrorStage.INFERENCE,
                            ErrorCode.INFERENCE_FAILED,
                            t.message ?: "Orchestration failed.",
                        ),
                    )
                } catch (_: RemoteException) { }
            }
        }
    }

    private suspend fun executeLocalAgent(
        context: SessionContext,
        descriptor: ModelDescriptor,
        runtime: BackendRuntime,
        callback: ISessionOrchestratorCallback,
    ) {
        val plannerPrompt = buildLocalAgentPlannerPrompt(context)
        val plannerInput = InferenceInput(
            requestId = context.requestId,
            task = InferenceInput.Task.Chat,
            parts = listOf(InferenceInput.Part.Text(plannerPrompt)),
            metadata = context.params + mapOf(
                "engine.agent" to "true",
                "engine.agentPlanner" to "local-text-runtime",
                "engine.maxTokens" to "256",
                "engine.temperature" to "0.1",
            ),
        )

        val plannerOutput = StringBuilder()
        runtime.execute(descriptor, plannerInput).collect { output ->
            output.items.filterIsInstance<InferenceOutput.Item.Text>().forEach { item ->
                plannerOutput.append(item.text)
                try {
                    callback.onThinkingChunk(item.text)
                } catch (_: RemoteException) { }
            }
        }

        val toolLog = mutableListOf<String>()
        val expression = extractMultiplicationExpression(context.userMessage)
        val calculation = expression?.let { (left, right) ->
            val result = left * right
            toolLog += "calculator: $left * $right = $result"
            "$left * $right = $result"
        } ?: run {
            toolLog += "calculator: no supported arithmetic expression found"
            "no supported arithmetic expression found"
        }

        val truth = buildTruthSnapshot()
        val textReady = isTextReadyInSnapshot(truth)
        val engineStatus = buildString {
            append("engineState=").append(truth.runtime.engineState.name)
            append("; readyForInference=").append(truth.runtime.readyForInference)
            append("; activeTextModelId=").append(truth.runtime.activeTextModelId ?: "NONE")
            append("; loadedModels=").append(truth.runtime.loadedModelIds.joinToString(prefix = "[", postfix = "]"))
        }
        toolLog += "engine_status_inspector: $engineStatus"

        val plannerPreview = plannerOutput.toString()
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(300)
            .ifBlank { "planner produced no text" }

        val finalAnswer = buildString {
            append("Engine status: ").append(engineStatus).append('\n')
            append("Calculation: ").append(calculation).append('\n')
            append("TEXT ready: ").append(if (textReady) "YES" else "NO").append('\n')
            append("Tool calls: ").append(toolLog.joinToString(" | "))
        }

        val result = AiDecisionResult(
            requestId = context.requestId,
            decisionType = AiDecisionResult.TYPE_TEXT_RESPONSE,
            textResponse = finalAnswer,
            confidence = 1.0f,
            reasoning = "Local agent planner called through ${descriptor.backendKey}; deterministic tools executed locally. Planner preview: $plannerPreview",
        )

        try {
            callback.onDecision(result)
        } catch (_: RemoteException) {
            logRemoteCallbackFailure(DiagnosticOperation.AGENT, context.requestId)
        }
    }

    private fun buildLocalAgentPlannerPrompt(context: SessionContext): String = buildString {
        appendLine("You are the local R2H AI Engine agent planner.")
        appendLine("Plan which local tools are needed. Available tools:")
        appendLine("- calculator: evaluates simple arithmetic.")
        appendLine("- engine_status_inspector: reads the local engine truth snapshot.")
        appendLine("Return a short plan only. Do not invent tool results.")
        appendLine("User task:")
        appendLine(context.userMessage)
        if (context.appState.isNotBlank()) {
            appendLine("App state:")
            appendLine(context.appState)
        }
    }

    private fun extractMultiplicationExpression(message: String): Pair<Long, Long>? {
        val match = Regex("""(\d+)\s*(?:\*|x|X)\s*(\d+)""").find(message) ?: return null
        val left = match.groupValues[1].toLongOrNull() ?: return null
        val right = match.groupValues[2].toLongOrNull() ?: return null
        return left to right
    }

    private fun isTextReadyInSnapshot(snapshot: EngineTruthSnapshot): Boolean {
        val textReady = snapshot.modalityState.any { state ->
            state.modality == "TEXT" && state.ready && !state.activeModelId.isNullOrBlank()
        }
        return textReady || (
            snapshot.runtime.readyForInference &&
                !snapshot.runtime.activeTextModelId.isNullOrBlank() &&
                snapshot.runtime.activeTextModelId in snapshot.runtime.loadedModelIds
        )
    }

    fun refreshModelList() {
        ioScope.launch(Dispatchers.IO) {
            cachedModelList = modelInfoSource()
        }
    }

    fun refreshModelCatalog() {
        ioScope.launch(Dispatchers.IO) {
            cachedModelCatalog = modelCatalogSource()
        }
    }

    fun refreshIntegrationCatalog() {
        ioScope.launch(Dispatchers.IO) {
            cachedIntegrationCatalog = integrationCatalogSource()
        }
    }

    fun loadInstalledModel(descriptor: ModelDescriptor) {
        refreshModelCatalog()
        orchestrator.loadModel(descriptor)
    }

    override fun generate(request: GenerateRequest, callback: IR2hGenerateCallback) {
        val callerUid = callingUidProvider()
        val caller = callerValidator.validate(callerUid)

        Log.d(
            TRACE_TAG,
            PrivacySafeDiagnostics.contentEvent(
                operation = DiagnosticOperation.GENERATION,
                status = DiagnosticStatus.STARTED,
                requestId = request.requestId,
                inputContent = request.prompt,
            ),
        )

        val allowedCaller = when (caller) {
            is CallerValidationResult.Allowed -> caller
            is CallerValidationResult.Denied -> {
                logSecurityRejection(DiagnosticOperation.GENERATION, caller.reason)
                eventSink.onSecurityRejection(callerUid, caller.reason)
                deliverError(
                    callback = callback,
                    requestId = request.requestId,
                    stage = ErrorStage.SECURITY,
                    code = ErrorCode.PERMISSION_DENIED,
                    message = "Caller is not authorized: ${caller.reason}",
                )
                return
            }
        }

        val rateLimitDenial = rateLimitDenial(callerUid, STANDARD_INFERENCE_RATE_COST)
        if (rateLimitDenial != null) {
            deliverError(
                callback = callback,
                requestId = request.requestId,
                stage = ErrorStage.SECURITY,
                code = ErrorCode.RATE_LIMITED,
                message = rateLimitMessage(rateLimitDenial),
            )
            return
        }

        recordCallerActivity(
            packageName = allowedCaller.packageName,
            capability = "GENERATE",
        )

        if (request.prompt.isBlank()) {
            Log.w(
                TAG,
                PrivacySafeDiagnostics.contentEvent(
                    operation = DiagnosticOperation.GENERATION,
                    status = DiagnosticStatus.REJECTED,
                    requestId = request.requestId,
                    inputContent = request.prompt,
                    errorCode = DiagnosticErrorCode.INVALID_INPUT,
                ),
            )
            deliverError(
                callback = callback,
                requestId = request.requestId,
                stage = ErrorStage.INFERENCE,
                code = ErrorCode.REQUEST_INVALID,
                message = "Prompt must not be empty.",
            )
            return
        }

        val clientBinder = callback.asBinder()
        val tracingCallback = object : IR2hGenerateCallback.Stub() {
            override fun onToken(requestId: String, token: String) {
                callback.onToken(requestId, token)
            }

            override fun onComplete(
                requestId: String,
                response: io.r2h.engine.api.model.GenerateResponse,
            ) {
                Log.d(
                    TRACE_TAG,
                    PrivacySafeDiagnostics.contentEvent(
                        operation = DiagnosticOperation.GENERATION,
                        status = DiagnosticStatus.COMPLETED,
                        requestId = requestId,
                        outputContent = response.outputText,
                        recordCount = response.generatedTokenCount,
                    ),
                )
                try {
                    callback.onComplete(requestId, response)
                } finally {
                    untrackRequest(clientBinder, requestId)
                }
            }

            override fun onError(
                requestId: String,
                error: io.r2h.engine.api.model.EngineError,
            ) {
                Log.d(
                    TRACE_TAG,
                    PrivacySafeDiagnostics.contentEvent(
                        operation = DiagnosticOperation.GENERATION,
                        status = DiagnosticStatus.FAILED,
                        requestId = requestId,
                        outputContent = error.message,
                        errorCode = DiagnosticErrorCode.RUNTIME_FAILURE,
                    ),
                )
                try {
                    callback.onError(requestId, error)
                } finally {
                    untrackRequest(clientBinder, requestId)
                }
            }
        }

        val internalRequest = InferenceRequest(
            request = request,
            callback = tracingCallback,
            callerUid = callerUid,
        )

        when (
            trackClient(
                binder = clientBinder,
                requestId = request.requestId,
                ownerUid = callerUid,
                packageName = allowedCaller.packageName,
            )
        ) {
            RequestRegistrationResult.REGISTERED -> Unit
            RequestRegistrationResult.DUPLICATE_REQUEST_ID -> {
                deliverError(
                    callback = callback,
                    requestId = request.requestId,
                    stage = ErrorStage.INFERENCE,
                    code = ErrorCode.REQUEST_INVALID,
                    message = "Request ID is already in use.",
                )
                return
            }
            RequestRegistrationResult.CAPACITY_REACHED -> {
                deliverError(
                    callback = callback,
                    requestId = request.requestId,
                    stage = ErrorStage.ENGINE,
                    code = ErrorCode.QUEUE_FULL,
                    message = "Request ownership capacity is full.",
                )
                return
            }
            RequestRegistrationResult.CLIENT_UNAVAILABLE -> {
                deliverError(
                    callback = callback,
                    requestId = request.requestId,
                    stage = ErrorStage.ENGINE,
                    code = ErrorCode.REQUEST_INVALID,
                    message = "Client callback is unavailable.",
                )
                return
            }
        }

        if (!queue.tryEnqueue(internalRequest)) {
            Log.w(
                TAG,
                PrivacySafeDiagnostics.contentEvent(
                    operation = DiagnosticOperation.GENERATION,
                    status = DiagnosticStatus.REJECTED,
                    requestId = request.requestId,
                    errorCode = DiagnosticErrorCode.RUNTIME_FAILURE,
                ),
            )
            eventSink.onQueueFull(callerUid)
            deliverError(
                callback = callback,
                requestId = request.requestId,
                stage = ErrorStage.ENGINE,
                code = ErrorCode.QUEUE_FULL,
                message = "Request queue is full.",
            )
            discardRequestOwnership(clientBinder, request.requestId)
            return
        }

        clientSessionRegistry.recordRequestStarted(allowedCaller.packageName)
    }

    override fun cancel(requestId: String) {
        val callerUid = callingUidProvider()
        val caller = requireAuthorizedCaller(callerUid)
        val ownership = requestOwnership.removeIfOwned(requestId, callerUid)
        if (ownership == null) {
            Log.w(TAG, "Cancel ignored for an unknown request or non-owner caller")
            return
        }
        releaseClientLinkIfIdle(ownership.client)
        recordCallerActivity(packageName = caller.packageName, capability = "CANCEL")
        queue.cancel(requestId)
        orchestrator.cancelActive(requestId)
        clientSessionRegistry.recordRequestFinished(ownership.packageName)
    }

    override fun warmup(modelId: String) {
        val caller = requireAuthorizedCaller()
        recordCallerActivity(
            packageName = caller.packageName,
            capability = "WARMUP",
        )
        ioScope.launch(Dispatchers.IO) {
            val path = modelResolver.resolveValidatedPath(modelId)
            if (path == null) {
                Log.e(
                    TAG,
                    PrivacySafeDiagnostics.contentEvent(
                        operation = DiagnosticOperation.MODEL_RUNTIME,
                        status = DiagnosticStatus.FAILED,
                        inputContent = modelId,
                        errorCode = DiagnosticErrorCode.MODEL_LOAD_FAILED,
                    ),
                )
                return@launch
            }

            val descriptor = modelDescriptorPort.resolveDescriptor(modelId)
                ?: buildFallbackDescriptor(modelId, path)

            orchestrator.loadModel(descriptor = descriptor)
        }
    }

    override fun registerImportedModel(absolutePath: String): String {
        val caller = requireAuthorizedCaller()
        recordCallerActivity(
            packageName = caller.packageName,
            capability = "REGISTER_IMPORTED_MODEL",
        )

        require(absolutePath.isNotBlank()) { "absolutePath must not be blank." }
        val file = File(absolutePath)
        return runBlocking(Dispatchers.IO) {
            val modelId = modelRegistrationPort.registerLocalModel(file)
            cachedModelList = modelInfoSource()
            cachedModelCatalog = modelCatalogSource()
            modelId
        }
    }

    fun notifyModelAdded(targetFile: File): String {
        return runBlocking(Dispatchers.IO) {
            val modelId = modelRegistrationPort.registerLocalModel(targetFile)
            cachedModelList = modelInfoSource()
            cachedModelCatalog = modelCatalogSource()
            modelId
        }
    }

    private fun requireAuthorizedCaller(
        callerUid: Int = callingUidProvider(),
    ): CallerValidationResult.Allowed {
        return when (val result = callerValidator.validate(callerUid)) {
            is CallerValidationResult.Allowed -> result
            is CallerValidationResult.Denied -> throw SecurityException(result.reason)
        }
    }

    private fun recordCallerActivity(
        packageName: String,
        displayName: String = packageName,
        capability: String,
        protocolVersion: Int? = null,
    ) {
        if (packageName.isBlank() || packageName == enginePackageName) return
        clientSessionRegistry.recordCallerActivity(
            packageName = packageName,
            displayName = displayName,
            capability = capability,
            protocolVersion = protocolVersion,
        )
    }

    private fun trackClient(
        binder: IBinder,
        requestId: String,
        ownerUid: Int,
        packageName: String,
    ): RequestRegistrationResult = synchronized(clientLinkLock) {
        val registration = requestOwnership.register(
            requestId = requestId,
            ownerUid = ownerUid,
            packageName = packageName,
            client = binder,
        )
        if (registration != RequestRegistrationResult.REGISTERED) {
            return@synchronized registration
        }

        if (!linkedClients.containsKey(binder)) {
            try {
                linkedClients[binder] = linkDeathRecipient(binder)
            } catch (_: RemoteException) {
                requestOwnership.removeAllForClient(binder)
                return@synchronized RequestRegistrationResult.CLIENT_UNAVAILABLE
            }
        }
        RequestRegistrationResult.REGISTERED
    }

    @Throws(RemoteException::class)
    private fun linkDeathRecipient(binder: IBinder): IBinder.DeathRecipient {
        val recipient = object : IBinder.DeathRecipient {
            override fun binderDied() = onClientDied(binder)
        }
        binder.linkToDeath(recipient, 0)
        return recipient
    }

    private fun onClientDied(binder: IBinder) {
        Log.i(TAG, "Client binder died — cancelling its pending requests")
        val ownerships = synchronized(clientLinkLock) {
            linkedClients.remove(binder)
            requestOwnership.removeAllForClient(binder)
        }
        for (ownership in ownerships) {
            queue.cancel(ownership.requestId)
            orchestrator.cancelActive(ownership.requestId)
            clientSessionRegistry.recordRequestFinished(ownership.packageName)
        }
    }

    private fun rateLimitDenial(
        callerUid: Int,
        cost: Int,
    ): CallerRateLimitResult.Denied? = try {
        callerRateLimiter.tryAcquire(callerUid, cost) as? CallerRateLimitResult.Denied
    } catch (failure: Throwable) {
        Log.e(
            TAG,
            PrivacySafeDiagnostics.failureEvent(
                operation = DiagnosticOperation.SECURITY,
                errorCode = DiagnosticErrorCode.SECURITY_REJECTED,
                failure = failure,
            ),
        )
        CallerRateLimitResult.Denied(retryAfterMs = 1_000L)
    }

    private fun rateLimitMessage(denial: CallerRateLimitResult.Denied): String =
        "Caller rate limit exceeded. Retry after ${denial.retryAfterMs} ms."

    private fun untrackRequest(
        binder: IBinder,
        requestId: String,
    ) {
        val ownership = synchronized(clientLinkLock) {
            requestOwnership.removeForTerminalEvent(requestId, binder).also {
                releaseClientLinkIfIdleLocked(binder)
            }
        }
        ownership?.packageName?.let(clientSessionRegistry::recordRequestFinished)
    }

    private fun discardRequestOwnership(
        binder: IBinder,
        requestId: String,
    ) {
        synchronized(clientLinkLock) {
            requestOwnership.removeForTerminalEvent(requestId, binder)
            releaseClientLinkIfIdleLocked(binder)
        }
    }

    private fun releaseClientLinkIfIdle(binder: IBinder) {
        synchronized(clientLinkLock) {
            releaseClientLinkIfIdleLocked(binder)
        }
    }

    private fun releaseClientLinkIfIdleLocked(binder: IBinder) {
        if (requestOwnership.hasRequestsForClient(binder)) return
        val recipient = linkedClients.remove(binder) ?: return
        runCatching { binder.unlinkToDeath(recipient, 0) }
    }

    private fun deliverError(
        callback: IR2hGenerateCallback,
        requestId: String,
        stage: ErrorStage,
        code: ErrorCode,
        message: String,
    ) {
        try {
            callback.onError(requestId, EngineError(stage, code, message))
        } catch (_: RemoteException) {
            logRemoteCallbackFailure(DiagnosticOperation.ENGINE_SERVICE, requestId)
        }
    }

    private fun snapshotInstalledModels(): MutableList<ModelInfo> {
        val activeModelId = orchestrator.activeModelId()
        return cachedModelList.map { model ->
            val isLoaded = model.modelId == activeModelId
            if (model.isLoaded == isLoaded) {
                model
            } else {
                ModelInfo(
                    model.modelId,
                    model.displayName,
                    model.sizeBytes,
                    model.quantization,
                    isLoaded,
                )
            }
        }.toMutableList()
    }

    private fun snapshotModelCatalog(): List<ModelCatalogRecord> {
        return runBlocking(Dispatchers.IO) {
            runCatching { modelCatalogSource() }
                .onFailure { failure ->
                    Log.w(
                        TAG,
                        PrivacySafeDiagnostics.failureEvent(
                            operation = DiagnosticOperation.MODEL_RUNTIME,
                            errorCode = DiagnosticErrorCode.RUNTIME_FAILURE,
                            failure = failure,
                        ),
                    )
                }
                .getOrElse { cachedModelCatalog }
        }
    }

    private fun snapshotIntegrationCatalog(): List<InstalledIntegrationRecord> {
        return runBlocking(Dispatchers.IO) {
            runCatching { integrationCatalogSource() }
                .onFailure { failure ->
                    Log.w(
                        TAG,
                        PrivacySafeDiagnostics.failureEvent(
                            operation = DiagnosticOperation.ENGINE_SERVICE,
                            errorCode = DiagnosticErrorCode.RUNTIME_FAILURE,
                            failure = failure,
                        ),
                    )
                }
                .getOrElse { cachedIntegrationCatalog }
        }
    }

    private fun logSecurityRejection(
        operation: DiagnosticOperation,
        reason: CharSequence,
    ) {
        Log.w(
            TAG,
            PrivacySafeDiagnostics.contentEvent(
                operation = operation,
                status = DiagnosticStatus.REJECTED,
                outputContent = reason,
                errorCode = DiagnosticErrorCode.SECURITY_REJECTED,
            ),
        )
    }

    private fun logRemoteCallbackFailure(
        operation: DiagnosticOperation,
        requestId: String,
    ) {
        Log.w(
            TAG,
            PrivacySafeDiagnostics.contentEvent(
                operation = operation,
                status = DiagnosticStatus.FAILED,
                requestId = requestId,
                errorCode = DiagnosticErrorCode.REMOTE_FAILURE,
            ),
        )
    }

    private fun buildTruthSnapshot(): EngineTruthSnapshot {
        val modelCatalog = snapshotModelCatalog()
        val liveSessionRecords = clientSessionRegistry.snapshotLiveSessions()
        val packageHistory = clientSessionRegistry.snapshotPackageHistory()
        val activeModelId = orchestrator.activeModelId()
        val activeBackendId = orchestrator.activeBackendId()
        val currentLoadStatus = orchestrator.currentLoadStatus()

        val loadedModelIds = modelCatalog
            .map { it.descriptor.id }
            .filter(::isModelLoaded)

        // Build modality state first so per-modality active model IDs are available
        // when constructing EngineRuntimeInfo. Calling buildModalityState() after the
        // runtime constructor would leave activeTextModelId/activeImageModelId/
        // activeAudioModelId null — that was the Stage 1 bug this fixes.
        val modalityState = buildModalityState(modelCatalog)

        val runtime = EngineRuntimeInfo(
            engineState = orchestrator.currentStatus().state,
            activeBackendId = activeBackendId,
            activeBackendLabel = orchestrator.activeBackendLabel(),
            activeModelId = activeModelId,
            readyForInference = orchestrator.isReadyForInference(),
            activeRequestCount = orchestrator.currentStatus().activeRequestCount,
            loadedModelIds = loadedModelIds,
            lastSuccessfulInferenceEpochMs = orchestrator.lastSuccessfulInferenceEpochMs(),
            lastError = orchestrator.lastError(),
            activeTextModelId = modalityState.find { it.modality == "TEXT" }?.activeModelId,
            activeImageModelId = modalityState.find { it.modality == "IMAGE" }?.activeModelId,
            activeAudioModelId = modalityState.find { it.modality == "AUDIO" }?.activeModelId,
        )

        val models = modelCatalog.map { record ->
            val isActive = record.descriptor.id == activeModelId
            val runtimeLoadState = runtimeRegistry.getRuntime(record.descriptor.backendKey)?.getLoadState(record.descriptor.id)
            val runtimeState = when {
                isActive && currentLoadStatus.state in setOf("Preparing", "Loading") -> ModelRuntimeState.LOADING
                currentLoadStatus.modelId == record.descriptor.id && currentLoadStatus.state == "Failed" -> ModelRuntimeState.FAILED
                runtimeLoadState is LoadState.Failed -> ModelRuntimeState.FAILED
                isActive && record.descriptor.id in loadedModelIds -> ModelRuntimeState.ACTIVE
                record.descriptor.id in loadedModelIds -> ModelRuntimeState.LOADED
                !record.installed -> ModelRuntimeState.UNLOADED
                record.validationState == ModelValidationState.INVALID -> ModelRuntimeState.UNLOADED
                isModelLoadable(record.descriptor) -> ModelRuntimeState.LOADABLE
                else -> ModelRuntimeState.UNLOADED
            }

            val loadabilityError = if (
                record.installed &&
                record.validationState == ModelValidationState.VALID &&
                runtimeState == ModelRuntimeState.UNLOADED
            ) {
                EngineError(
                    ErrorStage.MODEL_VALIDATION,
                    ErrorCode.MODEL_NOT_LOADABLE,
                    "No compatible runtime is available for model ${record.descriptor.id}.",
                )
            } else {
                null
            }

            val currentError = when {
                currentLoadStatus.modelId == record.descriptor.id && currentLoadStatus.state == "Failed" ->
                    parseLoadStatusError(currentLoadStatus.errorMessage)
                else -> null
            }

            val runtimeLoadFailureError = when (val failedState = runtimeLoadState) {
                is LoadState.Failed -> parseLoadStatusError(
                    "${failedState.reason.name}: ${failedState.message ?: failedState.reason.name}",
                )
                else -> null
            }

            ModelRuntimeInfo(
                modelId = record.descriptor.id,
                displayName = record.descriptor.displayName,
                backendId = record.descriptor.backendKey,
                backendLabel = activeBackendId
                    ?.takeIf { isActive }
                    ?.let { orchestrator.activeBackendLabel() }
                    ?: record.descriptor.backendKey,
                fileSizeBytes = record.fileSizeBytes,
                installationState = if (record.installed) {
                    ModelInstallationState.INSTALLED
                } else {
                    ModelInstallationState.MISSING
                },
                validationState = record.validationState,
                runtimeState = runtimeState,
                ready = isActive && orchestrator.isReadyForInference(),
                lastError = currentError ?: runtimeLoadFailureError ?: record.validationError ?: loadabilityError,
            )
        }.sortedBy { it.displayName.lowercase() }

        val liveSessions = liveSessionRecords.map { session ->
            LiveClientSessionInfo(
                sessionId = session.sessionId,
                displayName = session.displayName,
                packageName = session.packageName,
                connectionState = ConnectionState.CONNECTED,
                connectedAtEpochMs = session.connectedAtEpochMs,
                lastSeenEpochMs = session.lastSeenEpochMs,
                protocolVersion = session.protocolVersion,
                capabilities = session.capabilities,
                activeRequestCount = session.activeRequestCount,
                lastError = session.lastError,
            )
        }

        val installedIntegrations = snapshotIntegrationCatalog().map { record ->
            val history = packageHistory[record.packageName]
            val hasLiveSession = liveSessions.any { it.packageName == record.packageName }
            EngineIntegrationInfo(
                displayName = record.displayName,
                packageName = record.packageName,
                installedIntegration = record.installedIntegration,
                trustedIntegration = record.trustedIntegration,
                discoverableIntegration = record.discoverableIntegration,
                connectionState = when {
                    hasLiveSession -> ConnectionState.CONNECTED
                    history?.connectionState == ConnectionState.STALE -> ConnectionState.STALE
                    else -> ConnectionState.DISCONNECTED
                },
                lastSeenEpochMs = history?.lastSeenEpochMs,
                protocolVersion = history?.protocolVersion,
                capabilities = history?.capabilities ?: emptyList(),
                lastError = record.lastError ?: history?.lastError,
            )
        }.sortedBy { it.displayName.lowercase() }

        val lastErrors = buildList {
            runtime.lastError?.let(::add)
            models.mapNotNull { it.lastError }.forEach(::add)
            installedIntegrations.mapNotNull { it.lastError }.forEach(::add)
            liveSessions.mapNotNull { it.lastError }.forEach(::add)
        }.distinctBy { "${it.stage}:${it.code}:${it.message}" }

        return EngineTruthSnapshot(
            runtime = runtime,
            models = models,
            installedIntegrations = installedIntegrations,
            liveClientSessions = liveSessions,
            lastErrors = lastErrors,
            modalityState = modalityState,
        )
    }

    private fun isModelLoaded(modelId: String): Boolean {
        return runtimeRegistry.getAllRuntimes()
            .any { runtime -> runtime.getLoadState(modelId) is LoadState.Loaded }
    }

    private fun isModelLoadable(descriptor: ModelDescriptor): Boolean {
        val runtime = runtimeRegistry.getRuntime(descriptor.backendKey) ?: return false
        return runtime.assessCompatibility(descriptor) is CompatibilityResult.Compatible
    }

    private fun toLegacyConnectedAppInfo(info: EngineIntegrationInfo): ConnectedAppInfo {
        val status = when (info.connectionState) {
            ConnectionState.CONNECTED -> "Connected"
            ConnectionState.STALE -> "Stale"
            ConnectionState.DISCONNECTED -> {
                if (!info.installedIntegration) "NotInstalled" else "Disconnected"
            }
        }
        return ConnectedAppInfo(
            info.displayName,
            info.packageName,
            info.installedIntegration,
            info.trustedIntegration,
            info.discoverableIntegration,
            status,
            info.lastSeenEpochMs,
            info.capabilities,
            info.connectionState.name,
        )
    }

    private fun buildModalityState(
        modelCatalog: List<ModelCatalogRecord>,
    ): List<ModalityRuntimeState> {
        val modalityGroups = modelCatalog.groupBy { it.descriptor.modality.name }

        return Modality.entries.map { modality ->
            val group = modalityGroups[modality.name] ?: emptyList()

            // Use the first non-null backend key found for this modality's models.
            val runtimeKey = group.firstOrNull()?.descriptor?.backendKey
            val runtime = runtimeKey?.let { runtimeRegistry.getRuntime(it) }
            val runtimeState = runtime?.getRuntimeState()

            // Determine loaded models by querying the modality's runtime directly.
            // This correctly reports per-runtime active models for image/audio runtimes
            // that are independent of the orchestrator's single "activeModelId" cursor.
            val loadedInModality = if (runtime != null) {
                group
                    .map { it.descriptor.id }
                    .filter { runtime.getLoadState(it) is LoadState.Loaded }
            } else {
                // Fallback: use the globally-loaded snapshot when no runtime is registered yet.
                val globalLoadedIds = group
                    .map { it.descriptor.id }
                    .filter(::isModelLoaded)
                globalLoadedIds
            }

            // The active model in this modality is the first loaded model.
            val activeInModality = loadedInModality.firstOrNull()

            // Derive the supported task types from the loaded models' descriptors so that
            // clients can discover what they can request without inspecting each model.
            // Empty supportedTaskTypes on a descriptor means "no restriction" — we propagate
            // that as an empty union so the client knows to try any task type.
            val loadedDescriptors = group
                .filter { it.descriptor.id in loadedInModality }
                .map { it.descriptor }
            val supportedTaskTypes = loadedDescriptors
                .flatMap { it.supportedTaskTypes }
                .flatMap(::taskTypeTruthNames)
                .distinct()
                .sorted()

            val lastRuntimeError = runtimeState?.lastFailure?.let { failure ->
                EngineError(
                    ErrorStage.BACKEND,
                    ErrorCode.BACKEND_INIT_FAILED,
                    failure.message ?: failure.reason.name,
                )
            }

            ModalityRuntimeState(
                modality = modality.name,
                runtimeKey = runtimeKey,
                runtimeLabel = runtimeKey,
                ready = runtimeState?.lifecycle == RuntimeState.Lifecycle.READY ||
                    runtimeState?.lifecycle == RuntimeState.Lifecycle.BUSY,
                activeModelId = activeInModality,
                loadedModelIds = loadedInModality,
                activeRequestCount = runtimeState?.activeRequestCount ?: 0,
                lastError = lastRuntimeError,
                supportedTaskTypes = supportedTaskTypes,
            )
        }
    }

    private fun multimodalPartFor(
        ref: String,
        params: Map<String, String>,
    ): InferenceInput.Part {
        val mimeHint = params["input.mime"]
            ?: params["engine.inputMime"]
            ?: ref.substringAfterLast('.', missingDelimiterValue = "")
        val normalized = mimeHint.lowercase()
        return when {
            normalized.startsWith("audio/") || normalized in setOf("wav", "m4a", "mp3", "aac", "flac") ->
                InferenceInput.Part.Audio(InferenceInput.ArtifactRef(ref = ref, mimeType = "audio/*"))
            normalized.startsWith("video/") || normalized in setOf("mp4", "webm", "mkv", "mov") ->
                InferenceInput.Part.Video(InferenceInput.ArtifactRef(ref = ref, mimeType = "video/*"))
            else ->
                InferenceInput.Part.Image(InferenceInput.ArtifactRef(ref = ref, mimeType = "image/*"))
        }
    }

    private fun taskTypeTruthNames(taskType: TaskType): List<String> =
        if (taskType == TaskType.TEXT_GENERATION) {
            listOf(taskType.name, "GENERATE_TEXT")
        } else {
            listOf(taskType.name)
        }

    private fun mapInferenceOutputToResult(output: InferenceOutput): ModalInferenceResult {
        val finishReason = when (val c = output.completion) {
            is InferenceOutput.CompletionStatus.Terminal -> c.reason.name
            InferenceOutput.CompletionStatus.InProgress -> "STREAMING"
        }

        val textItem = output.items.filterIsInstance<InferenceOutput.Item.Text>().firstOrNull()
        val labelsItem = output.items.filterIsInstance<InferenceOutput.Item.Labels>().firstOrNull()
        val embeddingItem = output.items.filterIsInstance<InferenceOutput.Item.Embedding>().firstOrNull()

        return when {
            labelsItem != null -> ModalInferenceResult(
                requestId = output.requestId,
                resultType = "LABELS",
                labelScores = labelsItem.values.map { "${it.label}:${it.score}" },
                finishReason = finishReason,
            )
            embeddingItem != null -> ModalInferenceResult(
                requestId = output.requestId,
                resultType = "EMBEDDING",
                embeddingJson = embeddingItem.values.joinToString(",", "[", "]"),
                finishReason = finishReason,
            )
            textItem != null -> ModalInferenceResult(
                requestId = output.requestId,
                resultType = "TEXT",
                textValue = textItem.text,
                finishReason = finishReason,
            )
            else -> ModalInferenceResult(
                requestId = output.requestId,
                resultType = "TEXT",
                textValue = "",
                finishReason = finishReason,
            )
        }
    }

    private fun parseLoadStatusError(message: String?): EngineError {
        val raw = message?.trim().orEmpty()
        val normalized = raw.substringBefore(":", raw).trim()
        val detail = raw.substringAfter(":", raw).trim().ifBlank { raw.ifBlank { "Model load failed." } }
        val code = when (normalized) {
            "IMPORT_INVALID_STRUCTURE" -> ErrorCode.IMPORT_INVALID_STRUCTURE
            "IMPORT_MISSING_SIDECARS" -> ErrorCode.IMPORT_MISSING_SIDECARS
            "TOKENIZER_UNSUPPORTED" -> ErrorCode.TOKENIZER_UNSUPPORTED
            "TOKENIZER_INIT_FAILED" -> ErrorCode.TOKENIZER_INIT_FAILED
            "MODEL_CONTRACT_UNSUPPORTED" -> ErrorCode.MODEL_CONTRACT_UNSUPPORTED
            // Modality-specific contract failures surfaced by RealImageOnnxEngine and
            // RealWhisperOnnxEngine when the loaded model has no valid tensor I/O contract.
            "IMAGE_CONTRACT_UNSUPPORTED"  -> ErrorCode.IMAGE_CONTRACT_UNSUPPORTED
            "AUDIO_PROCESSOR_UNSUPPORTED" -> ErrorCode.AUDIO_PROCESSOR_UNSUPPORTED
            "BACKEND_SELECTION_FAILED" -> ErrorCode.BACKEND_SELECTION_FAILED
            "BACKEND_INIT_FAILED" -> ErrorCode.BACKEND_INIT_FAILED
            "SESSION_CREATION_FAILED" -> ErrorCode.SESSION_CREATION_FAILED
            else -> ErrorCode.SESSION_CREATION_FAILED
        }
        return EngineError(ErrorStage.MODEL_LOADING, code, detail)
    }

    private fun buildFallbackDescriptor(
        modelId: String,
        path: String,
    ): ModelDescriptor {
        val lowerPath = path.lowercase()

        val backendKey = when {
            lowerPath.endsWith(".litertlm") -> "litert-lm"
            lowerPath.endsWith(".gguf") -> "llama-cpp"
            lowerPath.endsWith(".onnx") -> "onnx"
            else -> "litert-lm"
        }

        val format = when {
            lowerPath.endsWith(".litertlm") -> "litertlm"
            lowerPath.endsWith(".gguf") -> "gguf"
            lowerPath.endsWith(".onnx") -> "onnx"
            else -> "unknown"
        }

        val modelType = when {
            lowerPath.endsWith(".onnx") -> ModelType.GENERIC_TASK
            else -> ModelType.TEXT_GENERATION
        }

        val capabilities = buildSet {
            add(ModelCapability.Input.Text)
            add(ModelCapability.Output.Text)
            add(ModelCapability.Execution.Local)
            add(ModelCapability.Lifecycle.ExplicitLoad)
            add(ModelCapability.Lifecycle.ExplicitUnload)

            if (modelType == ModelType.TEXT_GENERATION) {
                add(ModelCapability.Interaction.Streaming)
                add(ModelCapability.Interaction.Cancellation)
                add(ModelCapability.Interaction.SystemInstruction)
            }
        }

        return ModelDescriptor(
            id = modelId,
            displayName = modelId,
            version = "local",
            modelType = modelType,
            capabilities = capabilities,
            backendKey = backendKey,
            source = ModelDescriptor.Source.Local(
                artifactRef = path,
                format = format,
            ),
            executionTarget = ModelDescriptor.ExecutionTarget.LOCAL,
            isEnabled = true,
            isSelectable = true,
            maturity = ModelDescriptor.Maturity.PRODUCTION,
            constraints = ModelDescriptor.Constraints(
                maxContextWindow = DEFAULT_CONTEXT_LENGTH,
            ),
            metadata = emptyMap(),
        )
    }

    private companion object {
        const val DEFAULT_CONTEXT_LENGTH = 2048
    }
}

/** Maps a raw task-type string (already uppercased) to the corresponding [TaskType], or null if unrecognised. */
private fun String.toTaskTypeOrNull(): TaskType? = when (this) {
    "CHAT"                -> TaskType.CHAT
    "TEXT_GENERATION"     -> TaskType.TEXT_GENERATION
    "SUMMARIZATION"       -> TaskType.SUMMARIZATION
    "REWRITING"           -> TaskType.REWRITING
    "EXTRACTION"          -> TaskType.EXTRACTION
    "QUESTION_ANSWERING"  -> TaskType.QUESTION_ANSWERING
    "IMAGE_UNDERSTANDING" -> TaskType.IMAGE_UNDERSTANDING
    "IMAGE_TEXT_MULTIMODAL",
    "MULTIMODAL"          -> TaskType.IMAGE_TEXT_MULTIMODAL
    "IMAGE_GENERATION"    -> TaskType.IMAGE_GENERATION
    "VIDEO_FULL_ANALYSIS" -> TaskType.VIDEO_FULL_ANALYSIS
    "EMBEDDING"           -> TaskType.EMBEDDING
    "RERANKING"           -> TaskType.RERANKING
    "CLASSIFICATION"      -> TaskType.CLASSIFICATION
    "DETECTION",
    "OBJECT_DETECTION"    -> TaskType.OBJECT_DETECTION
    "SEGMENTATION"        -> TaskType.SEGMENTATION
    "OCR"                 -> TaskType.OCR
    "SPEECH_TO_TEXT"      -> TaskType.SPEECH_TO_TEXT
    "TEXT_TO_SPEECH"      -> TaskType.TEXT_TO_SPEECH
    "AUDIO_ANALYSIS"      -> TaskType.AUDIO_ANALYSIS
    "AUDIO_TAGGING"       -> TaskType.AUDIO_TAGGING
    else                  -> null
}

private fun ModalInferenceRequest.withCapabilityDefaults(
    modality: String,
    defaultTaskType: String,
): ModalInferenceRequest =
    copy(
        modality = modality,
        taskType = taskType.ifBlank { defaultTaskType },
    )
