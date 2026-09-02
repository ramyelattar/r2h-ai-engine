package io.r2h.engine.client

import io.r2h.engine.api.model.EngineStatus
import io.r2h.engine.api.model.EngineTruthSnapshot
import io.r2h.engine.api.model.ModelLoadStatus

class EngineTruthClient internal constructor(
    private val accessor: EngineServiceAccessor,
) {
    constructor(connector: EngineServiceConnector) : this(connector.accessor)

    suspend fun getEngineStatus(): EngineClientResult<EngineStatus> =
        accessor.withService { it.getEngineStatus() }

    suspend fun getModelLoadStatus(): EngineClientResult<ModelLoadStatus> =
        accessor.withService { it.getModelLoadStatus() }

    suspend fun getTruthSnapshot(): EngineClientResult<EngineTruthSnapshot> =
        accessor.withService { it.getEngineTruthSnapshot() }

    suspend fun getCapabilities(): EngineClientResult<EngineCapabilityState> {
        val truth = getTruthSnapshot()
        if (truth is EngineClientResult.Failure) {
            return EngineClientResult.Failure(truth.error)
        }
        val load = getModelLoadStatus()
        val loadStatus = (load as? EngineClientResult.Success)?.value
        return EngineClientResult.Success(
            EngineCapabilityParser.parse((truth as EngineClientResult.Success).value, loadStatus),
        )
    }
}
