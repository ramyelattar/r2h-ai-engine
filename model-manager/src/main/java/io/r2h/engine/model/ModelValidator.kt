package io.r2h.engine.model

import io.r2h.engine.api.privacy.DiagnosticErrorCode
import io.r2h.engine.api.privacy.DiagnosticOperation
import io.r2h.engine.api.privacy.DiagnosticStatus
import io.r2h.engine.api.privacy.PrivacySafeDiagnostics
import java.io.File
import java.io.IOException
import java.security.MessageDigest

private const val TAG       = "ModelValidator"
private const val ALGORITHM = "SHA-256"
private const val BUFFER_KB = 256

/**
 * Validates GGUF model files by comparing their SHA-256 digest against the
 * expected value stored in [LocalModelRecord].
 *
 * All methods are blocking and must be called on [kotlinx.coroutines.Dispatchers.IO].
 * They perform file I/O and should never be called on the main thread.
 */
class ModelValidator {

    /**
     * Validates the file at [record.absolutePath] against [record.sha256].
     *
     * Reads the file in [BUFFER_KB]-kilobyte chunks to avoid loading the entire
     * model (potentially several gigabytes) into memory. Returns immediately if the
     * file is missing or the digest does not match.
     */
    fun validate(record: LocalModelRecord): ModelValidationResult {
        val file = File(record.absolutePath)
        if (!file.exists()) {
            safeLog("Model file missing: ${record.absolutePath}")
            return ModelValidationResult.FileMissing(record.absolutePath)
        }

        val actualDigest = computeDigest(file)
            ?: return ModelValidationResult.IoError(
                IOException("Failed to compute SHA-256 for ${record.absolutePath}")
            )

        return if (actualDigest.equals(record.sha256, ignoreCase = true)) {
            ModelValidationResult.Valid
        } else {
            safeLog("Digest mismatch for ${record.modelId}: expected=${record.sha256} actual=$actualDigest")
            ModelValidationResult.DigestMismatch(
                expected = record.sha256,
                actual = actualDigest,
            )
        }
    }

    private fun computeDigest(file: File): String? {
        return try {
            val digest = MessageDigest.getInstance(ALGORITHM)
            val buffer = ByteArray(BUFFER_KB * 1024)
            file.inputStream().use { stream ->
                var bytesRead: Int
                while (stream.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: IOException) {
            safeLog("I/O error computing digest for ${file.absolutePath}")
            null
        }
    }

    private fun safeLog(message: String) {
        runCatching {
            android.util.Log.e(
                TAG,
                PrivacySafeDiagnostics.contentEvent(
                    operation = DiagnosticOperation.MODEL_VALIDATION,
                    status = DiagnosticStatus.FAILED,
                    outputContent = message,
                    errorCode = DiagnosticErrorCode.MODEL_LOAD_FAILED,
                ),
            )
        }
    }
}
