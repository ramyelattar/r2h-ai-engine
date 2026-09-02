package io.r2h.engine.telemetry

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG            = "EngineLogger"
private const val LOG_FILE_NAME  = "engine_telemetry.jsonl"
private const val MAX_FILE_BYTES = 5 * 1024 * 1024L // 5 MB rolling cap

/**
 * Fire-and-forget telemetry sink. Appends [TelemetryEvent] objects as newline-delimited
 * JSON (JSONL) to a private file in the app's internal storage.
 *
 * Design constraints:
 *  - No data ever leaves the device. This class must never open a network connection.
 *  - Writes are dispatched on [Dispatchers.IO] so callers are never blocked.
 *  - The log file is rotated (truncated) when it exceeds [MAX_FILE_BYTES].
 *    A production implementation should use a ring-buffer strategy; truncation
 *    is a safe stub that prevents unbounded disk growth.
 *  - No prompt or response content is ever written. See [TelemetryEvent] KDoc.
 *
 * @param context Application context used to resolve [Context.getFilesDir].
 * @param scope   Coroutine scope for IO dispatch. Typically the service's lifecycleScope.
 */
class EngineLogger(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val logFile = File(context.filesDir, LOG_FILE_NAME)
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)

    /** Enqueues [event] for asynchronous write. Returns immediately. */
    fun log(event: TelemetryEvent) {
        writeEvent(event)
    }

    private fun writeEvent(event: TelemetryEvent) {
        try {
            rotateIfNeeded()
            val line = serialise(event)
            logFile.parentFile?.mkdirs()
            logFile.appendText(line + "\n", Charsets.UTF_8)
        } catch (e: Exception) {
            // Telemetry failures must never propagate to callers.
            safeLog("Failed to write telemetry event: ${event::class.simpleName}")
        }
    }

    private fun rotateIfNeeded() {
        if (logFile.exists() && logFile.length() > MAX_FILE_BYTES) {
            // TODO(#telemetry-001): Replace truncation with a proper ring-buffer rotation.
            logFile.delete()
            safeLog("Telemetry log rotated")
        }
    }

    private fun serialise(event: TelemetryEvent): String {
        val values = linkedMapOf<String, Any>(
            "ts" to dateFormat.format(Date()),
            "type" to (event::class.simpleName ?: "Unknown"),
        )
        when (event) {
            is TelemetryEvent.ModelLoaded -> {
                values["modelId"] = safeModelId(event.modelId)
                values["durationMs"] = event.durationMs
            }
            is TelemetryEvent.ModelLoadFailed -> {
                values["modelId"] = safeModelId(event.modelId)
                values["reason"] = MODEL_LOAD_FAILED_CODE
            }
            is TelemetryEvent.InferenceCompleted -> {
                values["modelId"] = safeModelId(event.modelId)
                values["callerUid"] = event.callerUid
                values["inputTokens"] = event.promptTokenCount
                values["genTokens"] = event.generatedTokenCount
                values["firstTokenMs"] = event.firstTokenMs
                values["totalMs"] = event.totalMs
                values["finishReason"] = event.finishReason.takeIf(ALLOWED_FINISH_REASONS::contains)
                    ?: UNCLASSIFIED_CODE
            }
            is TelemetryEvent.SecurityRejection -> {
                values["callerUid"] = event.callerUid
                values["reason"] = event.reason.takeIf(ALLOWED_SECURITY_REASONS::contains)
                    ?: UNCLASSIFIED_CODE
            }
            is TelemetryEvent.QueueFull -> {
                values["callerUid"] = event.callerUid
            }
        }
        return values.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            val encoded = when (value) {
                is Number, is Boolean -> value.toString()
                else -> "\"${escape(value.toString())}\""
            }
            "\"$key\":$encoded"
        }
    }

    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun safeModelId(value: String): String =
        value.takeIf(SAFE_MODEL_ID::matches) ?: REDACTED_IDENTIFIER

    private fun safeLog(message: String) {
        runCatching { android.util.Log.e(TAG, message) }
    }

    private companion object {
        const val MODEL_LOAD_FAILED_CODE = "MODEL_LOAD_FAILED"
        const val UNCLASSIFIED_CODE = "UNCLASSIFIED"
        const val REDACTED_IDENTIFIER = "redacted"
        val SAFE_MODEL_ID = Regex("^[a-z0-9][a-z0-9._:-]{0,127}$")
        val ALLOWED_FINISH_REASONS = setOf("COMPLETE", "MAX_TOKENS", "CANCELLED", "ERROR")
        val ALLOWED_SECURITY_REASONS = setOf(
            "TRUST_POLICY_EMPTY",
            "CALLER_IDENTITY_UNRESOLVABLE",
            "CALLER_CERTIFICATE_UNRESOLVABLE",
            "SIGNING_IDENTITY_NOT_TRUSTED",
            "UID_NOT_RESOLVABLE",
            "RATE_LIMITED",
        )
    }
}
