package io.r2h.engine

import io.r2h.engine.api.model.ModelValidationState
import io.r2h.engine.core.ModelCapability
import io.r2h.engine.core.ModelType
import io.r2h.engine.core.Modality
import io.r2h.engine.core.TaskType
import io.r2h.engine.model.LocalModelRecord
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalModelCatalogAdapterTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `valid GGUF record becomes explicit ready TEXT catalog contract`() = runBlocking {
        val modelFile = temporaryFolder.newFile("gemma-local.gguf").apply {
            writeBytes(ByteArray(128) { 7 })
        }
        val record = recordFor(modelFile)

        val adapter = LocalModelCatalogAdapter(
            recordsSource = { listOf(record) },
            validatedPathSource = { modelId ->
                modelFile.absolutePath.takeIf { modelId == record.modelId }
            },
            activeModelSource = { null },
        )

        val catalog = adapter.snapshot()
        val catalogRecord = catalog.single()
        val descriptor = catalogRecord.descriptor

        assertTrue(catalogRecord.installed)
        assertEquals(ModelValidationState.VALID, catalogRecord.validationState)
        assertNull(catalogRecord.validationError)
        assertEquals(record.modelId, descriptor.id)
        assertEquals("llama-cpp", descriptor.backendKey)
        assertEquals(ModelType.GGUF, descriptor.modelType)
        assertEquals(Modality.TEXT, descriptor.modality)
        assertTrue(ModelCapability.Input.Text in descriptor.capabilities)
        assertTrue(ModelCapability.Output.Text in descriptor.capabilities)
        assertTrue(TaskType.TEXT_GENERATION in descriptor.supportedTaskTypes)
        assertTrue(TaskType.CHAT in descriptor.supportedTaskTypes)
    }

    @Test
    fun `descriptor resolution uses the same validated catalog descriptor contract`() = runBlocking {
        val modelFile = temporaryFolder.newFile("gemma-shared.gguf").apply {
            writeBytes(ByteArray(64) { 3 })
        }
        val record = recordFor(modelFile)

        val adapter = LocalModelCatalogAdapter(
            recordsSource = { listOf(record) },
            validatedPathSource = { modelFile.absolutePath },
            activeModelSource = { null },
        )

        val catalogDescriptor = adapter.snapshot().single().descriptor
        val resolvedDescriptor = adapter.resolveDescriptor(record.modelId)

        assertNotNull(resolvedDescriptor)
        assertEquals(catalogDescriptor, resolvedDescriptor)
    }

    @Test
    fun `failed repository validation remains visible but cannot resolve for loading`() = runBlocking {
        val modelFile = temporaryFolder.newFile("invalid.gguf").apply {
            writeBytes(ByteArray(32) { 1 })
        }
        val record = recordFor(modelFile)

        val adapter = LocalModelCatalogAdapter(
            recordsSource = { listOf(record) },
            validatedPathSource = { null },
            activeModelSource = { null },
        )

        val catalogRecord = adapter.snapshot().single()

        assertTrue(catalogRecord.installed)
        assertEquals(ModelValidationState.INVALID, catalogRecord.validationState)
        assertNotNull(catalogRecord.validationError)
        assertFalse(catalogRecord.descriptor.supportedTaskTypes.isEmpty())
        assertNull(adapter.resolveDescriptor(record.modelId))
    }

    private fun recordFor(file: File): LocalModelRecord = LocalModelRecord(
        modelId = file.nameWithoutExtension,
        displayName = file.nameWithoutExtension,
        absolutePath = file.absolutePath,
        sizeBytes = file.length(),
        sha256 = "test-sha256",
        quantization = "Q4_K_M",
    )
}