package io.r2h.engine.telemetry

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [EngineLogger].
 *
 * Verifies that events are written to disk, that log rotation occurs when the
 * file exceeds the size limit, and that no exception propagates to the caller.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EngineLoggerTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var logFile: File
    private lateinit var testScope: TestScope
    private lateinit var logger: EngineLogger

    @Before
    fun setUp() {
        val ctx = mockk<Context>()
        every { ctx.filesDir } returns tmpFolder.root

        val dispatcher = StandardTestDispatcher()
        testScope = TestScope(dispatcher)
        logger = EngineLogger(ctx, testScope)
        logFile = File(tmpFolder.root, "engine_telemetry.jsonl")
    }

    @Test
    fun `log writes ModelLoaded event to file`() = runTest {
        logger.log(TelemetryEvent.ModelLoaded("llama3", 1234L))
        advanceUntilIdle()
        assertTrue(logFile.exists())
        val content = logFile.readText()
        assertTrue(content.contains("ModelLoaded"))
        assertTrue(content.contains("llama3"))
        assertTrue(content.contains("1234"))
    }

    @Test
    fun `log writes InferenceCompleted event with all fields`() = runTest {
        logger.log(TelemetryEvent.InferenceCompleted(
            modelId             = "test-model",
            callerUid           = 10042,
            promptTokenCount    = 15,
            generatedTokenCount = 30,
            firstTokenMs        = 200L,
            totalMs             = 1500L,
            finishReason        = "COMPLETE",
        ))
        advanceUntilIdle()
        val content = logFile.readText()
        assertTrue(content.contains("InferenceCompleted"))
        assertTrue(content.contains("test-model"))
        assertTrue(content.contains("10042"))
        assertTrue(content.contains("COMPLETE"))
        // Verify prompt/response content is NOT included (only token counts).
        assertFalse(content.contains("prompt"))
        assertFalse(content.contains("response"))
    }

    @Test
    fun `log writes SecurityRejection event`() = runTest {
        logger.log(TelemetryEvent.SecurityRejection(callerUid = 9999, reason = "UID_NOT_RESOLVABLE"))
        advanceUntilIdle()
        val content = logFile.readText()
        assertTrue(content.contains("SecurityRejection"))
        assertTrue(content.contains("UID_NOT_RESOLVABLE"))
    }

    @Test
    fun `log writes QueueFull event`() = runTest {
        logger.log(TelemetryEvent.QueueFull(callerUid = 1001))
        advanceUntilIdle()
        val content = logFile.readText()
        assertTrue(content.contains("QueueFull"))
        assertTrue(content.contains("1001"))
    }

    @Test
    fun `untrusted telemetry reason and identifier content are not persisted`() = runTest {
        val secretReason = "SECRET_PROMPT_CANARY_9f18"
        val secretModelId = "SECRET_MODEL_CANARY_51c8"

        logger.log(TelemetryEvent.ModelLoadFailed(secretModelId, secretReason))
        logger.log(TelemetryEvent.SecurityRejection(callerUid = 4242, reason = secretReason))
        advanceUntilIdle()

        val content = logFile.readText()
        assertFalse(content.contains(secretReason))
        assertFalse(content.contains(secretModelId))
        assertTrue(content.contains("MODEL_LOAD_FAILED"))
        assertTrue(content.contains("UNCLASSIFIED"))
    }

    @Test
    fun `log appends multiple events as separate lines`() = runTest {
        logger.log(TelemetryEvent.ModelLoaded("model-a", 100L))
        logger.log(TelemetryEvent.QueueFull(callerUid = 2000))
        advanceUntilIdle()
        val lines = logFile.readLines().filter { it.isNotBlank() }
        assertTrue("Expected at least 2 log lines", lines.size >= 2)
    }

    @Test
    fun `log rotates file when it exceeds 5MB`() = runTest {
        // Pre-fill the log file to just over the 5MB limit.
        logFile.writeBytes(ByteArray(5 * 1024 * 1024 + 1) { 'x'.code.toByte() })
        val sizeBefore = logFile.length()

        logger.log(TelemetryEvent.ModelLoaded("trigger-rotation", 0L))
        advanceUntilIdle()

        val sizeAfter = logFile.length()
        assertTrue("File should be smaller after rotation (was $sizeBefore, now $sizeAfter)",
            sizeAfter < sizeBefore)
    }

    @Test
    fun `log does not throw when log directory does not exist`() = runTest {
        // Override logger pointing to a non-existent nested path.
        val ctx = mockk<Context>()
        every { ctx.filesDir } returns File(tmpFolder.root, "nonexistent/nested")
        val safeLogger = EngineLogger(ctx, testScope)

        // Should not throw — telemetry failures are swallowed internally.
        safeLogger.log(TelemetryEvent.QueueFull(callerUid = 0))
        advanceUntilIdle()
    }
}
