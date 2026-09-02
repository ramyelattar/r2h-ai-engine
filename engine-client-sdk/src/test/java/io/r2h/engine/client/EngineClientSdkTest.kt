package io.r2h.engine.client

import android.os.IBinder
import io.r2h.engine.api.IModalInferenceCallback
import io.r2h.engine.api.IR2hGenerateCallback
import io.r2h.engine.api.ISessionOrchestratorCallback
import io.r2h.engine.api.model.ClientSessionRegistration
import io.r2h.engine.api.model.EngineDashboardSnapshot
import io.r2h.engine.api.model.EngineError
import io.r2h.engine.api.model.EngineRuntimeInfo
import io.r2h.engine.api.model.EngineStateCode
import io.r2h.engine.api.model.EngineStatus
import io.r2h.engine.api.model.EngineTruthSnapshot
import io.r2h.engine.api.model.ErrorCode
import io.r2h.engine.api.model.ErrorStage
import io.r2h.engine.api.model.FinishReason
import io.r2h.engine.api.model.GenerateRequest
import io.r2h.engine.api.model.GenerateResponse
import io.r2h.engine.api.model.ModalityRuntimeState
import io.r2h.engine.api.model.ModalInferenceRequest
import io.r2h.engine.api.model.ModalInferenceResult
import io.r2h.engine.api.model.ModelLoadStatus
import io.r2h.engine.api.model.SessionContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EngineClientSdkTest {
    @Test
    fun contractConstantsMatchAcceptedPhase1Contract() {
        assertEquals("io.r2h.engine", EngineContract.ENGINE_PACKAGE)
        assertEquals("io.r2h.engine.EngineService", EngineContract.ENGINE_SERVICE_CLASS)
        assertEquals("r2h.permission.BIND_ENGINE", EngineContract.BIND_PERMISSION)
        assertEquals(3, EngineContract.API_VERSION)
    }

    @Test
    fun disconnectedStateIsDefaultShape() {
        val state = EngineConnectionState.disconnected("new client")
        assertEquals(EngineConnectionStatus.DISCONNECTED, state.status)
        assertEquals("new client", state.technicalReason)
    }

    @Test
    fun bindFailureStyleStateMapsToEngineMissingNotReady() {
        val ui = EngineUiStateMapper.fromConnection(
            EngineConnectionState(
                status = EngineConnectionStatus.ENGINE_NOT_INSTALLED,
                humanReadableMessage = "missing",
                technicalReason = "package not found",
            ),
        )
        assertEquals(EngineUiStatus.ENGINE_MISSING, ui.status)
    }

    @Test
    fun binderDeathStateMapsToReconnectUi() {
        val ui = EngineUiStateMapper.fromConnection(
            EngineConnectionState(
                status = EngineConnectionStatus.BINDER_DIED,
                humanReadableMessage = "binder died",
                technicalReason = "death recipient",
            ),
        )
        assertEquals(EngineUiStatus.BINDER_DIED_RECONNECTING, ui.status)
    }

    @Test
    fun binderDeathReconnectPolicySchedulesBoundedBackoff() {
        val policy = EngineReconnectPolicy(maxAttempts = 3, initialDelayMs = 100L)
        assertEquals(100L, policy.nextDelayMs())
        assertEquals(200L, policy.nextDelayMs())
        assertEquals(400L, policy.nextDelayMs())
        assertEquals(null, policy.nextDelayMs())
        policy.reset()
        assertEquals(100L, policy.nextDelayMs())
    }

    @Test
    fun truthSnapshotUnavailableMapsToFailure() = runTest {
        val accessor = FakeAccessor(FakeServiceFacade().apply {
            truthFailure = IllegalStateException("truth unavailable")
        })
        val result = EngineTruthClient(accessor).getCapabilities()
        assertTrue(result is EngineClientResult.Failure)
        assertEquals(EngineClientErrorCode.INTERNAL_ERROR, (result as EngineClientResult.Failure).error.code)
    }

    @Test
    fun capabilityParserDoesNotMarkUnknownCapabilityAvailable() {
        val caps = EngineCapabilityParser.parse(
            readyTruth(
                ModalityRuntimeState(
                    modality = EngineModalities.TEXT,
                    ready = true,
                    activeModelId = "text-model",
                    supportedTaskTypes = listOf("SOME_UNKNOWN_TASK"),
                ),
            ),
        )
        assertFalse(caps.textGeneration.available)
        assertEquals(CapabilityAvailability.UNAVAILABLE, caps.textGeneration.availability)
    }

    @Test
    fun capabilityParserDoesNotTreatLoadedStatusAsStillLoading() {
        val caps = EngineCapabilityParser.parse(
            readyTruth(
                ModalityRuntimeState(
                    modality = EngineModalities.TEXT,
                    ready = true,
                    activeModelId = "text-model",
                    supportedTaskTypes = listOf(EngineTaskTypes.GENERATE_TEXT),
                ),
            ),
            ModelLoadStatus(
                modelId = "text-model",
                state = "Loaded",
                progressPercent = 100,
                errorMessage = null,
            ),
        )

        assertFalse(caps.runtimeLoading)
        assertTrue(caps.textGeneration.available)
        assertEquals(CapabilityAvailability.AVAILABLE, caps.textGeneration.availability)
    }

    @Test
    fun textGenerationCallbackSuccessMapsToSuccess() = runTest {
        val service = FakeServiceFacade().apply {
            truth = readyTruth(
                ModalityRuntimeState(
                    modality = EngineModalities.TEXT,
                    ready = true,
                    activeModelId = "text-model",
                    supportedTaskTypes = listOf(EngineTaskTypes.GENERATE_TEXT),
                ),
            )
            generateHandler = { request, callback ->
                callback.onComplete(
                    request.requestId,
                    GenerateResponse(request.requestId, "engine text", FinishReason.COMPLETE, 3, 2),
                )
            }
        }
        val result = EngineInferenceClient(FakeAccessor(service)).generateText("hello")
        assertTrue(result is EngineClientResult.Success)
        assertEquals("engine text", (result as EngineClientResult.Success).value.text)
    }

    @Test
    fun textGenerationCallbackErrorMapsToEngineError() = runTest {
        val service = FakeServiceFacade().apply {
            truth = readyTruth(
                ModalityRuntimeState(
                    modality = EngineModalities.TEXT,
                    ready = true,
                    activeModelId = "text-model",
                    supportedTaskTypes = listOf(EngineTaskTypes.GENERATE_TEXT),
                ),
            )
            generateHandler = { request, callback ->
                callback.onError(
                    request.requestId,
                    EngineError(ErrorStage.INFERENCE, ErrorCode.INFERENCE_FAILED, "native failure"),
                )
            }
        }
        val result = EngineInferenceClient(FakeAccessor(service)).generateText("hello")
        assertTrue(result is EngineClientResult.Failure)
        val error = (result as EngineClientResult.Failure).error
        assertEquals(EngineClientErrorCode.ENGINE_ERROR, error.code)
        assertEquals("native failure", error.message)
    }

    @Test
    fun rateLimitedCallbackMapsToRateLimitedClientError() = runTest {
        val service = FakeServiceFacade().apply {
            truth = readyTruth(
                ModalityRuntimeState(
                    modality = EngineModalities.TEXT,
                    ready = true,
                    activeModelId = "text-model",
                    supportedTaskTypes = listOf(EngineTaskTypes.GENERATE_TEXT),
                ),
            )
            generateHandler = { request, callback ->
                callback.onError(
                    request.requestId,
                    EngineError(ErrorStage.SECURITY, ErrorCode.RATE_LIMITED, "Retry after 2500 ms."),
                )
            }
        }

        val result = EngineInferenceClient(FakeAccessor(service)).generateText("hello")

        assertTrue(result is EngineClientResult.Failure)
        val error = (result as EngineClientResult.Failure).error
        assertEquals(EngineClientErrorCode.RATE_LIMITED, error.code)
        assertEquals(ErrorCode.RATE_LIMITED, error.engineError?.code)
    }

    @Test
    fun modalInferenceRequestBuilderSetsFields() {
        val request = EngineInferenceClient(FakeAccessor(FakeServiceFacade())).buildModalRequest(
            modality = EngineModalities.IMAGE,
            taskType = EngineTaskTypes.CLASSIFICATION,
            inputRefs = listOf("content://image/1"),
            textPrompt = "classify",
            modelId = "vision",
            requestId = "req-1",
        )
        assertEquals("req-1", request.requestId)
        assertEquals(EngineModalities.IMAGE, request.modality)
        assertEquals(EngineTaskTypes.CLASSIFICATION, request.taskType)
        assertEquals(listOf("content://image/1"), request.inputRefs)
    }

    @Test
    fun timeoutReturnsStructuredTimeoutState() = runTest {
        val service = FakeServiceFacade().apply {
            truth = readyTruth(
                ModalityRuntimeState(
                    modality = EngineModalities.TEXT,
                    ready = true,
                    activeModelId = "text-model",
                    supportedTaskTypes = listOf(EngineTaskTypes.GENERATE_TEXT),
                ),
            )
            generateHandler = { _, _ -> }
        }
        val result = EngineInferenceClient(FakeAccessor(service), defaultTimeoutMs = 1).generateText("hello", timeoutMs = 1)
        assertTrue(result is EngineClientResult.Failure)
        assertEquals(EngineClientErrorCode.TIMEOUT, (result as EngineClientResult.Failure).error.code)
    }

    @Test
    fun modalTimeoutCancelsTheRemoteRequestThatOwnsItsCallback() = runTest {
        val service = FakeServiceFacade().apply {
            truth = readyTruth(
                ModalityRuntimeState(
                    modality = EngineModalities.IMAGE,
                    ready = true,
                    activeModelId = "vision-model",
                    supportedTaskTypes = listOf(EngineTaskTypes.IMAGE_UNDERSTANDING),
                ),
            )
            inferHandler = { _, _ -> }
        }

        val result = EngineInferenceClient(FakeAccessor(service)).inferImage(
            inputRefs = listOf("content://image/1"),
            timeoutMs = 1,
        )

        assertTrue(result is EngineClientResult.Failure)
        assertEquals(EngineClientErrorCode.TIMEOUT, (result as EngineClientResult.Failure).error.code)
        assertEquals(1, service.cancelledRequestIds.size)
        assertTrue(service.cancelledRequestIds.single().isNotBlank())
    }

    @Test
    fun agentTimeoutCancelsTheRemoteRequestThatOwnsItsCallback() = runTest {
        val service = FakeServiceFacade().apply {
            truth = readyTruth(
                ModalityRuntimeState(
                    modality = EngineModalities.TEXT,
                    ready = true,
                    activeModelId = "text-model",
                    supportedTaskTypes = listOf(EngineTaskTypes.GENERATE_TEXT),
                ),
            )
            agentHandler = { _, _ -> }
        }
        val context = SessionContext(
            requestId = "agent-timeout-request",
            appId = "test-client",
            userMessage = "test",
            modelId = "text-model",
        )

        val result = EngineInferenceClient(FakeAccessor(service)).runAgent(context, timeoutMs = 1)

        assertTrue(result is EngineClientResult.Failure)
        assertEquals(EngineClientErrorCode.TIMEOUT, (result as EngineClientResult.Failure).error.code)
        assertEquals(listOf(context.requestId), service.cancelledRequestIds)
    }

    @Test
    fun sessionHeartbeatStartsAfterRegistrationAndStopsOnDisconnect() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val service = FakeServiceFacade()
        val accessor = FakeAccessor(service)
        val client = EngineSessionClient(
            accessor = accessor,
            scope = CoroutineScope(dispatcher),
            heartbeatIntervalMs = 100L,
        )
        accessor.state.value = EngineConnectionState(EngineConnectionStatus.CONNECTED, "connected")
        client.start(ClientSessionRegistration(displayName = "test", packageName = "pkg"))
        testScheduler.runCurrent()
        assertEquals(EngineSessionStatus.REGISTERED, client.sessionState.value.status)
        advanceTimeBy(250L)
        assertTrue(service.heartbeatCount >= 2)
        accessor.state.value = EngineConnectionState.disconnected("lost")
        testScheduler.runCurrent()
        val heartbeatCount = service.heartbeatCount
        advanceTimeBy(250L)
        assertEquals(heartbeatCount, service.heartbeatCount)
    }

    private fun readyTruth(vararg states: ModalityRuntimeState): EngineTruthSnapshot =
        EngineTruthSnapshot(
            runtime = EngineRuntimeInfo(
                engineState = EngineStateCode.IDLE,
                readyForInference = true,
                activeModelId = states.firstOrNull()?.activeModelId,
                loadedModelIds = states.mapNotNull { it.activeModelId },
                activeTextModelId = states.firstOrNull { it.modality == EngineModalities.TEXT }?.activeModelId,
                activeImageModelId = states.firstOrNull { it.modality == EngineModalities.IMAGE }?.activeModelId,
                activeAudioModelId = states.firstOrNull { it.modality == EngineModalities.AUDIO }?.activeModelId,
            ),
            modalityState = states.toList(),
        )
}

