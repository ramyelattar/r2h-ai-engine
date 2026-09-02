package io.r2h.engine.model

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [ModelRepository].
 *
 * Uses a [TemporaryFolder] rule so that real file I/O can be exercised without an
 * Android device. [Context.filesDir] is mocked to point at the temp directory.
 */
class ModelRepositoryTest {

    @get:Rule
    val tmpFolder = TemporaryFolder()

    private lateinit var repo: ModelRepository
    private lateinit var modelsDir: File

    @Before
    fun setUp() {
        repo = ModelRepository.forTesting(tmpFolder.root)
        modelsDir = File(tmpFolder.root, "models").also { it.mkdirs() }
    }

    // ─── register ─────────────────────────────────────────────────────────────

    @Test
    fun `register returns null when file does not exist`() = runTest {
        val result = repo.register("missing.gguf", "Missing", "Q4_K_M")
        assertNull(result)
    }

    @Test
    fun `register stores record and computes sha256`() = runTest {
        val file = createGgufFile("mymodel.gguf", content = "fake model content")
        val record = repo.register("mymodel.gguf", "My Model", "Q4_K_M")
        assertNotNull(record)
        assertEquals("mymodel", record!!.modelId)
        assertEquals("My Model", record.displayName)
        assertEquals(file.length(), record.sizeBytes)
        assertEquals(sha256Of(file), record.sha256)
    }

    @Test
    fun `register with explicit sha256 validates and normalizes matching digest`() = runTest {
        createGgufFile("explicit.gguf", content = "data")
        val knownHash = sha256Of(File(modelsDir, "explicit.gguf")).uppercase()
        val record = repo.register("explicit.gguf", "Explicit", "Q8_0", sha256 = knownHash)
        assertNotNull(record)
        assertEquals(knownHash.lowercase(), record!!.sha256)
    }

    @Test
    fun `read-only repository cannot mutate the registry`() = runTest {
        createGgufFile("blocked.gguf", "blocked")
        val reader = ModelRepository.forTesting(tmpFolder.root, mutationEnabled = false)

        val failure = runCatching { reader.register("blocked.gguf", "Blocked", "Q4") }
            .exceptionOrNull() as ModelRegistryException

        assertEquals(ModelRegistryErrorCode.NOT_MUTATION_OWNER, failure.code)
        assertEquals(ModelRegistryState.NO_REGISTRY_EXISTS, reader.registrySnapshot().state)
    }

    @Test
    fun `register rejects malformed and mismatched explicit digests`() = runTest {
        createGgufFile("explicit.gguf", content = "data")

        listOf("abc123", " ${"ab".repeat(32)}", "g".repeat(64)).forEach { digest ->
            assertNull(repo.register("explicit.gguf", "Explicit", "Q8_0", sha256 = digest))
        }
        assertNull(repo.register("explicit.gguf", "Explicit", "Q8_0", sha256 = "00".repeat(32)))
        assertTrue(repo.listRecords().isEmpty())
    }

    @Test
    fun `register normalises model id from file name`() = runTest {
        createGgufFile("Llama-3.2-3B-Q4_K_M.gguf", content = "x")
        val record = repo.register("Llama-3.2-3B-Q4_K_M.gguf", "Llama", "Q4_K_M")
        assertNotNull(record)
        // Non-alphanumeric chars become '-'
        assertEquals("llama-3-2-3b-q4-k-m", record!!.modelId)
    }

    @Test
    fun `register blocks path traversal`() = runTest {
        // Create the file outside the models directory.
        val outside = File(tmpFolder.root, "outside.gguf").also { it.writeText("bad") }
        val result = repo.register("../outside.gguf", "Bad", "Q4_K_M")
        assertNull(result)
    }

    @Test
    fun `registerImportedFile accepts supported model nested under private models root`() = runTest {
        val imported = File(modelsDir, "imported/stable/model.onnx").also {
            it.parentFile?.mkdirs()
            it.writeText("onnx payload")
        }

        val record = repo.registerImportedFile(imported, "Vision model")

        assertNotNull(record)
        assertEquals(imported.canonicalPath, record?.absolutePath)
        assertEquals("model", record?.modelId)
    }

    @Test
    fun `registerImportedFile rejects model outside private models root`() = runTest {
        val outside = File(tmpFolder.root, "outside.gguf").also { it.writeText("bad") }

        assertNull(repo.registerImportedFile(outside, "Outside"))
    }

    @Test
    fun `register twice with same modelId replaces old entry`() = runTest {
        createGgufFile("model.gguf", content = "v1")
        val first = repo.register("model.gguf", "V1", "Q4_K_M")
        assertNotNull(first)

        val second = repo.register("model.gguf", "V2", "Q8_0")
        assertNotNull(second)

        val models = repo.listModels()
        assertEquals(1, models.size)
        assertEquals("V2", models[0].displayName)
    }

    // ─── unregister ───────────────────────────────────────────────────────────

