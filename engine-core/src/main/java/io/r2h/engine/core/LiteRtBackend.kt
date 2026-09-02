package io.r2h.engine.core

import android.util.Log
import io.r2h.engine.api.privacy.DiagnosticErrorCode
import io.r2h.engine.api.privacy.DiagnosticOperation
import io.r2h.engine.api.privacy.DiagnosticStatus
import io.r2h.engine.api.privacy.PrivacySafeDiagnostics
import io.r2h.engine.api.model.FinishReason
import io.r2h.engine.api.model.GenerateResponse
import io.r2h.engine.nativebridge.NativeErrorCode
import io.r2h.engine.nativebridge.NativeFinishCode
import io.r2h.engine.nativebridge.NativeInferenceEngine
import io.r2h.engine.nativebridge.TokenCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "LiteRtBackend"
private const val DEFAULT_MAX_CONTEXT = 2048
private const val DEFAULT_THREADS = 4

/**
 * AiModelBackend adapter that delegates to [NativeInferenceEngine] (LiteRT-LM runtime).
 *
 * This adapter encapsulates the opaque context handle, lifecycle phases, and result
 * translation so that [InferenceOrchestrator] depends only on [AiModelBackend], not on
 * the concrete LiteRT types.
 *
 * Creating an additional backend (image models, NNAPI, etc.) only requires a new class
 * implementing [AiModelBackend] — the orchestrator code does not need to change.
 */
internal class LiteRtBackend(
    private val maxContextLength: Int = DEFAULT_MAX_CONTEXT,
    private val threads: Int = DEFAULT_THREADS,
) : AiModelBackend {

    @Volatile private var contextHandle: Long = 0L
    @Volatile private var loadedModelId: String? = null
    @Volatile private var lastError: String? = null

    // ─── AiModelBackend ───────────────────────────────────────────────────────

    override fun getLabel(): String {
        val label = if (contextHandle != 0L) {
            NativeInferenceEngine.getBackendLabel(contextHandle) ?: "LiteRT"
        } else {
            "LiteRT"
        }
        return "LiteRT/$label"
    }

    override fun getModelType(): String = "TEXT_GENERATION"

    override suspend fun load(modelPath: String, phase: (String) -> Unit): Boolean {
        return withContext(Dispatchers.IO) {
            lastError = null

            phase("Validating file")
            val modelFile = java.io.File(modelPath)
            if (!modelFile.exists()) {
                lastError = "Model file not found: $modelPath"
                logModelFailure(lastError!!)
                return@withContext false
            }
            if (!modelFile.extension.equals("litertlm", ignoreCase = true)) {
                lastError = "Unsupported model format: ${modelFile.extension}"
                logModelFailure(lastError!!)
                return@withContext false
            }

            phase("Reading model metadata")
            val modelId = modelFile.nameWithoutExtension

            phase("Initializing LiteRT runtime")
            val handle = NativeInferenceEngine.createContext(
                modelPath = modelFile.absolutePath,
                maxContextLength = maxContextLength,
                threads = threads,
            )

            if (handle == 0L) {
                lastError = "LiteRT createContext returned 0 for ${modelFile.name}"
                logModelFailure(lastError!!)
                return@withContext false
            }

            phase("Allocating inference buffers")
            contextHandle = handle
            loadedModelId = modelId

            phase("Ready")
            Log.i(
                TAG,
                PrivacySafeDiagnostics.contentEvent(
                    operation = DiagnosticOperation.MODEL_RUNTIME,
                    status = DiagnosticStatus.COMPLETED,
                ),
            )
            true
        }
    }

    override suspend fun generate(
        prompt: String,
        maxTokens: Int,
        temperature: Float,
    ): GenerateResponse {
        val handle = contextHandle
        if (handle == 0L) {
            error("No model loaded")
        }

        val output = StringBuilder()
        val nativeResult = NativeInferenceEngine.generate(
            contextHandle = handle,
            prompt = prompt,
            maxTokens = maxTokens,
            temperature = temperature,
            tokenCallback = TokenCallback { token -> output.append(token) },
        )

        return when (nativeResult.errorCode) {
            NativeErrorCode.SUCCESS, NativeErrorCode.CANCELLED -> GenerateResponse(
                requestId = "",
                outputText = output.toString(),
                finishReason = when (nativeResult.finishReasonCode) {
                    NativeFinishCode.MAX_TOKENS -> FinishReason.MAX_TOKENS
                    NativeFinishCode.CANCELLED -> FinishReason.CANCELLED
                    else -> FinishReason.COMPLETE
                },
                promptTokenCount = nativeResult.promptTokenCount,
                generatedTokenCount = nativeResult.generatedTokenCount,
            )
            NativeErrorCode.INVALID_HANDLE -> error("Invalid context handle")
            NativeErrorCode.OOM -> error("Out of memory")
            else -> error("Native error code: ${nativeResult.errorCode}")
        }
    }

    override suspend fun cancel() {
        val handle = contextHandle
        if (handle != 0L) {
            NativeInferenceEngine.cancel(handle)
        }
    }

    override suspend fun unload() {
        val handle = contextHandle
        if (handle != 0L) {
            NativeInferenceEngine.destroyContext(handle)
            contextHandle = 0L
            loadedModelId = null
            Log.i(
                TAG,
                PrivacySafeDiagnostics.contentEvent(
                    operation = DiagnosticOperation.MODEL_RUNTIME,
                    status = DiagnosticStatus.CANCELLED,
                ),
            )
        }
    }

    override fun getIsLoaded(): Boolean = contextHandle != 0L

    override fun getMetadata(): Map<String, String> {
        val handle = contextHandle
        val backendLabel = if (handle != 0L) {
            NativeInferenceEngine.getBackendLabel(handle) ?: "Unknown"
        } else {
            "None"
        }
        return buildMap {
            put("executionTarget", backendLabel)
            put("runtime", "LiteRT-LM")
            put("modelType", getModelType())
            put("maxContextLength", maxContextLength.toString())
            put("threads", threads.toString())
        }
    }

    override fun getLastError(): String? = lastError

    override fun getLoadedModelId(): String? = loadedModelId

    private fun logModelFailure(detail: CharSequence) {
        Log.e(
            TAG,
            PrivacySafeDiagnostics.contentEvent(
                operation = DiagnosticOperation.MODEL_RUNTIME,
                status = DiagnosticStatus.FAILED,
                outputContent = detail,
                errorCode = DiagnosticErrorCode.MODEL_LOAD_FAILED,
            ),
        )
    }
}

