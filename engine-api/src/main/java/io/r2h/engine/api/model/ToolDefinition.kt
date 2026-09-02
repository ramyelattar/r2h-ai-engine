package io.r2h.engine.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Describes a callable action that the AI may select in an orchestrated session.
 *
 * [name] is the tool's machine-readable identifier. The engine uses this name
 * verbatim in [AiDecisionResult.toolName] when it selects this tool.
 *
 * [description] is shown to the model as-is. Write it as a clear imperative
 * sentence: what the tool does and when to use it.
 *
 * [parameterSchema] is a JSON Schema string (draft-07) describing the tool's
 * expected arguments. An empty string means the tool takes no parameters.
 *
 * [category] is an optional grouping hint. The engine passes it to the model
 * for context but does not enforce any behaviour based on it.
 */
@Parcelize
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameterSchema: String = "",
    val category: String = "",
) : Parcelable
