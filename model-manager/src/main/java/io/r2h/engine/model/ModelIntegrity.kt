package io.r2h.engine.model

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

internal object Sha256Contract {
    private val canonicalPattern = Regex("^[0-9a-fA-F]{64}$")

    fun normalize(value: String): String? =
        value.takeIf(canonicalPattern::matches)?.lowercase()

    @Throws(IOException::class)
    fun compute(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(256 * 1024)
        file.inputStream().use { stream ->
            var bytesRead: Int
            while (stream.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

enum class ModelImportError {
    SOURCE_UNREADABLE,
    UNSUPPORTED_FORMAT,
    EMPTY_FILE,
    SIZE_MISMATCH,
    INVALID_MODEL_FORMAT,
    INVALID_DIGEST,
    DIGEST_MISMATCH,
    TARGET_CONFLICT,
    IO_FAILURE,
}

class ModelImportException(
    val code: ModelImportError,
    cause: Throwable? = null,
) : IOException(code.name, cause)

internal object ModelFileContentValidator {
    private const val GGUF_HEADER_BYTES = 24
    private val ggufMagic = byteArrayOf(
        'G'.code.toByte(),
        'G'.code.toByte(),
        'U'.code.toByte(),
        'F'.code.toByte(),
    )

    @Throws(ModelImportException::class)
    fun validate(file: File) {
        if (!file.isFile || file.length() <= 0L) {
            throw ModelImportException(ModelImportError.EMPTY_FILE)
        }

        when (file.extension.lowercase()) {
            "gguf" -> validateGguf(file)
            "onnx" -> Unit
            else -> throw ModelImportException(ModelImportError.UNSUPPORTED_FORMAT)
        }
    }

    private fun validateGguf(file: File) {
        if (file.length() < GGUF_HEADER_BYTES) {
            throw ModelImportException(ModelImportError.INVALID_MODEL_FORMAT)
        }

        val header = ByteArray(GGUF_HEADER_BYTES)
        try {
            file.inputStream().use { input ->
                var offset = 0
                while (offset < header.size) {
                    val read = input.read(header, offset, header.size - offset)
                    if (read < 0) throw ModelImportException(ModelImportError.INVALID_MODEL_FORMAT)
                    offset += read
                }
            }
        } catch (failure: ModelImportException) {
            throw failure
        } catch (failure: IOException) {
            throw ModelImportException(ModelImportError.IO_FAILURE, failure)
        }

        if (!header.copyOfRange(0, ggufMagic.size).contentEquals(ggufMagic)) {
            throw ModelImportException(ModelImportError.INVALID_MODEL_FORMAT)
        }

        val values = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        values.position(4)
        val version = values.int
        val tensorCount = values.long
        val metadataCount = values.long
        if (version !in 2..3 || tensorCount < 0L || metadataCount < 0L) {
            throw ModelImportException(ModelImportError.INVALID_MODEL_FORMAT)
        }
    }
}
