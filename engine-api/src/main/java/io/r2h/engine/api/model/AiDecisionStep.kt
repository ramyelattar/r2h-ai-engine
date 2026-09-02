package io.r2h.engine.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * One step in a multi-step AI decision plan.
 *
 * [stepIndex] is zero-based. Steps are delivered in order and must be executed
 * sequentially unless the client has domain knowledge that allows parallelism.
 *
 * [toolName] matches a [ToolDefinition.name] supplied in the original [SessionContext].
 *
 * [toolArgs] maps parameter names to their string-serialised values. The caller
 * is responsible for deserialising each value to the correct type per the tool's
 * JSON Schema.
 *
 * [description] is the model's natural-language explanation of this step's
 * purpose. Useful for logging and debugging; not required for execution.
 */
@Parcelize
data class AiDecisionStep(
    val stepIndex: Int,
    val toolName: String,
    val toolArgs: Map<String, String> = emptyMap(),
    val description: String = "",
) : Parcelable
