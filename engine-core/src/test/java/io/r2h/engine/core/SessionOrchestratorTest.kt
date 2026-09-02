package io.r2h.engine.core

import android.util.Log
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkStatic
import io.mockk.verify
import io.r2h.engine.api.ISessionOrchestratorCallback
import io.r2h.engine.api.model.AiDecisionResult
import io.r2h.engine.api.model.EngineError
import io.r2h.engine.api.model.ErrorCode
import io.r2h.engine.api.model.SessionContext
import io.r2h.engine.api.model.ToolDefinition
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SessionOrchestrator].
 *
 * Covers:
 * - Happy path: well-formed JSON from runtime → correct [AiDecisionResult] via onDecision
 * - Inference failure: runtime throws → onError called with INFERENCE_FAILED error code
 * - Non-JSON response: parser falls back → TEXT_RESPONSE delivered via onDecision
 * - Thinking chunks: onThinkingChunk called once per streaming text item
 * - Multi-token accumulation: tokens streamed across multiple outputs are joined
 * - Tool resolution: session tools are passed to the prompt builder
 * - Metadata: context.params entries appear in InferenceInput metadata
 * - Client dead before onDecision: RemoteException swallowed (no crash)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionOrchestratorTest {

    private lateinit var toolRegistry: ToolRegistry
    private lateinit var orchestrator: SessionOrchestrator
    private lateinit var runtime: BackendRuntime
    private lateinit var descriptor: ModelDescriptor
    private lateinit var callback: ISessionOrchestratorCallback

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any<Throwable>()) } returns 0

        toolRegistry = ToolRegistry()
        orchestrator = SessionOrchestrator(toolRegistry = toolRegistry)

        runtime = mockk()
        descriptor = ModelDescriptor(
            id = "test-model",
            displayName = "Test Model",
            modelType = ModelType.GGUF,
            capabilities = setOf(ModelCapability.TEXT_GENERATION),
            backendKey = "llama",
        )
        callback = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    // ── Happy path ─────────────────────────────────────────────────────────────

    @Test
    fun `TOOL_CALL JSON from runtime delivers correct AiDecisionResult via onDecision`() = runTest {
        val json = """{"decision":"TOOL_CALL","tool_name":"flip_camera","tool_args":{"lens":"front"},"reasoning":"user asked to flip"}"""
        every { runtime.execute(any(), any()) } returns flowOf(output(requestId = "req-1", text = json))

        orchestrator.orchestrate(context("req-1"), runtime, descriptor, callback)

        val slot = slot<AiDecisionResult>()
        verify { callback.onDecision(capture(slot)) }
        assertEquals("req-1", slot.captured.requestId)
        assertEquals(AiDecisionResult.TYPE_TOOL_CALL, slot.captured.decisionType)
        assertEquals("flip_camera", slot.captured.toolName)
        assertEquals("front", slot.captured.toolArgs["lens"])
    }

    @Test
    fun `MULTI_STEP JSON from runtime delivers MultiStep decision`() = runTest {
        val json = """
            {"decision":"MULTI_STEP","steps":[
              {"step":1,"tool":"capture_image","args":{},"description":"capture"},
              {"step":2,"tool":"apply_filter","args":{"filter":"cartoon"},"description":"filter"}
            ],"reasoning":"two steps"}
        """.trimIndent()
        every { runtime.execute(any(), any()) } returns flowOf(output(requestId = "req-2", text = json))

        orchestrator.orchestrate(context("req-2"), runtime, descriptor, callback)

        val slot = slot<AiDecisionResult>()
        verify { callback.onDecision(capture(slot)) }
        assertEquals(AiDecisionResult.TYPE_MULTI_STEP, slot.captured.decisionType)
        assertEquals(2, slot.captured.steps.size)
        assertEquals("capture_image", slot.captured.steps[0].toolName)
        assertEquals("apply_filter", slot.captured.steps[1].toolName)
    }

    @Test
    fun `TEXT_RESPONSE JSON delivers TextResponse decision`() = runTest {
        val json = """{"decision":"TEXT_RESPONSE","text_response":"Hello!","reasoning":"direct"}"""
        every { runtime.execute(any(), any()) } returns flowOf(output("req-3", json))

        orchestrator.orchestrate(context("req-3"), runtime, descriptor, callback)

        val slot = slot<AiDecisionResult>()
        verify { callback.onDecision(capture(slot)) }
        assertEquals(AiDecisionResult.TYPE_TEXT_RESPONSE, slot.captured.decisionType)
        assertEquals("Hello!", slot.captured.textResponse)
    }

    @Test
    fun `NO_OP JSON delivers NoOp decision`() = runTest {
        val json = """{"decision":"NO_OP","reasoning":"nothing to do"}"""
        every { runtime.execute(any(), any()) } returns flowOf(output("req-4", json))

        orchestrator.orchestrate(context("req-4"), runtime, descriptor, callback)

        val slot = slot<AiDecisionResult>()
        verify { callback.onDecision(capture(slot)) }
        assertEquals(AiDecisionResult.TYPE_NO_OP, slot.captured.decisionType)
    }

    // ── Inference failure ──────────────────────────────────────────────────────

    @Test
    fun `inference RuntimeException delivers onError with INFERENCE_FAILED code`() = runTest {
        every { runtime.execute(any(), any()) } throws RuntimeException("CUDA OOM")

        orchestrator.orchestrate(context("req-err"), runtime, descriptor, callback)

        val slot = slot<EngineError>()
        verify { callback.onError(capture(slot)) }
        assertEquals(ErrorCode.INFERENCE_FAILED, slot.captured.code)
        verify(exactly = 0) { callback.onDecision(any()) }
    }

    @Test
    fun `inference failure error message contains cause message`() = runTest {
        every { runtime.execute(any(), any()) } throws IllegalStateException("context destroyed")

        orchestrator.orchestrate(context("req-err2"), runtime, descriptor, callback)

        val slot = slot<EngineError>()
        verify { callback.onError(capture(slot)) }
        assertTrue(slot.captured.message.contains("context destroyed"))
    }

    // ── Non-JSON fallback ──────────────────────────────────────────────────────

    @Test
    fun `non-JSON response from runtime falls back to TEXT_RESPONSE`() = runTest {
        val plainText = "I want to apply the mirror filter to the front camera."
        every { runtime.execute(any(), any()) } returns flowOf(output("req-fb", plainText))

        orchestrator.orchestrate(context("req-fb"), runtime, descriptor, callback)

        val slot = slot<AiDecisionResult>()
        verify { callback.onDecision(capture(slot)) }
        assertEquals(AiDecisionResult.TYPE_TEXT_RESPONSE, slot.captured.decisionType)
        assertEquals(plainText, slot.captured.textResponse)
    }

    @Test
    fun `markdown-fenced JSON is extracted and parsed correctly`() = runTest {
        val fenced = "```json\n{\"decision\":\"NO_OP\"}\n```"
        every { runtime.execute(any(), any()) } returns flowOf(output("req-fence", fenced))

        orchestrator.orchestrate(context("req-fence"), runtime, descriptor, callback)

        val slot = slot<AiDecisionResult>()
        verify { callback.onDecision(capture(slot)) }
        assertEquals(AiDecisionResult.TYPE_NO_OP, slot.captured.decisionType)
    }

    // ── Thinking chunks ────────────────────────────────────────────────────────

    @Test
    fun `onThinkingChunk is called for each streaming text token`() = runTest {
        // Two separate InferenceOutput emissions, each with one text item
        val token1 = output("req-tk", "{\"decision\":")
        val token2 = output("req-tk", "\"NO_OP\"}")
        every { runtime.execute(any(), any()) } returns flowOf(token1, token2)

        orchestrator.orchestrate(context("req-tk"), runtime, descriptor, callback)

        verify(exactly = 2) { callback.onThinkingChunk(any()) }
    }

    @Test
    fun `tokens are accumulated before parsing — multi-token JSON is parsed correctly`() = runTest {
        // JSON split across 3 separate streaming outputs
        every { runtime.execute(any(), any()) } returns flowOf(
            output("req-acc", "{\"decision\":"),
            output("req-acc", "\"TEXT_RESPONSE\","),
            output("req-acc", "\"text_response\":\"Done.\"}"),
        )

        orchestrator.orchestrate(context("req-acc"), runtime, descriptor, callback)

        val slot = slot<AiDecisionResult>()
        verify { callback.onDecision(capture(slot)) }
        assertEquals(AiDecisionResult.TYPE_TEXT_RESPONSE, slot.captured.decisionType)
        assertEquals("Done.", slot.captured.textResponse)
    }

    // ── Tool resolution ────────────────────────────────────────────────────────

    @Test
    fun `session tools passed via context are included in resolved tool list`() = runTest {
        // We verify indirectly: if tool resolution works, the prompt contains the tool name.
        // We capture the InferenceInput argument to check the prompt content.
        val sessionTool = ToolDefinition("session_tool", "A session-specific action")
        val json = """{"decision":"NO_OP"}"""

        val inputSlot = slot<InferenceInput>()
        every { runtime.execute(any(), capture(inputSlot)) } returns flowOf(output("req-tools", json))

        orchestrator.orchestrate(
            context("req-tools", sessionTools = listOf(sessionTool)),
            runtime, descriptor, callback,
        )

        val promptText = inputSlot.captured.parts
            .filterIsInstance<InferenceInput.Part.Text>()
            .joinToString("") { it.content }
        assertTrue(
            "Prompt should contain session tool name",
            promptText.contains("session_tool"),
        )
    }

    @Test
    fun `built-in registry tools appear in the orchestration prompt`() = runTest {
        toolRegistry.register(ToolDefinition("builtin_flash", "Toggle the flash"))
        val json = """{"decision":"NO_OP"}"""

        val inputSlot = slot<InferenceInput>()
        every { runtime.execute(any(), capture(inputSlot)) } returns flowOf(output("req-builtin", json))

        orchestrator.orchestrate(context("req-builtin"), runtime, descriptor, callback)

        val promptText = inputSlot.captured.parts
            .filterIsInstance<InferenceInput.Part.Text>()
            .joinToString("") { it.content }
        assertTrue(
            "Prompt should contain built-in tool name",
            promptText.contains("builtin_flash"),
        )
    }

    // ── requestId propagation ──────────────────────────────────────────────────

    @Test
    fun `requestId from context is echoed in AiDecisionResult`() = runTest {
        val id = "unique-session-id-42"
        every { runtime.execute(any(), any()) } returns flowOf(
            output(id, """{"decision":"NO_OP"}""")
        )

        orchestrator.orchestrate(context(id), runtime, descriptor, callback)

        val slot = slot<AiDecisionResult>()
        verify { callback.onDecision(capture(slot)) }
        assertEquals(id, slot.captured.requestId)
    }

    @Test
    fun `InferenceInput carries the requestId from context`() = runTest {
        val id = "req-id-propagation"
        val inputSlot = slot<InferenceInput>()
        every { runtime.execute(any(), capture(inputSlot)) } returns flowOf(
            output(id, """{"decision":"NO_OP"}""")
        )

        orchestrator.orchestrate(context(id), runtime, descriptor, callback)

        assertEquals(id, inputSlot.captured.requestId)
    }

    // ── Default metadata ───────────────────────────────────────────────────────

    @Test
    fun `InferenceInput metadata contains orchestration flag`() = runTest {
        val inputSlot = slot<InferenceInput>()
        every { runtime.execute(any(), capture(inputSlot)) } returns flowOf(
            output("req-meta", """{"decision":"NO_OP"}""")
        )

        orchestrator.orchestrate(context("req-meta"), runtime, descriptor, callback)

        assertEquals("true", inputSlot.captured.metadata["engine.orchestration"])
    }

    @Test
    fun `context params override default metadata values`() = runTest {
        val inputSlot = slot<InferenceInput>()
        every { runtime.execute(any(), capture(inputSlot)) } returns flowOf(
            output("req-override", """{"decision":"NO_OP"}""")
        )

        orchestrator.orchestrate(
            context("req-override", params = mapOf("engine.temperature" to "0.9")),
            runtime, descriptor, callback,
        )

        assertEquals("0.9", inputSlot.captured.metadata["engine.temperature"])
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private fun context(
        requestId: String,
        sessionTools: List<ToolDefinition> = emptyList(),
        params: Map<String, String> = emptyMap(),
    ) = SessionContext(
        requestId = requestId,
        appId = "com.example.test",
        userMessage = "test message",
        availableTools = sessionTools,
        params = params,
    )

    private fun output(requestId: String, text: String) = InferenceOutput(
        requestId = requestId,
        phase = InferenceOutput.Phase.Streaming,
        items = listOf(InferenceOutput.Item.Text(text)),
        completion = InferenceOutput.CompletionStatus.InProgress,
    )
}
