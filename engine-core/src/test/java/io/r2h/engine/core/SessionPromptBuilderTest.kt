package io.r2h.engine.core

import io.r2h.engine.api.model.ConversationTurn
import io.r2h.engine.api.model.SessionContext
import io.r2h.engine.api.model.ToolDefinition
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [SessionPromptBuilder].
 *
 * Covers:
 * - Default system block includes appId and role description
 * - Available tools are listed with name, description, category, and paramSchema
 * - No-tools path produces "No tools are available" message and TEXT_RESPONSE-only instruction
 * - systemInstruction override replaces the entire default system block
 * - Conversation history appears in "Conversation History" section
 * - History is truncated to maxHistoryTurns (oldest turns dropped)
 * - Role labels are capitalised correctly (user → User, assistant → Assistant, system → System)
 * - Unknown roles are title-cased
 * - appState is included when non-blank and not "{}"
 * - appState is omitted when blank
 * - appState is omitted when it is exactly "{}"
 * - User message is always present
 * - Response instruction includes all 5 JSON format examples when tools are present
 * - Response instruction includes only TEXT_RESPONSE format when no tools
 * - Tool category is shown in brackets when non-blank
 * - Tool parameterSchema is shown when non-blank and not "{}"
 * - Tool parameterSchema is omitted when blank or "{}"
 */
class SessionPromptBuilderTest {

    private val builder = SessionPromptBuilder(maxHistoryTurns = 5)

    // ── System block ───────────────────────────────────────────────────────────

    @Test
    fun `default system block contains appId`() {
        val prompt = builder.build(ctx(appId = "com.example.camera"), emptyList())
        assertTrue(prompt.contains("com.example.camera"))
    }

    @Test
    fun `default system block contains role description`() {
        val prompt = builder.build(ctx(), emptyList())
        assertTrue(prompt.contains("decide what action to take"))
    }

    @Test
    fun `systemInstruction override replaces default system block`() {
        val custom = "You are a custom assistant. Do exactly what the user says."
        val prompt = builder.build(ctx(systemInstruction = custom), emptyList())
        assertTrue("Custom instruction should be present", prompt.contains(custom))
        assertFalse("Default appId sentence should be absent", prompt.contains("AI assistant integrated"))
    }

    @Test
    fun `systemInstruction is trimmed before use`() {
        val custom = "  Custom system block.  "
        val prompt = builder.build(ctx(systemInstruction = custom), emptyList())
        assertTrue(prompt.contains("Custom system block."))
    }

    // ── App state ──────────────────────────────────────────────────────────────

    @Test
    fun `appState is included when non-blank and not empty object`() {
        val state = """{"screen":"camera","mode":"portrait"}"""
        val prompt = builder.build(ctx(appState = state), emptyList())
        assertTrue(prompt.contains(state))
    }

    @Test
    fun `appState is omitted when blank`() {
        val prompt = builder.build(ctx(appState = ""), emptyList())
        assertFalse(prompt.contains("Current Application State"))
    }

    @Test
    fun `appState is omitted when it is exactly empty JSON object`() {
        val prompt = builder.build(ctx(appState = "{}"), emptyList())
        assertFalse(prompt.contains("Current Application State"))
    }

    // ── Tools ──────────────────────────────────────────────────────────────────

    @Test
    fun `tool names appear in prompt`() {
        val tools = listOf(
            ToolDefinition("apply_mirror", "Mirrors the image"),
            ToolDefinition("capture_image", "Takes a photo"),
        )
        val prompt = builder.build(ctx(), tools)
        assertTrue(prompt.contains("apply_mirror"))
        assertTrue(prompt.contains("capture_image"))
    }

    @Test
    fun `tool descriptions appear in prompt`() {
        val tools = listOf(ToolDefinition("zoom", "Zooms the camera view"))
        val prompt = builder.build(ctx(), tools)
        assertTrue(prompt.contains("Zooms the camera view"))
    }

    @Test
    fun `tool category is shown in brackets when non-blank`() {
        val tools = listOf(ToolDefinition("flash", "Toggle flash", category = "camera"))
        val prompt = builder.build(ctx(), tools)
        assertTrue(prompt.contains("[camera]"))
    }

    @Test
    fun `tool category is omitted when blank`() {
        val tools = listOf(ToolDefinition("flash", "Toggle flash", category = ""))
        val prompt = builder.build(ctx(), tools)
        // No bracket pairs should appear for category
        assertFalse(prompt.contains("flash ["))
    }

    @Test
    fun `tool parameterSchema is shown when non-blank and not empty object`() {
        val schema = """{"type":"object","properties":{"axis":{"type":"string"}}}"""
        val tools = listOf(ToolDefinition("mirror", "Mirror", parameterSchema = schema))
        val prompt = builder.build(ctx(), tools)
        assertTrue(prompt.contains("Parameters:"))
        assertTrue(prompt.contains(schema))
    }

    @Test
    fun `tool parameterSchema is omitted when blank`() {
        val tools = listOf(ToolDefinition("mirror", "Mirror", parameterSchema = ""))
        val prompt = builder.build(ctx(), tools)
        assertFalse(prompt.contains("Parameters:"))
    }

