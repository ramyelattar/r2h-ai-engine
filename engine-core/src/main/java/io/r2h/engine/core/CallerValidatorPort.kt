package io.r2h.engine.core

fun interface CallerValidatorPort {
    fun validate(callerUid: Int): CallerValidationResult
}
