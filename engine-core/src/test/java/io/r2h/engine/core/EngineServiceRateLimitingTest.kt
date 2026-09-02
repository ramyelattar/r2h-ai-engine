package io.r2h.engine.core

import io.r2h.engine.api.IR2hGenerateCallback
import io.r2h.engine.api.IModalInferenceCallback
import io.r2h.engine.api.model.EngineError
import io.r2h.engine.api.model.ErrorCode
import io.r2h.engine.api.model.ErrorStage
import io.r2h.engine.api.model.GenerateRequest
import io.r2h.engine.api.model.GenerateResponse
import io.r2h.engine.api.model.ModalInferenceRequest
import io.r2h.engine.api.model.ModalInferenceResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineServiceRateLimitingTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `generate rejects an over-limit caller with a structured error`() {
        val validatedUids = mutableListOf<Int>()
        val service = EngineServiceImpl(
            scope = scope,
            runtimeRegistry = DefaultRuntimeRegistry(),
            taskRouter = DefaultTaskRouter(),
            callerRateLimiter = CallerRateLimiterPort { uid, cost ->
                assertEquals(5151, uid)
                assertEquals(4, cost)
                CallerRateLimitResult.Denied(retryAfterMs = 2_500L)
            },
            callerValidator = CallerValidatorPort { uid ->
                validatedUids += uid
                CallerValidationResult.Allowed("io.r2h.client")
            },
            callingUidProvider = { 5151 },
        )
        val callback = CapturingGenerateCallback()

        service.generate(
            GenerateRequest(
                requestId = "rate-limited-request",
                modelId = "model",
                prompt = "hello",
                streaming = false,
                sessionConfig = null,
            ),
            callback,
        )

        assertEquals(listOf(5151), validatedUids)
        assertEquals(ErrorStage.SECURITY, callback.error?.stage)
        assertEquals(ErrorCode.RATE_LIMITED, callback.error?.code)
        assertTrue(callback.error?.message.orEmpty().contains("2500"))
    }

    @Test
    fun `modal inference rejects an over-limit caller before model resolution`() {
        val service = EngineServiceImpl(
            scope = scope,
            runtimeRegistry = DefaultRuntimeRegistry(),
            taskRouter = DefaultTaskRouter(),
            callerRateLimiter = CallerRateLimiterPort { uid, cost ->
                assertEquals(5252, uid)
                assertEquals(4, cost)
                CallerRateLimitResult.Denied(retryAfterMs = 1_500L)
            },
            callerValidator = CallerValidatorPort {
                CallerValidationResult.Allowed("io.r2h.client")
            },
            callingUidProvider = { 5252 },
        )
        val callback = CapturingModalCallback()

        service.inferModal(
            ModalInferenceRequest(
                requestId = "modal-rate-limited",
                modelId = "missing-model",
                modality = "IMAGE",
                taskType = "IMAGE_UNDERSTANDING",
                inputRefs = listOf("content://example/input"),
            ),
            callback,
        )

        assertEquals(ErrorStage.SECURITY, callback.error?.stage)
        assertEquals(ErrorCode.RATE_LIMITED, callback.error?.code)
        assertTrue(callback.error?.message.orEmpty().contains("1500"))
    }

    private class CapturingGenerateCallback : IR2hGenerateCallback.Stub() {
        var error: EngineError? = null

        override fun onToken(requestId: String, token: String) = Unit

        override fun onComplete(requestId: String, response: GenerateResponse) = Unit

        override fun onError(requestId: String, error: EngineError) {
            this.error = error
        }
    }

    private class CapturingModalCallback : IModalInferenceCallback.Stub() {
        var error: EngineError? = null

        override fun onResult(result: ModalInferenceResult) = Unit

        override fun onError(error: EngineError) {
            this.error = error
        }
    }
}
