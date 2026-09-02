package io.r2h.engine.nativebridge

class QwenTextRuntimeAdapter(
    private val maxContextLength: Int = 512,
    private val threads: Int = defaultThreadCount(),
    private val maxTokens: Int = 8,
    private val temperature: Float = 0.1f,
) {
    fun runSmoke(absoluteModelPath: String): RuntimeProbeResult {
        val started = System.currentTimeMillis()
        var contextHandle = 0L
        var backendLabel = "llama.cpp JNI"
        val streamed = StringBuilder()

        return try {
            contextHandle = NativeInferenceEngine.createContext(
                modelPath = absoluteModelPath,
                maxContextLength = maxContextLength,
                threads = threads,
            )

            if (contextHandle == 0L) {
                return failed(
                    started = started,
                    backendLabel = backendLabel,
                    errorCode = "CREATE_CONTEXT_FAILED",
                    errorMessage = "NativeInferenceEngine.createContext returned 0.",
                )
            }

            backendLabel = NativeInferenceEngine.getBackendLabel(contextHandle) ?: backendLabel

            val nativeResult = NativeInferenceEngine.generate(
                contextHandle = contextHandle,
                prompt = "Answer with exactly one word: ok\n",
                maxTokens = maxTokens.coerceAtMost(16),
                temperature = temperature,
                tokenCallback = TokenCallback { token -> streamed.append(token) },
            )

            val output = streamed.toString().trim()
            if (nativeResult.errorCode == NativeErrorCode.SUCCESS && output.isNotBlank()) {
                RuntimeProbeResult(
                    status = RuntimeProbeStatus.AVAILABLE,
                    backendLabel = backendLabel,
                    outputPreview = output.take(256),
                    elapsedMs = System.currentTimeMillis() - started,
                    errorCode = null,
                    errorMessage = null,
                )
            } else {
                failed(
                    started = started,
                    backendLabel = backendLabel,
                    outputPreview = output.take(256),
                    errorCode = if (nativeResult.errorCode == NativeErrorCode.SUCCESS) {
                        "EMPTY_OUTPUT"
                    } else {
                        "NATIVE_ERROR_${nativeResult.errorCode}"
                    },
                    errorMessage = "generate returned errorCode=${nativeResult.errorCode}, " +
                        "promptTokens=${nativeResult.promptTokenCount}, " +
                        "generatedTokens=${nativeResult.generatedTokenCount}, " +
                        "finishReason=${nativeResult.finishReasonCode}.",
                )
            }
        } catch (missing: UnsatisfiedLinkError) {
            RuntimeProbeResult(
                status = RuntimeProbeStatus.RUNTIME_MISSING,
                backendLabel = backendLabel,
                outputPreview = streamed.toString().trim().take(256),
                elapsedMs = System.currentTimeMillis() - started,
                errorCode = "NATIVE_LIBRARY_MISSING",
                errorMessage = missing.message ?: missing.javaClass.simpleName,
            )
        } catch (missing: NoClassDefFoundError) {
            RuntimeProbeResult(
                status = RuntimeProbeStatus.RUNTIME_MISSING,
                backendLabel = backendLabel,
                outputPreview = streamed.toString().trim().take(256),
                elapsedMs = System.currentTimeMillis() - started,
                errorCode = "NATIVE_BRIDGE_MISSING",
                errorMessage = missing.message ?: missing.javaClass.simpleName,
            )
        } catch (t: Throwable) {
            failed(
                started = started,
                backendLabel = backendLabel,
                outputPreview = streamed.toString().trim().take(256),
                errorCode = t.javaClass.simpleName,
                errorMessage = t.message ?: t.javaClass.name,
            )
        } finally {
            if (contextHandle != 0L) {
                runCatching { NativeInferenceEngine.destroyContext(contextHandle) }
            }
        }
    }

    private fun failed(
        started: Long,
        backendLabel: String,
        outputPreview: String = "",
        errorCode: String,
        errorMessage: String,
    ): RuntimeProbeResult =
        RuntimeProbeResult(
            status = RuntimeProbeStatus.SMOKE_FAILED,
            backendLabel = backendLabel,
            outputPreview = outputPreview,
            elapsedMs = System.currentTimeMillis() - started,
            errorCode = errorCode,
            errorMessage = errorMessage,
        )

    private companion object {
        fun defaultThreadCount(): Int =
            (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 2)
    }
}
