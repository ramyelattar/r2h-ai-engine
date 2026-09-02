package io.r2h.engine.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Per-modality runtime snapshot included in [EngineTruthSnapshot].
 *
 * Allows clients to query readiness, active model, and last error
 * independently per modality (TEXT, IMAGE, AUDIO, MULTIMODAL).
 *
 * [supportedTaskTypes] is the union of task types declared by all currently-loaded
 * models for this modality. Clients should use this list to determine which task
 * types they can request without risking a REQUEST_INVALID rejection — e.g. an
 * IMAGE modality entry might expose ["IMAGE_UNDERSTANDING", "CLASSIFICATION"] while
 * an AUDIO entry exposes ["SPEECH_TO_TEXT"]. An empty list means no model is loaded
 * or the loaded model declared no task restrictions (any task accepted).
 */
@Parcelize
data class ModalityRuntimeState(
    val modality: String,
    val runtimeKey: String? = null,
    val runtimeLabel: String? = null,
    val ready: Boolean = false,
    val activeModelId: String? = null,
    val loadedModelIds: List<String> = emptyList(),
    val activeRequestCount: Int = 0,
    val lastError: EngineError? = null,
    /** Union of task types supported by all currently-loaded models for this modality. */
    val supportedTaskTypes: List<String> = emptyList(),
) : Parcelable
