package io.r2h.engine.model

import java.io.File
import java.io.IOException
import java.util.ArrayDeque
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelManifestTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var manifest: ModelManifest
    private val manifestFile get() = File(temporaryFolder.root, "model_manifest.json")

    @Before
    fun setUp() {
        manifest = ModelManifest.forTesting(temporaryFolder.root)
    }

    @Test
    fun `missing registry is distinct from a valid empty registry`() {
        val missing = manifest.snapshot()
        assertEquals(ModelRegistryState.NO_REGISTRY_EXISTS, missing.state)
        assertTrue(missing.records.isEmpty())

        manifest.writeAll(emptyList())

        val empty = manifest.snapshot()
        assertEquals(ModelRegistryState.READY, empty.state)
        assertTrue(empty.records.isEmpty())
    }

    @Test
    fun `structured JSON round trips escaped quotes backslashes and Unicode`() {
        val record = modelRecord(
            displayName = "Qwen \"quoted\" \\\\ path مرحبا 世界",
            absolutePath = "/data/user/0/io.r2h.engine/files/models/a \\\\ b.gguf",
            quantization = "量化-\\\"Q4\\\"",
        )

        manifest.writeAll(listOf(record))

        assertEquals(listOf(record), manifest.snapshot().records)
    }

    @Test
    fun `strict parser rejects malformed truncated and non-array top-level JSON`() {
        listOf(
            "NOT_VALID_JSON",
            "[{\"modelId\":\"cut",
            "{}",
            "null",
            "\"[]\"",
        ).forEachIndexed { index, json ->
            val root = temporaryFolder.newFolder("malformed-$index")
            File(root, "model_manifest.json").writeText(json)

            val result = ModelManifest.forTesting(root).snapshot()

            assertEquals(ModelRegistryState.CORRUPT_REGISTRY_QUARANTINED, result.state)
            assertTrue(result.records.isEmpty())
        }
    }

    @Test
    fun `strict parser rejects missing unknown wrong-type nested and malformed entries`() {
        val valid = """{"modelId":"m","displayName":"M","absolutePath":"/private/m.gguf","sizeBytes":24,"sha256":"${"ab".repeat(32)}","quantization":"Q4"}"""
        val invalidJson = listOf(
            "[$valid]".replace("\"displayName\":\"M\",", ""),
            "[${valid.dropLast(1)},\"unknown\":true}]",
            "[${valid.replace("\"sizeBytes\":24", "\"sizeBytes\":\"24\"")}]",
            "[${valid.replace("\"displayName\":\"M\"", "\"displayName\":{\"nested\":true}")}]",
            "[null]",
            "[42]",
        )

        invalidJson.forEachIndexed { index, json ->
            val root = temporaryFolder.newFolder("schema-$index")
            File(root, "model_manifest.json").writeText(json)
            assertEquals(
                ModelRegistryState.CORRUPT_REGISTRY_QUARANTINED,
                ModelManifest.forTesting(root).snapshot().state,
            )
        }
    }

    @Test
    fun `strict parser rejects duplicate IDs and malformed digests`() {
        val valid = modelRecord(id = "duplicate")
        manifest.writeAll(listOf(valid))
        val item = manifestFile.readText().trim().removePrefix("[").removeSuffix("]")
        manifestFile.writeText("[$item,$item]")

        assertEquals(ModelRegistryState.CORRUPT_REGISTRY_QUARANTINED, manifest.snapshot().state)

        listOf("abc123", " ${"ab".repeat(32)}", "g".repeat(64)).forEachIndexed { index, digest ->
            val root = temporaryFolder.newFolder("digest-$index")
            assertThrows(ModelRegistryException::class.java) {
                ModelManifest.forTesting(root).writeAll(listOf(modelRecord(sha256 = digest)))
            }
        }
    }

    @Test
    fun `manifest input and record count are bounded`() {
        manifestFile.writeBytes(ByteArray(ModelManifest.MAX_MANIFEST_BYTES + 1) { 'x'.code.toByte() })
        assertEquals(ModelRegistryState.CORRUPT_REGISTRY_QUARANTINED, manifest.snapshot().state)

        val root = temporaryFolder.newFolder("too-many")
        val tooMany = List(ModelManifest.MAX_RECORDS + 1) { modelRecord(id = "m-$it") }
        assertThrows(ModelRegistryException::class.java) {
            ModelManifest.forTesting(root).writeAll(tooMany)
        }
    }

    @Test
    fun `corrupt bytes are quarantined privately without touching model files`() {
        val corrupt = byteArrayOf(0x13, 0x37, 0x00, 0x7f)
        val modelA = File(temporaryFolder.root, "models/a.gguf").writeMinimalGguf()
        val modelB = File(temporaryFolder.root, "models/b.gguf").writeMinimalGguf()
        manifestFile.writeBytes(corrupt)

        val result = manifest.snapshot()

        assertEquals(ModelRegistryState.CORRUPT_REGISTRY_QUARANTINED, result.state)
        assertFalse(manifestFile.exists())
        val quarantines = temporaryFolder.root.listFiles().orEmpty()
            .filter { it.name.startsWith("model_manifest.corrupt.") && it.name.endsWith(".json") }
        assertEquals(1, quarantines.size)
        assertArrayEquals(corrupt, quarantines.single().readBytes())
        assertTrue(modelA.isFile)
        assertTrue(modelB.isFile)
    }

    @Test
    fun `quarantine collision never overwrites prior evidence`() {
        val first = File(temporaryFolder.root, "model_manifest.corrupt.fixed.json").also {
            it.writeText("prior evidence")
        }
        manifestFile.writeText("broken")
        manifest = ModelManifest.forTesting(temporaryFolder.root, uniqueToken = { "fixed" })

        assertEquals(ModelRegistryState.CORRUPT_REGISTRY_QUARANTINED, manifest.snapshot().state)
        assertEquals("prior evidence", first.readText())
        assertTrue(File(temporaryFolder.root, "model_manifest.corrupt.fixed.1.json").isFile)
    }

    @Test
    fun `failed quarantine leaves original corrupt bytes in place`() {
        val bytes = "broken registry".toByteArray()
        manifestFile.writeBytes(bytes)
        manifest = ModelManifest.forTesting(
            temporaryFolder.root,
            faultInjector = object : ModelManifestFaultInjector {
                override fun beforeQuarantine() = throw IOException("injected quarantine failure")
            },
        )

        val failure = assertThrows(ModelRegistryException::class.java) { manifest.snapshot() }

        assertEquals(ModelRegistryErrorCode.QUARANTINE_FAILED, failure.code)
        assertArrayEquals(bytes, manifestFile.readBytes())
    }

    @Test
    fun `temp write sync and replace failures preserve previous manifest`() {
        val previous = modelRecord(id = "previous")
        manifest.writeAll(listOf(previous))

        listOf(
            FailingPhase.TEMP_WRITE to ModelRegistryErrorCode.TEMP_WRITE_FAILED,
            FailingPhase.SYNC to ModelRegistryErrorCode.SYNC_FAILED,
            FailingPhase.REPLACE to ModelRegistryErrorCode.REPLACE_FAILED,
        ).forEach { (phase, expectedCode) ->
            val failing = ModelManifest.forTesting(
                temporaryFolder.root,
                faultInjector = OneShotFaultInjector(phase),
            )

            val failure = assertThrows(ModelRegistryException::class.java) {
                val safePhase = phase.name.lowercase().replace('_', '-')
                failing.writeAll(listOf(modelRecord(id = "replacement-$safePhase")))
            }
            assertEquals(expectedCode, failure.code)
            assertEquals(listOf(previous), manifest.snapshot().records)
        }
    }

    @Test
    fun `quarantine evidence remains after deliberate recovery write`() {
        val corrupt = "corrupt registry bytes".toByteArray()
        manifestFile.writeBytes(corrupt)
        assertEquals(ModelRegistryState.CORRUPT_REGISTRY_QUARANTINED, manifest.snapshot().state)
        val quarantine = temporaryFolder.root.listFiles().orEmpty().single { it.name.contains(".corrupt.") }

        manifest.writeAll(listOf(modelRecord(id = "recovered")))

        assertEquals(listOf("recovered"), manifest.snapshot().records.map { it.modelId })
        assertArrayEquals(corrupt, quarantine.readBytes())
    }

    private enum class FailingPhase { TEMP_WRITE, SYNC, REPLACE }

    private class OneShotFaultInjector(failingPhase: FailingPhase) : ModelManifestFaultInjector {
        private val remaining = ArrayDeque(listOf(failingPhase))

        override fun beforeTempWrite() = failIf(FailingPhase.TEMP_WRITE)
        override fun beforeSync() = failIf(FailingPhase.SYNC)
        override fun beforeReplace() = failIf(FailingPhase.REPLACE)

        private fun failIf(phase: FailingPhase) {
            if (remaining.firstOrNull() == phase) {
                remaining.removeFirst()
                throw IOException("injected $phase failure")
            }
        }
    }
}
