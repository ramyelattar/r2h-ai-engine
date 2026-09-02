package io.r2h.engine.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ModelRuntimeInfo(
    val modelId: String,
    val displayName: String,
    val backendId: String,
    val backendLabel: String,
    val fileSizeBytes: Long,
    val installationState: ModelInstallationState,
    val validationState: ModelValidationState,
    val runtimeState: ModelRuntimeState,
    val ready: Boolean,
    val lastError: EngineError? = null,
) : Parcelable
