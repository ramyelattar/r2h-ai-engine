package io.r2h.engine.model

import java.io.ByteArrayInputStream
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalModelImporterTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `imports validated GGUF into stable app-private directory`() {
        val bytes = minimalGgufBytes() + "model payload".toByteArray()
        val candidate = candidate(sizeBytes = bytes.size.toLong())
        val importer = LocalModelImporter(temporaryFolder.root)

        val imported = importer.import(candidate) { ByteArrayInputStream(bytes) }

        assertTrue(imported.file.exists())
        assertArrayEquals(bytes, imported.file.readBytes())
        assertEquals(sha256Of(bytes), imported.sha256)
        assertTrue(imported.file.canonicalPath.startsWith(temporaryFolder.root.canonicalPath))
        assertTrue(imported.file.invariantSeparatorsPath.contains("models/imported/"))
        assertFalse(imported.file.invariantSeparatorsPath.contains("assets/model-pack"))
    }

    @Test
    fun `same valid content is reused only after content validation`() {
        val bytes = minimalGgufBytes() + "same model bytes".toByteArray()
        val candidate = candidate(sizeBytes = bytes.size.toLong())
        val importer = LocalModelImporter(temporaryFolder.root)
        val first = importer.import(candidate) { ByteArrayInputStream(bytes) }

        val second = importer.import(candidate) { ByteArrayInputStream(bytes) }

        assertTrue(second.reusedExisting)
        assertEquals(first.file.canonicalFile, second.file.canonicalFile)
        assertArrayEquals(bytes, second.file.readBytes())
    }

    @Test
    fun `existing different content is not overwritten`() {
        val original = minimalGgufBytes() + "original".toByteArray()
        val replacement = minimalGgufBytes() + "replacement".toByteArray()
        val importer = LocalModelImporter(temporaryFolder.root)
        val first = importer.import(candidate(sizeBytes = original.size.toLong())) {
            ByteArrayInputStream(original)
        }

        assertThrows(ModelImportException::class.java) {
            importer.import(candidate(sizeBytes = replacement.size.toLong())) {
                ByteArrayInputStream(replacement)
            }
        }
        assertArrayEquals(original, first.file.readBytes())
    }

    @Test
    fun `wrong magic truncated and empty GGUF are rejected and staging is cleaned`() {
        val invalidPayloads = listOf(
            "NOT_GGUF_AT_ALL".toByteArray(),
            byteArrayOf('G'.code.toByte(), 'G'.code.toByte(), 'U'.code.toByte(), 'F'.code.toByte()),
            byteArrayOf(),
        )

        invalidPayloads.forEachIndexed { index, bytes ->
            val importer = LocalModelImporter(File(temporaryFolder.root, "case-$index"))
            assertThrows(ModelImportException::class.java) {
                importer.import(candidate(sizeBytes = bytes.size.toLong())) { ByteArrayInputStream(bytes) }
            }
        }

        assertTrue(temporaryFolder.root.walkTopDown().none { it.name.contains(".importing.") })
    }

    @Test
    fun `unsupported GGUF version is rejected`() {
        val bytes = minimalGgufBytes(version = 99)

        val failure = assertThrows(ModelImportException::class.java) {
            LocalModelImporter(temporaryFolder.root).import(candidate(sizeBytes = bytes.size.toLong())) {
                ByteArrayInputStream(bytes)
            }
        }

        assertEquals(ModelImportError.INVALID_MODEL_FORMAT, failure.code)
    }

    @Test
    fun `unknown provider size still accepts valid local bytes and rejects empty bytes`() {
        val bytes = minimalGgufBytes()
        val imported = LocalModelImporter(File(temporaryFolder.root, "valid")).import(candidate(sizeBytes = 0L)) {
            ByteArrayInputStream(bytes)
        }
        assertEquals(bytes.size.toLong(), imported.actualSizeBytes)

        assertThrows(ModelImportException::class.java) {
            LocalModelImporter(File(temporaryFolder.root, "empty")).import(candidate(sizeBytes = 0L)) {
                ByteArrayInputStream(byteArrayOf())
            }
        }
    }

    @Test
    fun `known provider size mismatch is rejected`() {
        val bytes = minimalGgufBytes()
        val failure = assertThrows(ModelImportException::class.java) {
            LocalModelImporter(temporaryFolder.root).import(candidate(sizeBytes = bytes.size + 1L)) {
                ByteArrayInputStream(bytes)
            }
        }
        assertEquals(ModelImportError.SIZE_MISMATCH, failure.code)
    }

    @Test
    fun `explicit digest must be canonical and match staged bytes`() {
        val bytes = minimalGgufBytes()
        val upper = sha256Of(bytes).uppercase()
        val matching = LocalModelImporter(File(temporaryFolder.root, "match")).import(
            candidate = candidate(sizeBytes = bytes.size.toLong()),
            expectedSha256 = upper,
        ) { ByteArrayInputStream(bytes) }
        assertEquals(upper.lowercase(), matching.sha256)

        listOf("abc123", " ${sha256Of(bytes)}", "g".repeat(64)).forEachIndexed { index, digest ->
            val failure = assertThrows(ModelImportException::class.java) {
                LocalModelImporter(File(temporaryFolder.root, "invalid-$index")).import(
                    candidate = candidate(sizeBytes = bytes.size.toLong()),
                    expectedSha256 = digest,
                ) { ByteArrayInputStream(bytes) }
            }
            assertEquals(ModelImportError.INVALID_DIGEST, failure.code)
        }

        val mismatch = assertThrows(ModelImportException::class.java) {
            LocalModelImporter(File(temporaryFolder.root, "mismatch")).import(
                candidate = candidate(sizeBytes = bytes.size.toLong()),
                expectedSha256 = "00".repeat(32),
            ) { ByteArrayInputStream(bytes) }
        }
        assertEquals(ModelImportError.DIGEST_MISMATCH, mismatch.code)
    }

    @Test
    fun `registry failure rolls back only newly created final file`() {
        val bytes = minimalGgufBytes() + byteArrayOf(1)
        val importer = LocalModelImporter(temporaryFolder.root)
        val transaction = LocalModelImportTransaction(importer)

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.test.runTest {
                transaction.execute(
                    candidate = candidate(sizeBytes = bytes.size.toLong()),
                    openSource = { ByteArrayInputStream(bytes) },
                    register = { error("injected registry failure") },
                )
            }
        }

        assertTrue(temporaryFolder.root.walkTopDown().none { it.isFile && it.extension == "gguf" })
        assertTrue(temporaryFolder.root.walkTopDown().none { it.name.contains(".importing.") })
    }

    @Test
    fun `rollback never deletes a reused pre-existing valid model`() = kotlinx.coroutines.test.runTest {
        val bytes = minimalGgufBytes() + byteArrayOf(2)
        val importer = LocalModelImporter(temporaryFolder.root)
        val first = importer.import(candidate(sizeBytes = bytes.size.toLong())) { ByteArrayInputStream(bytes) }
        val transaction = LocalModelImportTransaction(importer)

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.test.runTest {
                transaction.execute(
                    candidate = candidate(sizeBytes = bytes.size.toLong()),
                    openSource = { ByteArrayInputStream(bytes) },
                    register = { error("injected registry failure") },
                )
            }
        }

        assertTrue(first.file.isFile)
        assertArrayEquals(bytes, first.file.readBytes())
    }

    private fun candidate(sizeBytes: Long) = LocalModelCandidate(
        displayName = "Qwen",
        uri = "content://tree/models/document/qwen.gguf",
        fileName = "../qwen.gguf",
        extension = "gguf",
        sizeBytes = sizeBytes,
        guessedRole = LocalModelRole.TEXT,
        readable = true,
    )
}