private class FakeAccessor(private val service: EngineServiceFacade) : EngineServiceAccessor {
    val state = MutableStateFlow(EngineConnectionState.disconnected("fake"))
    override val connectionState = state

    override suspend fun <T> withService(block: suspend (EngineServiceFacade) -> T): EngineClientResult<T> =
        if (state.value.status == EngineConnectionStatus.DISCONNECTED && service !is FakeServiceFacade) {
            EngineClientResult.Failure(EngineClientError(EngineClientErrorCode.ENGINE_UNAVAILABLE, "Disconnected"))
        } else {
            try {
                EngineClientResult.Success(block(service))
            } catch (throwable: Throwable) {
                EngineClientResult.Failure(
                    EngineClientError(
                        EngineClientErrorCode.INTERNAL_ERROR,
                        throwable.message ?: "fake failure",
                        throwable::class.java.name,
                    ),
                )
            }
        }
}

private class FakeServiceFacade : EngineServiceFacade {
    override val binder: IBinder? = null
    var truth: EngineTruthSnapshot = EngineTruthSnapshot(
        runtime = EngineRuntimeInfo(EngineStateCode.UNAVAILABLE),
    )
    var truthFailure: Throwable? = null
    var generateHandler: (GenerateRequest, IR2hGenerateCallback) -> Unit = { _, _ -> }
    var inferHandler: (ModalInferenceRequest, IModalInferenceCallback) -> Unit = { request, callback ->
        callback.onResult(ModalInferenceResult(request.requestId, "TEXT", "ok"))
    }
    var agentHandler: (SessionContext, ISessionOrchestratorCallback) -> Unit = { _, _ -> }
    var heartbeatCount = 0
    val cancelledRequestIds = mutableListOf<String>()

