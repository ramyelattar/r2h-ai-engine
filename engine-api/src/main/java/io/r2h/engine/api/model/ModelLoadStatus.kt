package io.r2h.engine.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class ModelLoadStatus(
    val modelId: String?,
    val state: String,
    val progressPercent: Int = 0,
    val errorMessage: String? = null,
) : Parcelable {
    companion object {
        fun idle(): ModelLoadStatus = ModelLoadStatus(modelId = null, state = "Idle")
    }
}
