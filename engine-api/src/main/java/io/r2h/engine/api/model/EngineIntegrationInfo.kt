package io.r2h.engine.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class EngineIntegrationInfo(
    val displayName: String,
    val packageName: String,
    val installedIntegration: Boolean,
    val trustedIntegration: Boolean,
    val discoverableIntegration: Boolean,
    val connectionState: ConnectionState,
    val lastSeenEpochMs: Long? = null,
    val protocolVersion: Int? = null,
    val capabilities: List<String> = emptyList(),
    val lastError: EngineError? = null,
) : Parcelable
