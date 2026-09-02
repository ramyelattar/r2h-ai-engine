package io.r2h.engine.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * A multimodal inference request submitted via [io.r2h.engine.api.IR2hEngineService.inferModal].
 *
 * [modality] must match one of: TEXT, IMAGE, AUDIO, MULTIMODAL.
 * [taskType] maps to a specific inference task (e.g. SPEECH_TO_TEXT, IMAGE_UNDERSTANDING).
 * [inputRefs] are file paths or URIs for non-text modalities; required for IMAGE and AUDIO.
 * [params] carries optional engine hints, e.g. {"engine.timeoutMs": "60000"}.
 */
@Parcelize
data class ModalInferenceRequest(
    val requestId: String,
    val modelId: String,
    val modality: String,
    val taskType: String,
    val inputRefs: List<String> = emptyList(),
    val textPrompt: String? = null,
    val params: Map<String, String> = emptyMap(),
) : Parcelable
