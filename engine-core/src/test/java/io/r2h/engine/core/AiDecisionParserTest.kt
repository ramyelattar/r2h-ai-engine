package io.r2h.engine.core

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [AiDecisionParser].
 *
 * Covers:
 * - All five decision types (TOOL_CALL, MULTI_STEP, TEXT_RESPONSE, CLARIFICATION, NO_OP)
 * - Markdown code-fence stripping
 * - First-{...}-block extraction for responses with trailing text
 * - Fallback to TextResponse for completely malformed output
 * - Defensive handling: missing fields, unknown decision type, empty input
 */
class AiDecisionParserTest {

    private val parser = AiDecisionParser()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any<Throwable>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    // ── TOOL_CALL ──────────────────────────────────────────────────────────────

    @Test
    fun `TOOL_CALL with args parses correctly`() {
        val json = """{"decision":"TOOL_CALL","tool_name":"apply_mirror","tool_args":{"axis":"horizontal"},"reasoning":"user wants mirror"}"""
        val result = parser.parse(json)
        assertTrue(result is AiDecision.ToolCall)
        result as AiDecision.ToolCall
        assertEquals("apply_mirror", result.toolName)
        assertEquals("horizontal", result.args["axis"])
        assertEquals("user wants mirror", result.reasoning)
    }

    @Test
    fun `TOOL_CALL with empty tool_args parses correctly`() {
        val json = """{"decision":"TOOL_CALL","tool_name":"open_settings","tool_args":{}}"""
        val result = parser.parse(json)
        assertTrue(result is AiDecision.ToolCall)
        assertEquals("open_settings", (result as AiDecision.ToolCall).toolName)
        assertTrue(result.args.isEmpty())
    }

    @Test
    fun `TOOL_CALL confidence is clamped to 0-1`() {
        val json = """{"decision":"TOOL_CALL","tool_name":"t","tool_args":{},"confidence":2.5}"""
        val result = parser.parse(json) as AiDecision.ToolCall
        assertEquals(1.0f, result.confidence)
    }

    @Test
    fun `TOOL_CALL missing tool_name falls back to TextResponse`() {
        val json = """{"decision":"TOOL_CALL","tool_args":{"key":"val"}}"""
        val result = parser.parse(json)
        // Missing required field — parser falls back to treating raw output as text
        assertTrue("Expected TextResponse for malformed TOOL_CALL, got $result",
            result is AiDecision.TextResponse)
    }

    // ── MULTI_STEP ─────────────────────────────────────────────────────────────

    @Test
    fun `MULTI_STEP parses steps in order`() {
        val json = """
            {
              "decision":"MULTI_STEP",
              "steps":[
                {"step":1,"tool":"capture_image","args":{},"description":"take photo"},
                {"step":2,"tool":"apply_filter","args":{"filter":"cartoon"},"description":"apply filter"}
              ],
              "reasoning":"two-step plan"
            }
        """.trimIndent()
        val result = parser.parse(json)
        assertTrue(result is AiDecision.MultiStep)
        result as AiDecision.MultiStep
        assertEquals(2, result.steps.size)
        assertEquals("capture_image", result.steps[0].toolName)
        assertEquals("apply_filter", result.steps[1].toolName)
        assertEquals("cartoon", result.steps[1].args["filter"])
        assertEquals("two-step plan", result.reasoning)
    }

    @Test
    fun `MULTI_STEP with empty steps falls back to TextResponse`() {
        val json = """{"decision":"MULTI_STEP","steps":[]}"""
        val result = parser.parse(json)
        assertTrue("Expected TextResponse for empty steps, got $result",
            result is AiDecision.TextResponse)
    }

    @Test
    fun `MULTI_STEP confidence clamps to 0-1`() {
        val json = """{"decision":"MULTI_STEP","steps":[{"step":1,"tool":"t","args":{}}],"confidence":-0.5}"""
        val result = parser.parse(json) as AiDecision.MultiStep
        assertEquals(0.0f, result.confidence)
    }

    // ── TEXT_RESPONSE ──────────────────────────────────────────────────────────

    @Test
    fun `TEXT_RESPONSE captures text_response field`() {
        val json = """{"decision":"TEXT_RESPONSE","text_response":"Hello there!","reasoning":"direct answer"}"""
        val result = parser.parse(json)
        assertTrue(result is AiDecision.TextResponse)
        assertEquals("Hello there!", (result as AiDecision.TextResponse).text)
        assertEquals("direct answer", result.reasoning)
    }

