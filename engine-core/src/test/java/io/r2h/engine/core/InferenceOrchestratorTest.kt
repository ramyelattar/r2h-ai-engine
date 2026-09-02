package io.r2h.engine.core

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.r2h.engine.api.IR2hGenerateCallback
import io.r2h.engine.api.model.EngineStateCode
import io.r2h.engine.api.model.ErrorCode
import io.r2h.engine.api.model.GenerateRequest
import io.r2h.engine.nativebridge.NativeErrorCode
import io.r2h.engine.nativebridge.NativeFinishCode
import io.r2h.engine.nativebridge.NativeGenerateResult
import io.r2h.engine.nativebridge.TokenCallback
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InferenceOrchestratorTest {

    private lateinit var queue: RequestQueue
    private lateinit var testScope: TestScope
    private lateinit var orchestrator: InferenceOrchestrator
    private lateinit var fakeNativeRuntime: FakeNativeRuntimePort

    @Before
    fun setUp() {
        fakeNativeRuntime = FakeNativeRuntimePort()
        queue = RequestQueue()
        val testDispatcher = StandardTestDispatcher()
        testScope = TestScope(testDispatcher)
        orchestrator = InferenceOrchestrator(testScope, queue, nativeRuntime = fakeNativeRuntime, dispatcher = testDispatcher)
    }

    @After
    fun tearDown() {
        fakeNativeRuntime = FakeNativeRuntimePort()
    }

    private fun makeRequest(id: String, streaming: Boolean = false): InferenceRequest =
        InferenceRequest(
            request = GenerateRequest(
                requestId = id,
                modelId = "test-model",
                prompt = "Hello",
                streaming = streaming,
                sessionConfig = null,
            ),
            callback = mockk(relaxed = true),
            callerUid = 1000,
        )

    @Test
    fun `initial state is Unavailable`() {
        assertEquals(EngineStateCode.UNAVAILABLE, orchestrator.currentStatus().state)
    }

    @Test
    fun `request with no model loaded delivers MODEL_NOT_LOADED error`() = runTest {
        val ir = makeRequest("r1")
        queue.tryEnqueue(ir)
        testScope.testScheduler.advanceUntilIdle()
        verify { ir.callback.onError("r1", match { it.code == ErrorCode.MODEL_NOT_LOADED }) }
    }

    @Test
    fun `cancelled request before processing delivers onComplete with CANCELLED`() = runTest {
        val ir = makeRequest("r1")
        queue.tryEnqueue(ir)
        queue.cancel("r1")
        testScope.testScheduler.advanceUntilIdle()
        verify { ir.callback.onComplete("r1", match {
            it.finishReason == io.r2h.engine.api.model.FinishReason.CANCELLED
        }) }
    }

    @Test
    fun `successful inference delivers onComplete with correct token counts`() = runTest {
        fakeNativeRuntime.createContextResult = 42L
        fakeNativeRuntime.generateResult = NativeGenerateResult(NativeErrorCode.SUCCESS, 10, 20, NativeFinishCode.COMPLETE)

        orchestrator.loadModel("/path/model.gguf", "test-model", 2048, 4)

        val ir = makeRequest("r1")
        queue.tryEnqueue(ir)
        testScope.testScheduler.advanceUntilIdle()

        verify { ir.callback.onComplete("r1", match {
            it.promptTokenCount == 10 && it.generatedTokenCount == 20
        }) }
    }

    @Test
    fun `native OOM error delivers INTERNAL_ERROR`() = runTest {
        fakeNativeRuntime.createContextResult = 42L
        fakeNativeRuntime.generateResult = NativeGenerateResult(NativeErrorCode.OOM, -1, 0, NativeFinishCode.ERROR)

        orchestrator.loadModel("/path/model.gguf", "test-model", 2048, 4)

        val ir = makeRequest("r1")
        queue.tryEnqueue(ir)
        testScope.testScheduler.advanceUntilIdle()

        verify { ir.callback.onError("r1", match { it.code == ErrorCode.INTERNAL_ERROR }) }
    }

    @Test
    fun `loadModel failure leaves state as Unavailable`() = runTest {
        fakeNativeRuntime.createContextResult = 0L

        orchestrator.loadModel("/bad/path.gguf", "bad-model", 2048, 4)
        testScope.testScheduler.advanceUntilIdle()

        assertEquals(EngineStateCode.UNAVAILABLE, orchestrator.currentStatus().state)
    }
    private class FakeNativeRuntimePort : NativeRuntimePort {
        var createContextResult: Long = 42L
        var createEmbeddingContextResult: Long = 84L
        var embedTextResult: FloatArray? = floatArrayOf(0.25f, 0.75f)
        var generateResult: NativeGenerateResult =
            NativeGenerateResult(NativeErrorCode.SUCCESS, 10, 20, NativeFinishCode.COMPLETE)

        var cancelCalls: Int = 0
        var destroyCalls: Int = 0

        override fun createContext(
            modelPath: String,
            maxContextLength: Int,
            threads: Int
        ): Long {
            return createContextResult
        }

        override fun createEmbeddingContext(
            modelPath: String,
            maxContextLength: Int,
            threads: Int
        ): Long {
            return createEmbeddingContextResult
        }

        override fun generate(
            contextHandle: Long,
            prompt: String,
            maxTokens: Int,
            temperature: Float,
            tokenCallback: TokenCallback
        ): NativeGenerateResult {
            if (generateResult.errorCode == NativeErrorCode.SUCCESS) {
                tokenCallback.onToken("ok")
            }
            return generateResult
        }

        override fun embedText(contextHandle: Long, text: String): FloatArray? {
            return embedTextResult
        }

        override fun cancel(contextHandle: Long) {
            cancelCalls += 1
        }

        override fun destroyContext(contextHandle: Long) {
            destroyCalls += 1
        }
    }
}
