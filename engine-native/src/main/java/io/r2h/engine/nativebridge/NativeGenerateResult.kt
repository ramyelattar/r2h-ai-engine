package io.r2h.engine.nativebridge

/**
 * Return value of [NativeInferenceEngine.generate].
 *
 * This class is instantiated by the C++ JNI layer via NewObject — the constructor
 * signature `(IIII)V` must not change without a corresponding C++ update.
 * Field names are intentionally not data-class-generated to avoid accidental
 * structural coupling with the native side.
 *
 * @param errorCode           0 = success. Matches [NativeErrorCode] constants.
 * @param promptTokenCount    Tokens consumed by the prompt. -1 if generation failed
 *                            before tokenisation completed.
 * @param generatedTokenCount Tokens produced. 0 if generation failed before output.
 * @param finishReasonCode    Matches [NativeFinishCode] constants.
 */
class NativeGenerateResult(
    val errorCode: Int,
    val promptTokenCount: Int,
    val generatedTokenCount: Int,
    val finishReasonCode: Int,
)

/** Integer constants for [NativeGenerateResult.errorCode]. */
object NativeErrorCode {
    const val SUCCESS          = 0
    const val INVALID_HANDLE   = 1
    const val INVALID_PARAMS   = 2
    const val INFERENCE_FAILED = 3
    const val CANCELLED        = 4
    const val OOM              = 5
}

/** Integer constants for [NativeGenerateResult.finishReasonCode]. */
object NativeFinishCode {
    const val COMPLETE   = 0
    const val MAX_TOKENS = 1
    const val CANCELLED  = 2
    const val ERROR      = 3
}
