package io.r2h.engine.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class ModelValidatorTest {

    @get:Rule
    val tmpDir = TemporaryFolder()

    private val validator = ModelValidator()

    private fun sha256Of(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(bytes).joinToString("") { "%02x".format(it) }
    }

    private fun makeRecord(file: java.io.File, sha256: String) = LocalModelRecord(
        modelId = "test-model",
        displayName = "Test Model",
        absolutePath = file.absolutePath,
        sizeBytes = file.length(),
        sha256 = sha256,
        quantization = "Q4_K_M",
    )

    @Test
    fun `Valid returns when file exists and digest matches`() {
        val content = "fake gguf content".toByteArray()
        val file = tmpDir.newFile("model.gguf").also { it.writeBytes(content) }
        val record = makeRecord(file, sha256Of(content))

        val result = validator.validate(record)

        assertEquals(ModelValidationResult.Valid, result)
    }

    @Test
    fun `FileMissing returns when file does not exist`() {
        val record = makeRecord(java.io.File(tmpDir.root, "missing.gguf"), "aabbcc")

        val result = validator.validate(record)

        assertTrue(result is ModelValidationResult.FileMissing)
    }

    @Test
    fun `DigestMismatch returns when file content differs from expected hash`() {
        val file = tmpDir.newFile("model.gguf").also { it.writeText("real content") }
        val record = makeRecord(file, "000000deadbeef")

        val result = validator.validate(record)

        assertTrue(result is ModelValidationResult.DigestMismatch)
        val mismatch = result as ModelValidationResult.DigestMismatch
        assertEquals("000000deadbeef", mismatch.expected)
    }

    @Test
    fun `digest is case-insensitive match`() {
        val content = "data".toByteArray()
        val file = tmpDir.newFile("model.gguf").also { it.writeBytes(content) }
        val upperHash = sha256Of(content).uppercase()
        val record = makeRecord(file, upperHash)

        assertEquals(ModelValidationResult.Valid, validator.validate(record))
    }
}
