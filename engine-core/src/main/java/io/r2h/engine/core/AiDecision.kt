package io.r2h.engine.core

import io.r2h.engine.api.model.AiDecisionResult
import io.r2h.engine.api.model.AiDecisionStep

/**
 * Engine-internal representation of the AI's decision in a session orchestration call.
 *
 * This sealed hierarchy is richer than [AiDecisionResult] (which is the IPC-safe flat
 * form) and is used only within engine-core. Use [toResult] to convert to the IPC form.
 */
sealed interface AiDecision {

    /** The model chose to invoke a single registered tool. */
    data class ToolCall(
        val toolName: String,
        val args: Map<String, String>,
        val reasoning: String = "",
        val confidence: Float = 1.0f,
    ) : AiDecision

    /** The model produced a multi-step plan involving multiple tool invocations. */
    data class MultiStep(
        val steps: List<Step>,
        val reasoning: String = "",
        val confidence: Float = 1.0f,
    ) : AiDecision {

        data class Step(
            val index: Int,
            val toolName: String,
            val args: Map<String, String>,
            val description: String = "",
        )
    }

    /** The model answered the user directly without invoking any tool. */
    data class TextResponse(
        val text: String,
        val reasoning: String = "",
    ) : AiDecision

    /** The model needs more information before it can decide. */
    data class Clarification(
        val question: String,
    ) : AiDecision

    /** The model determined that no action is appropriate. */
    data object NoOp : AiDecision

    /** Converts to the IPC-safe flat representation. */
    fun toResult(requestId: String): AiDecisionResult = when (this) {
        is ToolCall -> AiDecisionResult(
            requestId = requestId,
            decisionType = AiDecisionResult.TYPE_TOOL_CALL,
            toolName = toolName,
            toolArgs = args,
            reasoning = reasoning,
            confidence = confidence,
        )
        is MultiStep -> AiDecisionResult(
            requestId = requestId,
            decisionType = AiDecisionResult.TYPE_MULTI_STEP,
            steps = steps.map { s ->
                AiDecisionStep(
                    stepIndex = s.index,
                    toolName = s.toolName,
                    toolArgs = s.args,
                    description = s.description,
                )
            },
            reasoning = reasoning,
            confidence = confidence,
        )
        is TextResponse -> AiDecisionResult(
            requestId = requestId,
            decisionType = AiDecisionResult.TYPE_TEXT_RESPONSE,
            textResponse = text,
            reasoning = reasoning,
        )
        is Clarification -> AiDecisionResult(
            requestId = requestId,
            decisionType = AiDecisionResult.TYPE_CLARIFICATION,
            textResponse = question,
        )
        NoOp -> AiDecisionResult(
            requestId = requestId,
            decisionType = AiDecisionResult.TYPE_NO_OP,
        )
    }
}
