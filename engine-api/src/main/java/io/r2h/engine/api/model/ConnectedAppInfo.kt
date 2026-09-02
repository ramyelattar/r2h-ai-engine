package io.r2h.engine.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** Legacy dashboard transfer object. Prefer [EngineIntegrationInfo] for new code. */
@Parcelize
data class ConnectedAppInfo(
    val displayName: String,
    val packageName: String,
    val installedIntegration: Boolean,
    val trustedIntegration: Boolean,
    val discoverableIntegration: Boolean,
    val status: String,
    val lastSeenEpochMs: Long? = null,
    val capabilities: List<String> = emptyList(),
    val connectionStateName: String = "DISCONNECTED",
) : Parcelable
