package io.r2h.engine.model

import java.io.File

sealed interface ModelPackVerificationResult {
    val reason: String

    data object Verified : ModelPackVerificationResult {
        override val reason: String = "OK"
    }

    data class FileMissing(val path: String) : ModelPackVerificationResult {
        override val reason: String = "FILE_MISSING: $path"
    }

    data class SizeMismatch(
        val path: String,
        val expectedBytes: Long,
        val actualBytes: Long,
    ) : ModelPackVerificationResult {
        override val reason: String =
            "SIZE_MISMATCH: $path expected=$expectedBytes actual=$actualBytes"
    }

    data class Sha256Mismatch(
        val path: String,
        val expectedSha256: String,
        val actualSha256: String,
    ) : ModelPackVerificationResult {
        override val reason: String =
            "SHA256_MISMATCH: $path expected=$expectedSha256 actual=$actualSha256"
    }

    data class IoError(
        val path: String,
        val message: String,
    ) : ModelPackVerificationResult {
        override val reason: String = "IO_ERROR: $path $message"
    }
}

class ModelPackVerifier(
    private val checksumValidator: ChecksumValidator = ChecksumValidator(),
) {
    fun verify(file: File, artifact: ModelPackArtifact): ModelPackVerificationResult {
        if (!file.isFile) {
            return ModelPackVerificationResult.FileMissing(file.absolutePath)
        }

        val actualSize = file.length()
        if (actualSize != artifact.sizeBytes) {
            return ModelPackVerificationResult.SizeMismatch(
                path = artifact.path,
                expectedBytes = artifact.sizeBytes,
                actualBytes = actualSize,
            )
        }

        return try {
            val actualSha256 = checksumValidator.sha256(file)
            if (actualSha256.equals(artifact.sha256, ignoreCase = true)) {
                ModelPackVerificationResult.Verified
            } else {
                ModelPackVerificationResult.Sha256Mismatch(
                    path = artifact.path,
                    expectedSha256 = artifact.sha256,
                    actualSha256 = actualSha256,
                )
            }
        } catch (t: Throwable) {
            ModelPackVerificationResult.IoError(
                path = artifact.path,
                message = t.message ?: t.javaClass.simpleName,
            )
        }
    }
}