    @Test
    fun `TEXT_RESPONSE with empty text_response produces empty TextResponse`() {
        val json = """{"decision":"TEXT_RESPONSE","text_response":""}"""
        val result = parser.parse(json)
        assertTrue(result is AiDecision.TextResponse)
        assertEquals("", (result as AiDecision.TextResponse).text)
    }

    // ── CLARIFICATION ──────────────────────────────────────────────────────────

    @Test
    fun `CLARIFICATION captures question from text_response`() {
        val json = """{"decision":"CLARIFICATION","text_response":"Which camera do you want to use?"}"""
        val result = parser.parse(json)
        assertTrue(result is AiDecision.Clarification)
        assertEquals("Which camera do you want to use?",
            (result as AiDecision.Clarification).question)
    }

    // ── NO_OP ──────────────────────────────────────────────────────────────────

    @Test
    fun `NO_OP returns NoOp singleton`() {
        val json = """{"decision":"NO_OP","reasoning":"nothing to do"}"""
        val result = parser.parse(json)
        assertTrue(result is AiDecision.NoOp)
    }

    // ── Fallback behaviour ─────────────────────────────────────────────────────

    @Test
    fun `unknown decision type falls back to TextResponse`() {
        val json = """{"decision":"DANCE","tool_name":"boogie"}"""
        val result = parser.parse(json)
        assertTrue(result is AiDecision.TextResponse)
    }

    @Test
    fun `completely malformed JSON falls back to TextResponse with raw content`() {
        val raw = "I want to apply the mirror filter to the front camera."
        val result = parser.parse(raw)
        assertTrue(result is AiDecision.TextResponse)
        assertEquals(raw, (result as AiDecision.TextResponse).text)
    }

    @Test
    fun `empty input returns NoOp`() {
        val result = parser.parse("")
        assertTrue(result is AiDecision.NoOp)
    }

    @Test
    fun `blank whitespace input returns NoOp`() {
        val result = parser.parse("   \n  ")
        assertTrue(result is AiDecision.NoOp)
    }

    // ── Markdown code-fence stripping ──────────────────────────────────────────

    @Test
    fun `JSON inside markdown code fence is extracted and parsed`() {
        val raw = """
            ```json
            {"decision":"TOOL_CALL","tool_name":"flip_camera","tool_args":{}}
            ```
        """.trimIndent()
        val result = parser.parse(raw)
        assertTrue("Expected ToolCall, got $result", result is AiDecision.ToolCall)
        assertEquals("flip_camera", (result as AiDecision.ToolCall).toolName)
    }

    @Test
    fun `JSON inside plain code fence without json tag is extracted`() {
        val raw = "```\n{\"decision\":\"NO_OP\"}\n```"
        val result = parser.parse(raw)
        assertTrue("Expected NoOp, got $result", result is AiDecision.NoOp)
    }

    // ── Trailing text extraction ───────────────────────────────────────────────

    @Test
    fun `JSON with preamble text is extracted via first-brace strategy`() {
        val raw = "Sure! Here is my decision: {\"decision\":\"TEXT_RESPONSE\",\"text_response\":\"Done.\"}"
        val result = parser.parse(raw)
        assertTrue("Expected TextResponse, got $result", result is AiDecision.TextResponse)
        assertEquals("Done.", (result as AiDecision.TextResponse).text)
    }

    @Test
    fun `JSON with trailing text is extracted via first-brace strategy`() {
        val raw = "{\"decision\":\"NO_OP\"}\n\nThis was my reasoning."
        val result = parser.parse(raw)
        assertTrue("Expected NoOp, got $result", result is AiDecision.NoOp)
    }

    // ── Args serialisation ─────────────────────────────────────────────────────

    @Test
    fun `nested JSON object in tool_args is serialised to string`() {
        val json = """{"decision":"TOOL_CALL","tool_name":"t","tool_args":{"config":{"mode":"fast"}}}"""
        val result = parser.parse(json) as AiDecision.ToolCall
        assertTrue("config arg should be a JSON string",
            result.args["config"]!!.contains("mode"))
    }

    @Test
    fun `numeric value in tool_args is serialised to string`() {
        val json = """{"decision":"TOOL_CALL","tool_name":"t","tool_args":{"count":5}}"""
        val result = parser.parse(json) as AiDecision.ToolCall
        assertEquals("5", result.args["count"])
    }
}