    @Test
    fun `unregister removes existing record`() = runTest {
        createGgufFile("model.gguf", content = "x")
        repo.register("model.gguf", "Model", "Q4_K_M")
        val removed = repo.unregister("model")
        assertTrue(removed)
        assertTrue(repo.listModels().isEmpty())
    }

    @Test
    fun `unregister returns false for unknown modelId`() = runTest {
        val removed = repo.unregister("does-not-exist")
        assertTrue(!removed)
    }

    // ─── resolveValidatedPath ─────────────────────────────────────────────────

    @Test
    fun `resolveValidatedPath returns path for valid model`() = runTest {
        val file = createGgufFile("valid.gguf", content = "hello")
        val sha256 = sha256Of(file)
        repo.register("valid.gguf", "Valid", "Q4_K_M", sha256 = sha256)
        val path = repo.resolveValidatedPath("valid")
        assertEquals(file.absolutePath, path)
    }

    @Test
    fun `resolveValidatedPath returns null for unknown modelId`() = runTest {
        assertNull(repo.resolveValidatedPath("unknown"))
    }

    @Test
    fun `resolveValidatedPath returns null when file has been deleted`() = runTest {
        val file = createGgufFile("deleted.gguf", content = "data")
        repo.register("deleted.gguf", "Deleted", "Q4_K_M")
        file.delete()
        assertNull(repo.resolveValidatedPath("deleted"))
    }

    @Test
    fun `resolveValidatedPath returns null when sha256 does not match`() = runTest {
        createGgufFile("tampered.gguf", content = "original content")
        val record = repo.register("tampered.gguf", "Tampered", "Q4_K_M")
        assertNotNull(record)
        File(modelsDir, "tampered.gguf").appendText("tampered")
        assertNull(repo.resolveValidatedPath("tampered"))
    }

    // ─── syncWithDisk ─────────────────────────────────────────────────────────

    @Test
    fun `syncWithDisk registers gguf files not in manifest`() = runTest {
        createGgufFile("new-model.gguf", content = "model data")
        val discovered = repo.syncWithDisk()
        assertEquals(1, discovered.size)
        assertEquals("new-model", discovered[0].modelId)
        assertEquals(1, repo.listModels().size)
    }

    @Test
    fun `syncWithDisk skips files already in manifest`() = runTest {
        val file = createGgufFile("existing.gguf", content = "data")
        repo.register("existing.gguf", "Existing", "Q4_K_M")
        val discovered = repo.syncWithDisk()
        assertTrue(discovered.isEmpty())
        assertEquals(1, repo.listModels().size)
    }

    @Test
    fun `syncWithDisk ignores non-gguf files`() = runTest {
        File(modelsDir, "readme.txt").writeText("not a model")
        val discovered = repo.syncWithDisk()
        assertTrue(discovered.isEmpty())
    }

    @Test
    fun `syncWithDisk ignores renamed garbage GGUF`() = runTest {
        File(modelsDir, "garbage.gguf").writeText("not a GGUF model")

        assertTrue(repo.syncWithDisk().isEmpty())
        assertTrue(repo.listRecords().isEmpty())
    }

    @Test
    fun `registry recovery quarantines corruption preserves model files and allows deliberate new registration`() = runTest {
        val modelA = createGgufFile("a.gguf", "a")
        val modelB = createGgufFile("b.gguf", "b")
        assertNotNull(repo.register("a.gguf", "A", "Q4"))
        assertNotNull(repo.register("b.gguf", "B", "Q4"))
        val corruptBytes = "damaged registry bytes".toByteArray()
        File(tmpFolder.root, "model_manifest.json").writeBytes(corruptBytes)

        val reopened = ModelRepository.forTesting(tmpFolder.root)
        val recovery = reopened.registrySnapshot()

        assertEquals(ModelRegistryState.CORRUPT_REGISTRY_QUARANTINED, recovery.state)
        assertTrue(modelA.isFile)
        assertTrue(modelB.isFile)
        val modelC = createGgufFile("c.gguf", "c")
        assertNotNull(reopened.register("c.gguf", "C", "Q4"))
        assertEquals(listOf("c"), reopened.listRecords().map { it.modelId })
        assertTrue(modelC.isFile)
        val quarantine = tmpFolder.root.listFiles().orEmpty().single { it.name.contains(".corrupt.") }
        assertEquals(corruptBytes.toList(), quarantine.readBytes().toList())
    }

    @Test
    fun `mutation that first discovers corruption fails before any replacement is written`() = runTest {
        createGgufFile("existing.gguf", "existing")
        assertNotNull(repo.register("existing.gguf", "Existing", "Q4"))
        val corruptBytes = "corrupt-before-mutation".toByteArray()
        File(tmpFolder.root, "model_manifest.json").writeBytes(corruptBytes)
        createGgufFile("new.gguf", "new")

        val failure = runCatching { repo.register("new.gguf", "New", "Q4") }.exceptionOrNull()

        assertTrue(failure is ModelRegistryException)
        assertEquals(
            ModelRegistryErrorCode.CORRUPT_REGISTRY_QUARANTINED,
            (failure as ModelRegistryException).code,
        )
        assertFalse(File(tmpFolder.root, "model_manifest.json").exists())
        val quarantine = tmpFolder.root.listFiles().orEmpty().single { it.name.contains(".corrupt.") }
        assertEquals(corruptBytes.toList(), quarantine.readBytes().toList())

        assertNotNull(repo.register("new.gguf", "New", "Q4"))
        assertEquals(listOf("new"), repo.listRecords().map { it.modelId })
        assertTrue(quarantine.isFile)
    }

