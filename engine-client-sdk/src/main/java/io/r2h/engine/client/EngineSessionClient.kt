package io.r2h.engine.client

import io.r2h.engine.api.model.ClientSessionRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class EngineSessionStatus {
    NOT_REGISTERED,
    REGISTERING,
    REGISTERED,
    ERROR,
}

data class EngineSessionState(
    val status: EngineSessionStatus,
    val sessionId: String? = null,
    val error: EngineClientError? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

class EngineSessionClient internal constructor(
    private val accessor: EngineServiceAccessor,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    private val heartbeatIntervalMs: Long = 15_000L,
) {
    constructor(
        connector: EngineServiceConnector,
        scope: CoroutineScope = CoroutineScope(SupervisorJob()),
        heartbeatIntervalMs: Long = 15_000L,
    ) : this(connector.accessor, scope, heartbeatIntervalMs)

    private val _sessionState = MutableStateFlow(EngineSessionState(EngineSessionStatus.NOT_REGISTERED))
    val sessionState: StateFlow<EngineSessionState> = _sessionState.asStateFlow()

    private var monitorJob: Job? = null
    private var heartbeatJob: Job? = null
    private var registration: ClientSessionRegistration? = null

    fun start(registration: ClientSessionRegistration) {
        this.registration = registration
        monitorJob?.cancel()
        monitorJob = scope.launch {
            accessor.connectionState.collect { state ->
                if (state.status == EngineConnectionStatus.CONNECTED) {
                    if (_sessionState.value.status != EngineSessionStatus.REGISTERED) {
                        register(registration)
                    }
                } else {
                    stopHeartbeat()
                    _sessionState.value = EngineSessionState(EngineSessionStatus.NOT_REGISTERED)
                }
            }
        }
    }

    suspend fun registerNow(registration: ClientSessionRegistration): EngineClientResult<String> =
        register(registration)

    suspend fun stop() {
        monitorJob?.cancel()
        monitorJob = null
        stopHeartbeat()
        val sessionId = _sessionState.value.sessionId
        if (sessionId != null) {
            accessor.withService { it.unregisterClientSession(sessionId) }
        }
        _sessionState.value = EngineSessionState(EngineSessionStatus.NOT_REGISTERED)
    }

    private suspend fun register(registration: ClientSessionRegistration): EngineClientResult<String> {
        _sessionState.value = EngineSessionState(EngineSessionStatus.REGISTERING)
        val result = accessor.withService { it.registerClientSession(registration) }
        return when (result) {
            is EngineClientResult.Success -> {
                _sessionState.value = EngineSessionState(EngineSessionStatus.REGISTERED, result.value)
                startHeartbeat(result.value)
                result
            }
            is EngineClientResult.Failure -> {
                _sessionState.value = EngineSessionState(EngineSessionStatus.ERROR, error = result.error)
                result
            }
        }
    }

    private fun startHeartbeat(sessionId: String) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                delay(heartbeatIntervalMs)
                val result = accessor.withService { it.heartbeatClientSession(sessionId) }
                if (result is EngineClientResult.Failure) {
                    _sessionState.value = EngineSessionState(EngineSessionStatus.ERROR, sessionId, result.error)
                    stopHeartbeat()
                    break
                }
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }
}
