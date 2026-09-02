package io.r2h.engine.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * The authoritative runtime truth for the engine at a point in time.
 *
 * Clients should consume this instead of individual getEngineStatus() /
 * listInstalledModels() calls. It is a consistent snapshot: all fields
 * are sampled atomically so there are no cross-field race conditions.
 *
 * [runtime]              — engine process health and active model per modality.
 * [models]               — per-model installation, validation and runtime states.
 * [installedIntegrations]— known client apps and their connection states.
 * [liveClientSessions]   — actively registered client sessions right now.
 * [lastErrors]           — de-duplicated errors from all subsystems.
 * [modalityState]        — per-modality ready state, active model, and supported tasks.
 */
@Parcelize
data class EngineTruthSnapshot(
    val runtime: EngineRuntimeInfo,
    val models: List<ModelRuntimeInfo> = emptyList(),
    val installedIntegrations: List<EngineIntegrationInfo> = emptyList(),
    val liveClientSessions: List<LiveClientSessionInfo> = emptyList(),
    val lastErrors: List<EngineError> = emptyList(),
    val modalityState: List<ModalityRuntimeState> = emptyList(),
) : Parcelable