    @Test
    fun `parallel distinct registrations do not lose successful mutations`() = runTest {
        val files = (0 until 24).map { index -> createGgufFile("parallel-$index.gguf", "$index") }

        val results = files.map { file ->
            async(Dispatchers.Default) {
                repo.register(file.name, file.nameWithoutExtension, "Q4")
            }
        }.awaitAll()

        assertTrue(results.all { it != null })
        assertEquals(files.map { it.nameWithoutExtension }.toSet(), repo.listRecords().map { it.modelId }.toSet())
    }

    @Test
    fun `concurrent register unregister and sync preserve the combined committed state`() = runTest {
        val removedFile = createGgufFile("remove.gguf", "remove")
        createGgufFile("discover.gguf", "discover")
        assertNotNull(repo.register("remove.gguf", "Remove", "Q4"))
        assertTrue(removedFile.delete())
        createGgufFile("register.gguf", "register")

        awaitAll(
            async(Dispatchers.Default) { repo.unregister("remove") },
            async(Dispatchers.Default) { repo.register("register.gguf", "Register", "Q4") },
            async(Dispatchers.Default) { repo.syncWithDisk() },
        )

        val ids = repo.listRecords().map { it.modelId }.toSet()
        assertFalse("explicit unregister must win over sync discovery", "remove" in ids)
        assertTrue("register" in ids)
        assertTrue("discover" in ids)
    }

    @Test
    fun `same ID concurrent registration rejects different content without overwriting the winner`() = runTest {
        val first = File(modelsDir, "one/shared.gguf").writeMinimalGguf(suffix = byteArrayOf(1))
        val second = File(modelsDir, "two/shared.gguf").writeMinimalGguf(suffix = byteArrayOf(2))

        val outcomes = awaitAll(
            async(Dispatchers.Default) { repo.registerImportedFile(first, "First") },
            async(Dispatchers.Default) { repo.registerImportedFile(second, "Second") },
        )

        assertEquals(1, outcomes.count { it != null })
        val committed = repo.listRecords().single()
        assertTrue(committed.absolutePath == first.absolutePath || committed.absolutePath == second.absolutePath)
        assertEquals(sha256Of(File(committed.absolutePath)), committed.sha256)
    }

    @Test
    fun `reader during blocked replacement waits and observes new complete state`() = runTest {
        createGgufFile("old.gguf", "old")
        assertNotNull(repo.register("old.gguf", "Old", "Q4"))
        createGgufFile("new.gguf", "new")
        val hook = BlockingReplaceFault(fail = false)
        val writer = ModelRepository.forTesting(tmpFolder.root, faultInjector = hook)
        val reader = ModelRepository.forTesting(tmpFolder.root, mutationEnabled = false)

        val write = async(Dispatchers.Default) { writer.register("new.gguf", "New", "Q4") }
        assertTrue(hook.entered.await(5, TimeUnit.SECONDS))
        val read = async(Dispatchers.Default) { reader.listRecords() }
        assertFalse(read.isCompleted)
        hook.release.countDown()
        assertNotNull(write.await())
        assertEquals(setOf("old", "new"), read.await().map { it.modelId }.toSet())
    }

    @Test
    fun `failed mutation releases lock so queued valid mutation commits`() = runTest {
        createGgufFile("failed.gguf", "failed")
        createGgufFile("valid.gguf", "valid")
        val hook = BlockingReplaceFault(fail = true)
        val writer = ModelRepository.forTesting(tmpFolder.root, faultInjector = hook)
        val nextWriter = ModelRepository.forTesting(tmpFolder.root)

        val failed = async(Dispatchers.Default) { runCatching { writer.register("failed.gguf", "Failed", "Q4") } }
        assertTrue(hook.entered.await(5, TimeUnit.SECONDS))
        val valid = async(Dispatchers.Default) { nextWriter.register("valid.gguf", "Valid", "Q4") }
        hook.release.countDown()

        assertTrue(failed.await().isFailure)
        assertNotNull(valid.await())
        assertEquals(listOf("valid"), repo.listRecords().map { it.modelId })
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun createGgufFile(name: String, content: String): File {
        return File(modelsDir, name).writeMinimalGguf(suffix = content.toByteArray())
    }

    private class BlockingReplaceFault(private val fail: Boolean) : ModelManifestFaultInjector {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)

        override fun beforeReplace() {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS)) { "test did not release replacement hook" }
            if (fail) throw IOException("injected replacement failure")
        }
    }
}
