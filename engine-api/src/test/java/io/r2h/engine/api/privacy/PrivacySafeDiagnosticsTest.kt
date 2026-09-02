package io.r2h.engine.api.privacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacySafeDiagnosticsTest {
    @Test
    fun `content events retain metadata without prompt response OCR or notification text`() {
        val prompt = "SECRET_PROMPT_CANARY_9f18"
        val response = "SECRET_RESPONSE_CANARY_a701"
        val ocr = "SECRET_OCR_CANARY_b632"
        val notification = "SECRET_NOTIFICATION_CANARY_45d2"

        val rendered = listOf(
            PrivacySafeDiagnostics.contentEvent(
                operation = DiagnosticOperation.GENERATION,
                status = DiagnosticStatus.COMPLETED,
                requestId = "request-123",
                inputContent = prompt,
                outputContent = response,
            ),
            PrivacySafeDiagnostics.contentEvent(
                operation = DiagnosticOperation.OCR,
                status = DiagnosticStatus.COMPLETED,
                outputContent = ocr,
            ),
            PrivacySafeDiagnostics.contentEvent(
                operation = DiagnosticOperation.NOTIFICATION_CAPTURE,
                status = DiagnosticStatus.COMPLETED,
                outputContent = notification,
                recordCount = 3,
            ),
        ).joinToString("\n")

        assertFalse(rendered.contains(prompt))
        assertFalse(rendered.contains(response))
        assertFalse(rendered.contains(ocr))
        assertFalse(rendered.contains(notification))
        assertTrue(rendered.contains("requestId=request-123"))
        assertTrue(rendered.contains("inputChars=${prompt.length}"))
        assertTrue(rendered.contains("outputChars=${response.length}"))
        assertTrue(rendered.contains("recordCount=3"))
    }

    @Test
    fun `failure events retain error classification and safe stack site without throwable message`() {
        val secretMessage = "SECRET_THROWABLE_CANARY_c114"
        val failure = IllegalStateException(secretMessage).apply {
            stackTrace = arrayOf(
                StackTraceElement(
                    "io.r2h.engine.core.LocalRuntime",
                    "execute",
                    "LocalRuntime.kt",
                    77,
                ),
            )
        }

        val rendered = PrivacySafeDiagnostics.failureEvent(
            operation = DiagnosticOperation.MODAL_INFERENCE,
            requestId = "modal-456",
            errorCode = DiagnosticErrorCode.RUNTIME_FAILURE,
            failure = failure,
        )

        assertFalse(rendered.contains(secretMessage))
        assertTrue(rendered.contains("status=FAILED"))
        assertTrue(rendered.contains("errorCode=RUNTIME_FAILURE"))
        assertTrue(rendered.contains("errorType=java.lang.IllegalStateException"))
        assertTrue(rendered.contains("stackSite=io.r2h.engine.core.LocalRuntime.execute:77"))
    }

    @Test
    fun `unsafe request identifiers are omitted rather than logged as content`() {
        val secretIdentifier = "SECRET_PROMPT_CANARY_9f18"

        val rendered = PrivacySafeDiagnostics.contentEvent(
            operation = DiagnosticOperation.GENERATION,
            status = DiagnosticStatus.STARTED,
            requestId = secretIdentifier,
            inputContent = "hello",
        )

        assertFalse(rendered.contains(secretIdentifier))
        assertTrue(rendered.contains("requestId=<redacted>"))
    }
}
