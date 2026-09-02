package io.r2h.engine.model

/**
 * Result of a model file integrity check performed by [ModelValidator].
 */
sealed interface ModelValidationResult {

    /** File exists and its SHA-256 digest matches the registered value. */
    data object Valid : ModelValidationResult

    /** File does not exist at the recorded path. */
    data class FileMissing(val path: String) : ModelValidationResult

    /** File exists but its digest does not match the registered expected value. */
    data class DigestMismatch(
        val expected: String,
        val actual: String,
    ) : ModelValidationResult

    /** An I/O error occurred while reading the file for hashing. */
    data class IoError(val cause: Throwable) : ModelValidationResult
}
