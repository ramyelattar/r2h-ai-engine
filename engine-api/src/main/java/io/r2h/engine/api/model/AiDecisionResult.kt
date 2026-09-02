package io.r2h.engine.api.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * The engine's AI-directed decision returned from a session orchestration call.
 *
 * [requestId] echoes the [SessionContext.requestId] so the caller can
 * correlate the response.
 *
 * [decisionType] is one of:
 *   "TOOL_CALL"     — the model wants to invoke a single tool
 *   "MULTI_STEP"    — the model produced a multi-step plan
 *   "TEXT_RESPONSE" — the model answered directly in text (no tool needed)
 *   "CLARIFICATION" — the model needs more information before deciding
 *   "NO_OP"         — the model determined no action is appropriate
 *
 * When [decisionType] is "TOOL_CALL", [toolName] and [toolArgs] are populated.
 * When [decisionType] is "MULTI_STEP", [steps] is populated.
 * When [decisionType] is "TEXT_RESPONSE" or "CLARIFICATION", [textResponse] is populated.
 *
 * [confidence] is a [0, 1] float. 1.0 means the model expressed no uncertainty.
 * Not all models produce a confidence score; in that case the engine sets 1.0.
 *
 * [reasoning] is the model's optional chain-of-thought explanation. May be empty.
 */
@Parcelize
data class AiDecisionResult(
    val requestId: String,
    val decisionType: String,
    val toolName: String = "",
    val toolArgs: Map<String, String> = emptyMap(),
    val textResponse: String = "",
    val steps: List<AiDecisionStep> = emptyList(),
    val confidence: Float = 1.0f,
    val reasoning: String = "",
) : Parcelable {

    companion object {
        const val TYPE_TOOL_CALL = "TOOL_CALL"
        const val TYPE_MULTI_STEP = "MULTI_STEP"
        const val TYPE_TEXT_RESPONSE = "TEXT_RESPONSE"
        const val TYPE_CLARIFICATION = "CLARIFICATION"
        const val TYPE_NO_OP = "NO_OP"
    }
}
