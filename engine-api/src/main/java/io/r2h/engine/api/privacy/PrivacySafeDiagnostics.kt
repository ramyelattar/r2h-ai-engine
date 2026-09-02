package io.r2h.engine.api.privacy

/** Operations permitted in first-party production diagnostic messages. */
enum class DiagnosticOperation {
    AGENT,
    AUDIO_TAGGING,
    ENGINE_REFRESH,
    ENGINE_SERVICE,
    GENERATION,
    MODEL_IMPORT,
    MODEL_RUNTIME,
    MODEL_VALIDATION,
    MODAL_INFERENCE,
    NATIVE_RUNTIME,
    NOTIFICATION_CAPTURE,
    OCR,
    SECURITY,
    TELEMETRY,
    TTS,
    UI_DIAGNOSTIC,
    VIDEO_ANALYSIS,
}

enum class DiagnosticStatus {
    STARTED,
    UPDATED,
    COMPLETED,
    CANCELLED,
    REJECTED,
    FAILED,
}

enum class DiagnosticErrorCode {
    INVALID_INPUT,
    MODEL_LOAD_FAILED,
    REMOTE_FAILURE,
    RUNTIME_FAILURE,
    SECURITY_REJECTED,
    TELEMETRY_WRITE_FAILED,
    UNKNOWN,
}

/**
 * Renders content-safe, bounded production diagnostics.
 *
 * Content parameters are deliberately reduced to character counts. Throwable
 * messages/causes are never rendered; only their type and first stack site are
 * retained so logs remain useful without copying provider or user data.
 */
object PrivacySafeDiagnostics {
    fun contentEvent(
        operation: DiagnosticOperation,
        status: DiagnosticStatus,
        requestId: String? = null,
        inputContent: CharSequence? = null,
        outputContent: CharSequence? = null,
        recordCount: Int? = null,
        durationMs: Long? = null,
        errorCode: DiagnosticErrorCode? = null,
    ): String = render(
        operation = operation,
        status = status,
        requestId = requestId,
        inputChars = inputContent?.length,
        outputChars = outputContent?.length,
        recordCount = recordCount,
        durationMs = durationMs,
        errorCode = errorCode,
        errorType = null,
        stackSite = null,
    )

    fun failureEvent(
        operation: DiagnosticOperation,
        requestId: String? = null,
        errorCode: DiagnosticErrorCode,
        failure: Throwable,
        inputContent: CharSequence? = null,
        outputContent: CharSequence? = null,
    ): String = render(
        operation = operation,
        status = DiagnosticStatus.FAILED,
        requestId = requestId,
        inputChars = inputContent?.length,
        outputChars = outputContent?.length,
        recordCount = null,
        durationMs = null,
        errorCode = errorCode,
        errorType = safeToken(failure.javaClass.name, MAX_ERROR_TYPE_CHARS),
        stackSite = failure.stackTrace.firstOrNull()?.let { frame ->
            val owner = safeToken(frame.className, MAX_STACK_COMPONENT_CHARS)
            val method = safeToken(frame.methodName, MAX_STACK_COMPONENT_CHARS)
            "$owner.$method:${frame.lineNumber.coerceAtLeast(0)}"
        },
    )

    private fun render(
        operation: DiagnosticOperation,
        status: DiagnosticStatus,
        requestId: String?,
        inputChars: Int?,
        outputChars: Int?,
        recordCount: Int?,
        durationMs: Long?,
        errorCode: DiagnosticErrorCode?,
        errorType: String?,
        stackSite: String?,
    ): String = buildList {
        add("operation=${operation.name}")
        add("status=${status.name}")
        requestId?.let { add("requestId=${safeRequestId(it)}") }
        inputChars?.let { add("inputChars=${it.coerceAtLeast(0)}") }
        outputChars?.let { add("outputChars=${it.coerceAtLeast(0)}") }
        recordCount?.let { add("recordCount=${it.coerceAtLeast(0)}") }
        durationMs?.let { add("durationMs=${it.coerceAtLeast(0L)}") }
        errorCode?.let { add("errorCode=${it.name}") }
        errorType?.let { add("errorType=$it") }
        stackSite?.let { add("stackSite=$it") }
    }.joinToString(separator = " ")

    private fun safeRequestId(value: String): String =
        value.takeIf { SAFE_REQUEST_ID.matches(it) } ?: REDACTED

    private fun safeToken(value: String, maxChars: Int): String = buildString {
        value.take(maxChars).forEach { character ->
            append(if (character.isLetterOrDigit() || character in SAFE_TOKEN_PUNCTUATION) character else '_')
        }
    }

    private const val REDACTED = "<redacted>"
    private const val MAX_ERROR_TYPE_CHARS = 120
    private const val MAX_STACK_COMPONENT_CHARS = 120
    private val SAFE_REQUEST_ID = Regex("^[a-z][a-z0-9._:-]{0,79}$")
    private val SAFE_TOKEN_PUNCTUATION = setOf('.', '$', '_', '-')
}
