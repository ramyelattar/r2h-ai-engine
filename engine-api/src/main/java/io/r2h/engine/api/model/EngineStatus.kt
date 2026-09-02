package io.r2h.engine.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * A point-in-time snapshot of the engine's operational state.
 * Returned by [io.r2h.engine.api.IR2hEngineService.getEngineStatus].
 *
 * This is a snapshot; the engine state may change immediately after the call
 * returns. Clients that need to react to state transitions should poll at a
 * reasonable interval (suggested: 1s) or subscribe to a higher-level wrapper
 * that manages polling internally (e.g., EngineClient in r2h-magician).
 *
 * @param state              Current operational state. Never null.
 * @param loadedModelId      The [ModelInfo.modelId] of the model currently held
 *                           in the native context, or null if no model is loaded.
 * @param activeRequestCount Number of requests currently queued or being processed.
 *                           Useful for load-shedding decisions on the client side.
 */
@Parcelize
data class EngineStatus(
    val state: EngineStateCode,
    val loadedModelId: String?,
    val activeRequestCount: Int,
) : Parcelable
