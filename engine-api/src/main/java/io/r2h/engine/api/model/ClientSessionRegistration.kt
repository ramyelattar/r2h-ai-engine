package io.r2h.engine.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ClientSessionRegistration(
    val displayName: String,
    val packageName: String = "",
    val protocolVersion: Int = 1,
    val capabilities: List<String> = emptyList(),
) : Parcelable
