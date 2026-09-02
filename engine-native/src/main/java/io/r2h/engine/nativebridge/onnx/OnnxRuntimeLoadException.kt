package io.r2h.engine.nativebridge.onnx

/**
 * Thrown when an ONNX model cannot be loaded or its session contract is invalid.
 *
 * [reason] provides a typed failure category that callers can inspect without
 * string-matching on [message]. [code] surfaces [reason.name] for legacy callers
 * that previously used the String-only constructor, and is matched by
 * EngineServiceImpl.parseLoadStatusError() to produce a typed [ErrorCode].
 */
class OnnxRuntimeLoadException(
    val reason: Reason,
    override val message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause) {

    /** Backward-compatible string code derived from the typed [reason]. */
    val code: String get() = reason.name

    enum class Reason {
        /** The model file or ONNX bundle does not match any supported input/output contract. */
        MODEL_CONTRACT_UNSUPPORTED,

        /**
         * The image model has no recognized pixel_values input tensor, or both
         * classification and embedding outputs are absent.
         * Maps to ErrorCode.IMAGE_CONTRACT_UNSUPPORTED.
         */
        IMAGE_CONTRACT_UNSUPPORTED,

        /**
         * The audio model (Whisper encoder or CTC) has no recognized audio input tensor
         * (expected input_features or input_values).
         * Maps to ErrorCode.AUDIO_PROCESSOR_UNSUPPORTED.
         */
        AUDIO_PROCESSOR_UNSUPPORTED,

        /** The ONNX Runtime could not create a session from the provided artifact. */
        SESSION_CREATION_FAILED,
    }
}
