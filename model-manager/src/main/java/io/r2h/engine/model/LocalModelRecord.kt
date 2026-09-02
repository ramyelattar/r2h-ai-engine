package io.r2h.engine.model

/**
 * Persistent record describing a supported local model file that has been registered with
 * the model-manager. Stored in [ModelManifest] and never crosses the IPC boundary
 * directly — it is converted to [io.r2h.engine.api.model.ModelInfo] before being
 * returned to clients.
 *
 * @param modelId       Stable identifier derived from the file name (without extension).
 *                      Treat as opaque; do not parse or construct manually.
 * @param displayName   Human-readable name for UI display.
 * @param absolutePath  Absolute path to the model file inside the app's private storage.
 *                      Must never be a path in external shared storage.
 * @param sizeBytes     File size at registration time, in bytes.
 * @param sha256        Expected SHA-256 hex digest. Validated on every load.
 * @param quantization  Quantization type string (e.g. "Q4_K_M"). Informational only.
 */
data class LocalModelRecord(
    val modelId: String,
    val displayName: String,
    val absolutePath: String,
    val sizeBytes: Long,
    val sha256: String,
    val quantization: String,
)
