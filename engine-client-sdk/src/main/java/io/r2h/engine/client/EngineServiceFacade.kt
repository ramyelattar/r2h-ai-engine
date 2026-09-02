package io.r2h.engine.client

import android.os.IBinder
import io.r2h.engine.api.IModalInferenceCallback
import io.r2h.engine.api.IR2hEngineService
import io.r2h.engine.api.IR2hGenerateCallback
import io.r2h.engine.api.ISessionOrchestratorCallback
import io.r2h.engine.api.model.ClientSessionRegistration
import io.r2h.engine.api.model.EngineDashboardSnapshot
import io.r2h.engine.api.model.EngineStatus
import io.r2h.engine.api.model.EngineTruthSnapshot
import io.r2h.engine.api.model.GenerateRequest
import io.r2h.engine.api.model.ModalInferenceRequest
import io.r2h.engine.api.model.ModelLoadStatus
import io.r2h.engine.api.model.SessionContext

internal interface EngineServiceFacade {
    val binder: IBinder?
    fun getApiVersion(): Int
    fun getEngineStatus(): EngineStatus
    fun getModelLoadStatus(): ModelLoadStatus
    fun getEngineDashboard(): EngineDashboardSnapshot
    fun getEngineTruthSnapshot(): EngineTruthSnapshot
    fun registerClientSession(registration: ClientSessionRegistration): String
    fun heartbeatClientSession(sessionId: String)
    fun unregisterClientSession(sessionId: String)
    fun generate(request: GenerateRequest, callback: IR2hGenerateCallback)
    fun inferModal(request: ModalInferenceRequest, callback: IModalInferenceCallback)
    fun runAgent(context: SessionContext, callback: ISessionOrchestratorCallback)
    fun cancel(requestId: String)
}

internal class BinderEngineServiceFacade(
    private val service: IR2hEngineService,
    override val binder: IBinder,
) : EngineServiceFacade {
    override fun getApiVersion(): Int = service.apiVersion
    override fun getEngineStatus(): EngineStatus = service.engineStatus
    override fun getModelLoadStatus(): ModelLoadStatus = service.modelLoadStatus
    override fun getEngineDashboard(): EngineDashboardSnapshot = service.engineDashboard
    override fun getEngineTruthSnapshot(): EngineTruthSnapshot = service.engineTruthSnapshot
    override fun registerClientSession(registration: ClientSessionRegistration): String =
        service.registerClientSession(registration)
    override fun heartbeatClientSession(sessionId: String) = service.heartbeatClientSession(sessionId)
    override fun unregisterClientSession(sessionId: String) = service.unregisterClientSession(sessionId)
    override fun generate(request: GenerateRequest, callback: IR2hGenerateCallback) =
        service.generate(request, callback)
    override fun inferModal(request: ModalInferenceRequest, callback: IModalInferenceCallback) =
        service.inferModal(request, callback)
    override fun runAgent(context: SessionContext, callback: ISessionOrchestratorCallback) =
        service.runAgent(context, callback)
    override fun cancel(requestId: String) = service.cancel(requestId)
}

internal interface EngineServiceAccessor {
    val connectionState: kotlinx.coroutines.flow.StateFlow<EngineConnectionState>
    suspend fun <T> withService(block: suspend (EngineServiceFacade) -> T): EngineClientResult<T>
}
