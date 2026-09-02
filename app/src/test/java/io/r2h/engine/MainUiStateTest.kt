package io.r2h.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MainUiStateTest {
    private val codec = MainSavedStateCodec()

    @Test
    fun `selected section and bounded transcript restore across process-style recreation`() {
        val transcript = "x".repeat(MainStateLimits.TRANSCRIPT_CHARS + 500)
        val original = MainUiState(
            currentSection = ProductSection.AGENT,
            selectedModelId = "local-model",
            lastPromptText = "Explain this locally",
            lastGenerationText = transcript,
            isGenerating = true,
        )

        val encoded = codec.encode(original)
        val restored = codec.decode(encoded.values)

        assertEquals(ProductSection.AGENT, restored.currentSection)
        assertEquals("local-model", restored.selectedModelId)
        assertEquals(MainStateLimits.TRANSCRIPT_CHARS, restored.lastGenerationText.length)
        assertEquals(transcript.takeLast(MainStateLimits.TRANSCRIPT_CHARS), restored.lastGenerationText)
        assertFalse("A saved transcript must not fabricate live engine execution", restored.isGenerating)
    }

    @Test
    fun `aggregate saved state stays under target with multibyte and escaped content`() {
        val hostileLargeText = "😀\\\"\n".repeat(40_000)
        val state = MainUiState(
            currentSection = ProductSection.DIAGNOSTICS,
            selectedModelId = hostileLargeText,
            lastPromptText = hostileLargeText,
            lastGenerationText = hostileLargeText,
            lastGenerationMeta = hostileLargeText,
            lastGenerationError = hostileLargeText,
            lastStudioWorkflowTitle = hostileLargeText,
            lastStudioResultPreview = hostileLargeText,
            lastAgentTask = hostileLargeText,
            lastAgentTimeline = listOf(hostileLargeText, hostileLargeText),
            lastAgentToolSummary = hostileLargeText,
            lastAgentResult = hostileLargeText,
            lastAgentError = hostileLargeText,
            diagnosticsText = hostileLargeText,
        )

        val encoded = codec.encode(state)

        assertTrue(
            "Persisted state must meet the 128 KiB target, was ${encoded.serializedSizeBytes}",
            encoded.serializedSizeBytes <= MainStateLimits.SAVED_STATE_TARGET_BYTES,
        )
        assertTrue(encoded.serializedSizeBytes <= MainStateLimits.SAVED_STATE_HARD_BYTES)
    }

    @Test
    fun `saved state contains only explicit small logical fields`() {
        val encoded = codec.encode(
            MainUiState(
                currentSection = ProductSection.STATUS,
                isGenerating = true,
                isAgentRunning = true,
            ),
        )

        assertEquals(MainSavedStateCodec.PERSISTED_KEYS, encoded.values.keys)
        assertFalse(encoded.values.containsKey("runtime"))
        assertFalse(encoded.values.containsKey("engineService"))
        assertFalse(encoded.values.containsKey("callbacks"))
        assertFalse(encoded.values.containsKey("views"))

        val restored = codec.decode(encoded.values)
        assertFalse(restored.isGenerating)
        assertFalse(restored.isAgentRunning)
        assertNull(restored.runtime.truth)
    }

    @Test
    fun `unknown or malformed saved values fail safely to defaults`() {
        val restored = codec.decode(
            mapOf(
                "section" to "NOT_A_SECTION",
                "showTechnicalDetails" to "not-a-boolean",
                "pendingApprovalStatus" to "NOT_A_STATUS",
            ),
        )

        assertEquals(ProductSection.CHAT, restored.currentSection)
        assertFalse(restored.showTechnicalDetails)
        assertNull(restored.pendingApproval)
    }
}
