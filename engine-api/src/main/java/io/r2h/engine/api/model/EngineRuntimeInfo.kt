package io.r2h.engine.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class EngineRuntimeInfo(
    val engineState: EngineStateCode,
    val activeBackendId: String? = null,
    val activeBackendLabel: String? = null,
    val activeModelId: String? = null,
    val readyForInference: Boolean = false,
    val activeRequestCount: Int = 0,
    val loadedModelIds: List<String> = emptyList(),
    val lastSuccessfulInferenceEpochMs: Long? = null,
    val lastError: EngineError? = null,
    val activeTextModelId: String? = null,
    val activeImageModelId: String? = null,
    val activeAudioModelId: String? = null,
) : Parcelable
