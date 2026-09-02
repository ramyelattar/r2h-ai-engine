package io.r2h.engine.model

import java.io.File
import java.security.MessageDigest

class ChecksumValidator {
    fun sha256(file: File): String {
        require(file.isFile) { "Checksum target is not a regular file." }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        file.inputStream().use { input ->
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun verify(file: File, expectedSha256: String): Boolean =
        expectedSha256.isNotBlank() && sha256(file).equals(expectedSha256, ignoreCase = true)
}
