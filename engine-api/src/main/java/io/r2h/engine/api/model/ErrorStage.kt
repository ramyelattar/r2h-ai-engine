package io.r2h.engine.api.model

enum class ErrorStage {
    SECURITY,
    MODEL_VALIDATION,
    MODEL_LOADING,
    ENGINE,
    INFERENCE,
    BACKEND,
    CLIENT,
}
