package io.r2h.engine.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Describes a failure at a specific [stage] of the engine pipeline.
 * Delivered via callbacks for both generate() and inferModal() flows.
 *
 * Callers must switch on [code] for recovery; [message] is for diagnostics only.
 * The engine guarantees [message] never contains prompt text or user content.
 */
@Parcelize
data class EngineError(
    val stage: ErrorStage,
    val code: ErrorCode,
    val message: String,
) : Parcelable
