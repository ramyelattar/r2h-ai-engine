package io.r2h.engine.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class LiveClientSessionInfo(
    val sessionId: String,
    val displayName: String,
    val packageName: String,
    val connectionState: ConnectionState,
    val connectedAtEpochMs: Long,
    val lastSeenEpochMs: Long,
    val protocolVersion: Int,
    val capabilities: List<String>,
    val activeRequestCount: Int,
    val lastError: EngineError? = null,
) : Parcelable
