package io.r2h.engine.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * A single result frame emitted by [io.r2h.engine.api.IModalInferenceCallback.onResult].
 *
 * [resultType]: "TEXT", "LABELS", or "EMBEDDING".
 * [textValue]: populated for TEXT and SPEECH_TO_TEXT results.
 * [labelScores]: populated for CLASSIFICATION/DETECTION as "label:score" strings.
 * [embeddingJson]: populated for EMBEDDING results as a JSON float array.
 * [finishReason]: "STREAMING" (more frames coming) or a terminal reason name.
 */
@Parcelize
data class ModalInferenceResult(
    val requestId: String,
    val resultType: String,
    val textValue: String? = null,
    val labelScores: List<String> = emptyList(),
    val embeddingJson: String? = null,
    val finishReason: String = "COMPLETE",
) : Parcelable