    @Test
    fun `tool parameterSchema is omitted when it is exactly empty JSON object`() {
        val tools = listOf(ToolDefinition("mirror", "Mirror", parameterSchema = "{}"))
        val prompt = builder.build(ctx(), tools)
        assertFalse(prompt.contains("Parameters:"))
    }

    @Test
    fun `no tools path produces no-tools message`() {
        val prompt = builder.build(ctx(), emptyList())
        assertTrue(prompt.contains("No tools are available"))
    }

    // ── User message ───────────────────────────────────────────────────────────

    @Test
    fun `user message is always present under User Request section`() {
        val prompt = builder.build(ctx(userMessage = "Apply cartoon filter"), emptyList())
        assertTrue(prompt.contains("User Request"))
        assertTrue(prompt.contains("Apply cartoon filter"))
    }

    // ── Conversation history ───────────────────────────────────────────────────

    @Test
    fun `conversation history section present when turns exist`() {
        val history = listOf(
            ConversationTurn(ConversationTurn.ROLE_USER, "Hello"),
            ConversationTurn(ConversationTurn.ROLE_ASSISTANT, "Hi there"),
        )
        val prompt = builder.build(ctx(history = history), emptyList())
        assertTrue(prompt.contains("Conversation History"))
    }

    @Test
    fun `conversation history absent when no turns`() {
        val prompt = builder.build(ctx(history = emptyList()), emptyList())
        assertFalse(prompt.contains("Conversation History"))
    }

    @Test
    fun `user role is formatted as User`() {
        val history = listOf(ConversationTurn(ConversationTurn.ROLE_USER, "hello"))
        val prompt = builder.build(ctx(history = history), emptyList())
        assertTrue(prompt.contains("User: hello"))
    }

    @Test
    fun `assistant role is formatted as Assistant`() {
        val history = listOf(ConversationTurn(ConversationTurn.ROLE_ASSISTANT, "world"))
        val prompt = builder.build(ctx(history = history), emptyList())
        assertTrue(prompt.contains("Assistant: world"))
    }

    @Test
    fun `system role is formatted as System`() {
        val history = listOf(ConversationTurn(ConversationTurn.ROLE_SYSTEM, "init"))
        val prompt = builder.build(ctx(history = history), emptyList())
        assertTrue(prompt.contains("System: init"))
    }

    @Test
    fun `unknown role is title-cased`() {
        val history = listOf(ConversationTurn("moderator", "note"))
        val prompt = builder.build(ctx(history = history), emptyList())
        assertTrue(prompt.contains("Moderator: note"))
    }

    @Test
    fun `history is truncated to maxHistoryTurns — oldest turns dropped`() {
        // builder has maxHistoryTurns = 5; supply 7 turns
        val history = (1..7).map { i ->
            ConversationTurn(ConversationTurn.ROLE_USER, "message $i")
        }
        val prompt = builder.build(ctx(history = history), emptyList())
        // Turns 1 and 2 should be absent; turns 3–7 should be present
        assertFalse("oldest turn should be truncated", prompt.contains("message 1"))
        assertFalse("second oldest should be truncated", prompt.contains("message 2"))
        assertTrue("most recent turn should be present", prompt.contains("message 7"))
        assertTrue("fifth-from-end should be present", prompt.contains("message 3"))
    }

    @Test
    fun `history within maxHistoryTurns shows all turns`() {
        val history = (1..5).map { i ->
            ConversationTurn(ConversationTurn.ROLE_USER, "msg $i")
        }
        val prompt = builder.build(ctx(history = history), emptyList())
        (1..5).forEach { i ->
            assertTrue("turn $i should be present", prompt.contains("msg $i"))
        }
    }

    // ── Response instruction ───────────────────────────────────────────────────

    @Test
    fun `response instruction includes all five decision formats when tools present`() {
        val tools = listOf(ToolDefinition("t", "desc"))
        val prompt = builder.build(ctx(), tools)
        assertTrue(prompt.contains("TOOL_CALL"))
        assertTrue(prompt.contains("MULTI_STEP"))
        assertTrue(prompt.contains("TEXT_RESPONSE"))
        assertTrue(prompt.contains("CLARIFICATION"))
        assertTrue(prompt.contains("NO_OP"))
    }

    @Test
    fun `response instruction includes only TEXT_RESPONSE format when no tools`() {
        val prompt = builder.build(ctx(), emptyList())
        assertTrue(prompt.contains("TEXT_RESPONSE"))
        assertFalse(prompt.contains("TOOL_CALL"))
        assertFalse(prompt.contains("MULTI_STEP"))
        assertFalse(prompt.contains("CLARIFICATION"))
        assertFalse(prompt.contains("NO_OP"))
    }

    @Test
    fun `prompt instructs model not to use markdown code blocks`() {
        val tools = listOf(ToolDefinition("t", "desc"))
        val prompt = builder.build(ctx(), tools)
        assertTrue(prompt.contains("Do not wrap in markdown code blocks"))
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun ctx(
        appId: String = "com.example.app",
        userMessage: String = "Help me.",
        appState: String = "",
        systemInstruction: String = "",
        history: List<ConversationTurn> = emptyList(),
    ) = SessionContext(
        requestId = "req-test",
        appId = appId,
        userMessage = userMessage,
        appState = appState,
        systemInstruction = systemInstruction,
        conversationHistory = history,
    )
}
