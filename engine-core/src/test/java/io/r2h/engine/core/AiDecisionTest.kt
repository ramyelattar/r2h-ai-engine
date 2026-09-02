package io.r2h.engine.core

import io.r2h.engine.api.model.AiDecisionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AiDecision.toResult].
 *
 * Covers every variant of the sealed hierarchy to verify that the mapping to
 * the IPC-safe [AiDecisionResult] is correct and that [requestId] is echoed.
 */
class AiDecisionTest {

    private val requestId = "req-abc-123"

    // ── ToolCall ───────────────────────────────────────────────────────────────

    @Test
    fun `ToolCall toResult sets TYPE_TOOL_CALL and maps fields`() {
        val decision = AiDecision.ToolCall(
            toolName = "apply_mirror",
            args = mapOf("axis" to "horizontal"),
            reasoning = "user wants mirror effect",
            confidence = 0.9f,
        )
        val result = decision.toResult(requestId)

        assertEquals(requestId, result.requestId)
        assertEquals(AiDecisionResult.TYPE_TOOL_CALL, result.decisionType)
        assertEquals("apply_mirror", result.toolName)
        assertEquals("horizontal", result.toolArgs["axis"])
        assertEquals("user wants mirror effect", result.reasoning)
        assertEquals(0.9f, result.confidence)
    }

    @Test
    fun `ToolCall toResult with empty args produces empty toolArgs map`() {
        val decision = AiDecision.ToolCall(toolName = "open_settings", args = emptyMap())
        val result = decision.toResult(requestId)

        assertEquals(AiDecisionResult.TYPE_TOOL_CALL, result.decisionType)
        assertTrue(result.toolArgs.isEmpty())
    }

    @Test
    fun `ToolCall toResult steps list is empty`() {
        val result = AiDecision.ToolCall(toolName = "t", args = emptyMap()).toResult(requestId)
        assertTrue(result.steps.isEmpty())
    }

    @Test
    fun `ToolCall toResult textResponse is empty`() {
        val result = AiDecision.ToolCall(toolName = "t", args = emptyMap()).toResult(requestId)
        assertEquals("", result.textResponse)
    }

    // ── MultiStep ──────────────────────────────────────────────────────────────

    @Test
    fun `MultiStep toResult sets TYPE_MULTI_STEP and maps steps`() {
        val decision = AiDecision.MultiStep(
            steps = listOf(
                AiDecision.MultiStep.Step(
                    index = 0,
                    toolName = "capture_image",
                    args = emptyMap(),
                    description = "take photo",
                ),
                AiDecision.MultiStep.Step(
                    index = 1,
                    toolName = "apply_filter",
                    args = mapOf("filter" to "cartoon"),
                    description = "apply cartoon filter",
                ),
            ),
            reasoning = "two-step plan",
            confidence = 0.85f,
        )
        val result = decision.toResult(requestId)

        assertEquals(requestId, result.requestId)
        assertEquals(AiDecisionResult.TYPE_MULTI_STEP, result.decisionType)
        assertEquals(2, result.steps.size)
        assertEquals(0, result.steps[0].stepIndex)
        assertEquals("capture_image", result.steps[0].toolName)
        assertEquals(1, result.steps[1].stepIndex)
        assertEquals("apply_filter", result.steps[1].toolName)
        assertEquals("cartoon", result.steps[1].toolArgs["filter"])
        assertEquals("apply cartoon filter", result.steps[1].description)
        assertEquals("two-step plan", result.reasoning)
        assertEquals(0.85f, result.confidence)
    }

    @Test
    fun `MultiStep toResult toolName is empty and toolArgs is empty`() {
        val decision = AiDecision.MultiStep(
            steps = listOf(
                AiDecision.MultiStep.Step(index = 0, toolName = "t", args = emptyMap()),
            ),
        )
        val result = decision.toResult(requestId)

        assertEquals("", result.toolName)
        assertTrue(result.toolArgs.isEmpty())
    }