    override fun getApiVersion(): Int = EngineContract.API_VERSION
    override fun getEngineStatus(): EngineStatus = EngineStatus(EngineStateCode.IDLE, null, 0)
    override fun getModelLoadStatus(): ModelLoadStatus = ModelLoadStatus.idle()
    override fun getEngineDashboard(): EngineDashboardSnapshot {
        throw UnsupportedOperationException("Dashboard not needed in JVM SDK tests.")
    }
    override fun getEngineTruthSnapshot(): EngineTruthSnapshot {
        truthFailure?.let { throw it }
        return truth
    }
    override fun registerClientSession(registration: ClientSessionRegistration): String = "session-1"
    override fun heartbeatClientSession(sessionId: String) {
        heartbeatCount += 1
    }
    override fun unregisterClientSession(sessionId: String) = Unit
    override fun generate(request: GenerateRequest, callback: IR2hGenerateCallback) = generateHandler(request, callback)
    override fun inferModal(request: ModalInferenceRequest, callback: IModalInferenceCallback) = inferHandler(request, callback)
    override fun runAgent(
        context: SessionContext,
        callback: ISessionOrchestratorCallback,
    ) = agentHandler(context, callback)

    override fun cancel(requestId: String) {
        cancelledRequestIds += requestId
    }
}
