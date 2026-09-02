package io.r2h.engine.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** Legacy dashboard snapshot. Prefer [EngineTruthSnapshot] for new code. */
@Parcelize
data class EngineDashboardSnapshot(
    val status: EngineStatus,
    val loadStatus: ModelLoadStatus,
    val installedModels: List<ModelInfo> = emptyList(),
    val connectedApps: List<ConnectedAppInfo> = emptyList(),
) : Parcelable
