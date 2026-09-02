package io.r2h.engine.core

import io.mockk.every
import io.mockk.mockk
import io.r2h.engine.nativebridge.RuntimeProbeResult
import io.r2h.engine.nativebridge.RuntimeProbeStatus
import io.r2h.engine.nativebridge.SherpaOnnxTtsRuntimeAdapter
import io.r2h.engine.nativebridge.TtsSynthesisResult
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SherpaOnnxTtsOutputContainmentTest {

    private lateinit var testRoot: File
    private lateinit var voiceRoot: File
    private lateinit var outputRoot: File
    private lateinit var adapter: SherpaOnnxTtsRuntimeAdapter
    private lateinit var runtime: SherpaOnnxTtsBackendRuntime
    private lateinit var descriptor: ModelDescriptor
    private lateinit var capturedOutput: File

    @Before
    fun setUp() = runTest {
        testRoot = Files.createTempDirectory("tts-containment").toFile()
        voiceRoot = File(testRoot, "voices").apply { mkdirs() }
        File(voiceRoot, "vits-piper-en_US-lessac-low-int8").mkdirs()
        File(voiceRoot, "vits-piper-ar_JO-kareem-medium-int8").mkdirs()
        outputRoot = File(testRoot, "private-tts-output")

        adapter = mockk()
        every { adapter.runSmoke(any(), any(), any()) } returns RuntimeProbeResult(
            status = RuntimeProbeStatus.AVAILABLE,
            backendLabel = "Sherpa-ONNX TTS",
            outputPreview = "ok",
            elapsedMs = 1L,
            errorCode = null,
            errorMessage = null,
        )
        every { adapter.synthesize(any(), any(), any(), any()) } answers {
            capturedOutput = thirdArg<File>()
            capturedOutput.writeBytes(byteArrayOf(1, 2, 3))
            TtsSynthesisResult(
                outputWav = capturedOutput,
                sampleRate = 16_000,
                sampleCount = 16_000,
                durationSeconds = 1.0,
                voiceId = arg(3),
            )
        }

        runtime = SherpaOnnxTtsBackendRuntime(
            adapter = adapter,
            outputDirectory = outputRoot,
        )
        descriptor = ModelDescriptor(
            id = "tts-model",
            displayName = "TTS model",
            modelType = ModelType.TEXT_TO_SPEECH,
            capabilities = setOf(
                ModelCapability.Input.Text,
                ModelCapability.Output.Audio,
                ModelCapability.Execution.Local,
            ),
            backendKey = "sherpa-onnx-tts",
            source = ModelDescriptor.Source.Local(voiceRoot.absolutePath, "directory"),
        )
        assertTrue(runtime.loadModel(descriptor) is LoadResult.Success)
    }

    @After
    fun tearDown() {
        testRoot.deleteRecursively()
    }

    @Test
    fun `caller path metadata cannot escape the engine output directory`() = runTest {
        val hostilePaths = listOf(
            "../../escape.wav",
            "..\\..\\escape.wav",
            "C:\\escape.wav",
            "/tmp/escape.wav",
            "nested/valid.wav",
        )

        hostilePaths.forEachIndexed { index, hostilePath ->
            synthesize(requestId = "request-$index", outputPath = hostilePath)
            assertEquals(outputRoot.canonicalFile, capturedOutput.canonicalFile.parentFile)
            assertTrue(capturedOutput.isFile)
        }
    }

    @Test
    fun `caller path metadata cannot overwrite an unrelated internal file`() = runTest {
        val unrelated = File(testRoot, "registry.json").apply { writeText("protected") }

        synthesize(requestId = "overwrite-attempt", outputPath = unrelated.absolutePath)

        assertEquals("protected", unrelated.readText())
        assertEquals(outputRoot.canonicalFile, capturedOutput.canonicalFile.parentFile)
    }

    @Test
    fun `engine chooses a unique output name even for a valid relative hint`() = runTest {
        synthesize(requestId = "valid-relative", outputPath = "nested/result.wav")

        assertTrue(capturedOutput.name.startsWith("tts-"))
        assertTrue(capturedOutput.name.endsWith(".wav"))
        assertEquals(outputRoot.canonicalFile, capturedOutput.canonicalFile.parentFile)
    }

    private suspend fun synthesize(requestId: String, outputPath: String) {
        runtime.execute(
            descriptor,
            InferenceInput(
                requestId = requestId,
                task = InferenceInput.Task.TextToSpeech,
                parts = listOf(InferenceInput.Part.Text("Hello")),
                metadata = mapOf("tts.outputPath" to outputPath),
            ),
        ).single()
    }
}
