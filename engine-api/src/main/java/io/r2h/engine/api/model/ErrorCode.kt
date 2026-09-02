package io.r2h.engine.api.model

/**
 * Machine-readable failure codes carried inside [EngineError].
 * Callers should switch on this enum to decide recovery strategy rather than
 * parsing the human-readable [EngineError.message] string.
 */
enum class ErrorCode {
    QUEUE_FULL,
    MODEL_NOT_LOADED,
    MODEL_NOT_FOUND,
    MODEL_NOT_LOADABLE,
    INFERENCE_TIMEOUT,
    INFERENCE_FAILED,
    INTERNAL_ERROR,
    PERMISSION_DENIED,
    REQUEST_INVALID,
    NO_ACTIVE_RUNTIME,

    // Model load structured error codes
    IMPORT_INVALID_STRUCTURE,
    IMPORT_MISSING_SIDECARS,
    TOKENIZER_UNSUPPORTED,
    TOKENIZER_INIT_FAILED,
    MODEL_CONTRACT_UNSUPPORTED,
    IMAGE_CONTRACT_UNSUPPORTED,
    AUDIO_PROCESSOR_UNSUPPORTED,
    BACKEND_SELECTION_FAILED,
    BACKEND_INIT_FAILED,
    SESSION_CREATION_FAILED,

    // Binder abuse-control rejection. Added at the end to preserve existing enum ordinals.
    RATE_LIMITED,
}
