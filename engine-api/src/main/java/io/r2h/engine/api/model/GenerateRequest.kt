package io.r2h.engine.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Describes a single generation job submitted to the engine via
 * [io.r2h.engine.api.IR2hEngineService.generate].
 *
 * Each request must carry a [requestId] that is unique within the client's session.
 * The engine returns all callbacks with the same [requestId] so callers can correlate
 * responses when multiple requests are in-flight.
 *
 * @param requestId     Caller-assigned unique identifier for this request.
 *                      UUID v4 format is recommended. Duplicate IDs within the same
 *                      client session are rejected with INTERNAL_ERROR.
 * @param modelId       Identifier of the model to use, matching [ModelInfo.modelId].
 *                      The model must be installed and loaded; call
 *                      [io.r2h.engine.api.IR2hEngineService.listInstalledModels] to
 *                      enumerate available models.
 * @param prompt        Fully-constructed prompt string. The engine applies no template
 *                      or system-prompt wrapping; the caller is responsible for format.
 * @param streaming     When true, the engine delivers incremental tokens via
 *                      [io.r2h.engine.api.IR2hGenerateCallback.onToken] before the
 *                      terminal [io.r2h.engine.api.IR2hGenerateCallback.onComplete].
 *                      When false, all output is delivered in a single onComplete call.
 * @param sessionConfig Optional tuning parameters. Null uses engine defaults.
 */
@Parcelize
data class GenerateRequest(
    val requestId: String,
    val modelId: String,
    val prompt: String,
    val streaming: Boolean,
    val sessionConfig: SessionConfig?,
) : Parcelable