    @Test
    fun `MultiStep toResult step order is preserved`() {
        val steps = (0..4).map { i ->
            AiDecision.MultiStep.Step(index = i, toolName = "tool_$i", args = emptyMap())
        }
        val result = AiDecision.MultiStep(steps = steps).toResult(requestId)

        result.steps.forEachIndexed { i, step ->
            assertEquals(i, step.stepIndex)
            assertEquals("tool_$i", step.toolName)
        }
    }

    // ── TextResponse ───────────────────────────────────────────────────────────

    @Test
    fun `TextResponse toResult sets TYPE_TEXT_RESPONSE and maps text`() {
        val decision = AiDecision.TextResponse(
            text = "Hello there!",
            reasoning = "direct answer",
        )
        val result = decision.toResult(requestId)

        assertEquals(requestId, result.requestId)
        assertEquals(AiDecisionResult.TYPE_TEXT_RESPONSE, result.decisionType)
        assertEquals("Hello there!", result.textResponse)
        assertEquals("direct answer", result.reasoning)
    }

    @Test
    fun `TextResponse toResult toolName is empty and steps are empty`() {
        val result = AiDecision.TextResponse(text = "hi").toResult(requestId)
        assertEquals("", result.toolName)
        assertTrue(result.steps.isEmpty())
    }

    @Test
    fun `TextResponse toResult with empty text preserves empty string`() {
        val result = AiDecision.TextResponse(text = "").toResult(requestId)
        assertEquals("", result.textResponse)
    }

    // ── Clarification ──────────────────────────────────────────────────────────

    @Test
    fun `Clarification toResult sets TYPE_CLARIFICATION and maps question to textResponse`() {
        val decision = AiDecision.Clarification(question = "Which camera do you want to use?")
        val result = decision.toResult(requestId)

        assertEquals(requestId, result.requestId)
        assertEquals(AiDecisionResult.TYPE_CLARIFICATION, result.decisionType)
        assertEquals("Which camera do you want to use?", result.textResponse)
    }

    @Test
    fun `Clarification toResult reasoning is empty`() {
        val result = AiDecision.Clarification(question = "?").toResult(requestId)
        assertEquals("", result.reasoning)
    }

    @Test
    fun `Clarification toResult toolName is empty and steps are empty`() {
        val result = AiDecision.Clarification(question = "?").toResult(requestId)
        assertEquals("", result.toolName)
        assertTrue(result.steps.isEmpty())
    }

    // ── NoOp ──────────────────────────────────────────────────────────────────

    @Test
    fun `NoOp toResult sets TYPE_NO_OP`() {
        val result = AiDecision.NoOp.toResult(requestId)

        assertEquals(requestId, result.requestId)
        assertEquals(AiDecisionResult.TYPE_NO_OP, result.decisionType)
    }

    @Test
    fun `NoOp toResult has empty toolName, toolArgs, textResponse, and steps`() {
        val result = AiDecision.NoOp.toResult(requestId)
        assertEquals("", result.toolName)
        assertTrue(result.toolArgs.isEmpty())
        assertEquals("", result.textResponse)
        assertTrue(result.steps.isEmpty())
    }

    // ── requestId echo ─────────────────────────────────────────────────────────

    @Test
    fun `all decision types echo requestId correctly`() {
        val id = "unique-req-id"
        val decisions: List<AiDecision> = listOf(
            AiDecision.ToolCall(toolName = "t", args = emptyMap()),
            AiDecision.MultiStep(
                steps = listOf(AiDecision.MultiStep.Step(0, "t", emptyMap()))
            ),
            AiDecision.TextResponse(text = "hi"),
            AiDecision.Clarification(question = "?"),
            AiDecision.NoOp,
        )
        decisions.forEach { decision ->
            assertEquals(
                "requestId mismatch for ${decision::class.simpleName}",
                id,
                decision.toResult(id).requestId,
            )
        }
    }
}
