package io.r2h.engine.model

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class LocalModelImportResult(
    val file: File,
    val reusedExisting: Boolean,
    val actualSizeBytes: Long,
    val sha256: String,
    internal val createdNew: Boolean,
)

data class RegisteredLocalModelImport(
    val import: LocalModelImportResult,
    val modelId: String,
)

/** Copies and validates a SAF model without trusting its name or provider size. */
class LocalModelImporter(filesDir: File) {
    private val importRoot = File(filesDir, "models/imported").canonicalFile.also { it.mkdirs() }

    fun import(
        candidate: LocalModelCandidate,
        expectedSha256: String? = null,
        onBytesCopied: (Long) -> Unit = {},
        openSource: () -> InputStream,
    ): LocalModelImportResult {
        if (!candidate.readable) throw ModelImportException(ModelImportError.SOURCE_UNREADABLE)
        val target = resolveTarget(candidate)
        val normalizedExpected = when {
            expectedSha256 == null -> null
            else -> Sha256Contract.normalize(expectedSha256)
                ?: throw ModelImportException(ModelImportError.INVALID_DIGEST)
        }
        val staging = File(
            target.parentFile,
            "${target.nameWithoutExtension}.importing.${UUID.randomUUID()}.${target.extension}",
        )

        try {
            copyAndSync(staging, openSource, onBytesCopied)
            val actualSize = staging.length()
            if (actualSize <= 0L) throw ModelImportException(ModelImportError.EMPTY_FILE)
            if (candidate.sizeBytes > 0L && candidate.sizeBytes != actualSize) {
                throw ModelImportException(ModelImportError.SIZE_MISMATCH)
            }
            ModelFileContentValidator.validate(staging)
            val actualDigest = try {
                Sha256Contract.compute(staging)
            } catch (failure: IOException) {
                throw ModelImportException(ModelImportError.IO_FAILURE, failure)
            }
            if (normalizedExpected != null && normalizedExpected != actualDigest) {
                throw ModelImportException(ModelImportError.DIGEST_MISMATCH)
            }

            if (target.exists()) {
                return reuseOrReject(target, actualDigest)
            }

            try {
                Files.move(staging.toPath(), target.toPath())
            } catch (failure: Exception) {
                if (target.isFile) return reuseOrReject(target, actualDigest)
                throw ModelImportException(ModelImportError.IO_FAILURE, failure)
            }
            if (!target.isFile || target.length() != actualSize) {
                throw ModelImportException(ModelImportError.IO_FAILURE)
            }
            return LocalModelImportResult(
                file = target,
                reusedExisting = false,
                actualSizeBytes = actualSize,
                sha256 = actualDigest,
                createdNew = true,
            )
        } finally {
            if (staging.exists()) staging.delete()
            removeDirectoryIfEmpty(target.parentFile)
        }
    }

    internal fun rollback(result: LocalModelImportResult) {
        if (!result.createdNew || !result.file.isFile) return
        val stillMatches = runCatching {
            result.file.length() == result.actualSizeBytes &&
                Sha256Contract.compute(result.file) == result.sha256
        }.getOrDefault(false)
        if (stillMatches) result.file.delete()
        removeDirectoryIfEmpty(result.file.parentFile)
    }

    internal fun transactionKey(candidate: LocalModelCandidate): String = resolveTarget(candidate).canonicalPath

    private fun resolveTarget(candidate: LocalModelCandidate): File {
        val safeName = candidate.fileName
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._-]"), "_")
            .ifBlank { "model.${candidate.extension}" }
        if (!LocalModelCandidateClassifier.isSupported(safeName)) {
            throw ModelImportException(ModelImportError.UNSUPPORTED_FORMAT)
        }
        val stableId = sha256Text(candidate.uri).take(24)
        val targetDirectory = File(importRoot, stableId).canonicalFile
        if (!targetDirectory.toPath().startsWith(importRoot.toPath())) {
            throw ModelImportException(ModelImportError.IO_FAILURE)
        }
        if (!targetDirectory.exists() && !targetDirectory.mkdirs()) {
            throw ModelImportException(ModelImportError.IO_FAILURE)
        }
        val target = File(targetDirectory, safeName).canonicalFile
        if (!target.toPath().startsWith(importRoot.toPath()) || target == importRoot) {
            throw ModelImportException(ModelImportError.IO_FAILURE)
        }
        return target
    }

    private fun copyAndSync(
        staging: File,
        openSource: () -> InputStream,
        onBytesCopied: (Long) -> Unit,
    ) {
        try {
            openSource().use { input ->
                FileOutputStream(staging).use { rawOutput ->
                    val output = BufferedOutputStream(rawOutput)
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        total += read
                        onBytesCopied(total)
                    }
                    output.flush()
                    rawOutput.fd.sync()
                }
            }
        } catch (failure: ModelImportException) {
            throw failure
        } catch (failure: Exception) {
            throw ModelImportException(ModelImportError.IO_FAILURE, failure)
        }
    }

    private fun reuseOrReject(
        target: File,
        stagedDigest: String,
    ): LocalModelImportResult {
        val targetDigest = try {
            ModelFileContentValidator.validate(target)
            Sha256Contract.compute(target)
        } catch (failure: Exception) {
            throw ModelImportException(ModelImportError.TARGET_CONFLICT, failure)
        }
        if (targetDigest != stagedDigest) {
            throw ModelImportException(ModelImportError.TARGET_CONFLICT)
        }
        return LocalModelImportResult(
            file = target,
            reusedExisting = true,
            actualSizeBytes = target.length(),
            sha256 = targetDigest,
            createdNew = false,
        )
    }

    private fun removeDirectoryIfEmpty(directory: File?) {
        if (directory != null && directory != importRoot && directory.list().orEmpty().isEmpty()) {
            directory.delete()
        }
    }

    private fun sha256Text(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

class LocalModelImportTransaction(
    private val importer: LocalModelImporter,
) {
    suspend fun execute(
        candidate: LocalModelCandidate,
        expectedSha256: String? = null,
        onBytesCopied: (Long) -> Unit = {},
        openSource: () -> InputStream,
        register: suspend (File) -> String,
    ): RegisteredLocalModelImport {
        val lock = LocalImportLocks.forTarget(importer.transactionKey(candidate))
        return lock.withLock {
            val imported = importer.import(candidate, expectedSha256, onBytesCopied, openSource)
            try {
                RegisteredLocalModelImport(imported, register(imported.file))
            } catch (failure: Throwable) {
                importer.rollback(imported)
                throw failure
            }
        }
    }
}

private object LocalImportLocks {
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun forTarget(path: String): Mutex = locks.computeIfAbsent(path) { Mutex() }
}
