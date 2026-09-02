package io.r2h.engine.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Describes a GGUF model file known to the engine's model-manager.
 * Returned as elements of the list from
 * [io.r2h.engine.api.IR2hEngineService.listInstalledModels].
 *
 * [modelId] is the stable identifier to use in [GenerateRequest.modelId] and
 * [io.r2h.engine.api.IR2hEngineService.warmup]. It is derived from the model
 * file name and is stable across engine restarts as long as the file is present.
 *
 * @param modelId      Stable identifier for the model. Matches the key used in
 *                     the model-manager manifest. Treat as opaque; do not parse.
 * @param displayName  Human-readable name suitable for UI display.
 * @param sizeBytes    Size of the GGUF file on disk in bytes. Useful for
 *                     storage budget checks before initiating a download.
 * @param quantization Quantization level string (e.g., "Q4_K_M", "Q8_0").
 *                     Informational only; the engine does not interpret this field.
 * @param isLoaded     True if this model is currently loaded into the native
 *                     inference context and ready to serve generate() calls without
 *                     a model-load delay.
 */
@Parcelize
data class ModelInfo(
    val modelId: String,
    val displayName: String,
    val sizeBytes: Long,
    val quantization: String,
    val isLoaded: Boolean,
) : Parcelable
