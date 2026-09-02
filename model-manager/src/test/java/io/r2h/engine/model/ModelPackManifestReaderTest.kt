package io.r2h.engine.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ModelPackManifestReaderTest {
    @Test
    fun `parse reads models artifacts hashes sizes statuses and capabilities`() {
        val manifest = ModelPackManifestReader.parse(SAMPLE_MANIFEST)

        assertEquals(1, manifest.schemaVersion)
        assertEquals("r2h-test-pack", manifest.packId)
        assertEquals(2, manifest.totalFiles)
        assertEquals(579L, manifest.totalBytes)
        assertEquals(1, manifest.models.size)

        val model = manifest.models.single()
        assertEquals("qwen2-vl-2b-instruct-q4-k-m", model.id)
        assertEquals("qwen2vl", model.family)
        assertEquals("llama.cpp-mtmd", model.runtime)
        assertEquals("MODEL_PRESENT_RUNTIME_UNVERIFIED", model.status)
        assertEquals(listOf("TEXT_GENERATION", "IMAGE_TEXT_MULTIMODAL"), model.capabilities)

        val artifact = manifest.findArtifact("models/qwen2vl/Qwen2-VL-2B-Instruct-Q4_K_M.gguf")
        assertNotNull(artifact)
        assertEquals(123L, artifact!!.sizeBytes)
        assertEquals("ABCDEF", artifact.sha256)
        assertEquals(".gguf", artifact.extension)
    }

    private companion object {
        const val SAMPLE_MANIFEST = """
        {
          "schemaVersion": 1,
          "packId": "r2h-test-pack",
          "generatedAtUtc": "2026-06-17T00:00:00Z",
          "totalFiles": 2,
          "totalBytes": 579,
          "models": [
            {
              "id": "qwen2-vl-2b-instruct-q4-k-m",
              "family": "qwen2vl",
              "runtime": "llama.cpp-mtmd",
              "capabilities": ["TEXT_GENERATION", "IMAGE_TEXT_MULTIMODAL"],
              "status": "MODEL_PRESENT_RUNTIME_UNVERIFIED",
              "artifacts": [
                {
                  "path": "models/qwen2vl/Qwen2-VL-2B-Instruct-Q4_K_M.gguf",
                  "sizeBytes": 123,
                  "sizeMB": 0.01,
                  "sha256": "ABCDEF",
                  "extension": ".gguf"
                }
              ]
            }
          ]
        }
        """
    }
}
