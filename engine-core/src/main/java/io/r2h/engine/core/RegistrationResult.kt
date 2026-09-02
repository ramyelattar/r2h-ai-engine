package io.r2h.engine.core

sealed interface RegistrationResult {
    data class Success(val runtime: BackendRuntime, val action: Action) : RegistrationResult
    data class Rejected(val reason: String) : RegistrationResult

    enum class Action { ADDED, REPLACED }
}
