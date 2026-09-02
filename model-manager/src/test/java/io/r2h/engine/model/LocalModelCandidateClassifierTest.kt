package io.r2h.engine.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalModelCandidateClassifierTest {

    @Test
    fun `gguf is detected as a readable text model`() {
        val candidate = LocalModelCandidateClassifier.create(
            displayName = "Qwen 2.5",
            fileName = "qwen2.5-q4.gguf",
            uri = "content://models/qwen",
            sizeBytes = 1234L,
            readable = true,
            relativePath = "text/qwen2.5-q4.gguf",
        )

        requireNotNull(candidate)
        assertEquals("gguf", candidate.extension)
        assertEquals(LocalModelRole.TEXT, candidate.guessedRole)
        assertTrue(candidate.readable)
    }

    @Test
    fun `onnx role is inferred from folder and filename`() {
        val reranker = LocalModelCandidateClassifier.create(
            displayName = "Reranker",
            fileName = "model.int8.onnx",
            uri = "content://models/reranker",
            sizeBytes = 42L,
            readable = true,
            relativePath = "models/reranker/model.int8.onnx",
        )
        val vision = LocalModelCandidateClassifier.create(
            displayName = "Detector",
            fileName = "yolo11n.onnx",
            uri = "content://models/yolo",
            sizeBytes = 42L,
            readable = true,
            relativePath = "vision/yolo11n.onnx",
        )

        assertEquals(LocalModelRole.RERANKER, reranker?.guessedRole)
        assertEquals(LocalModelRole.VISION, vision?.guessedRole)
    }

    @Test
    fun `unsupported and companion files are not model candidates`() {
        assertNull(LocalModelCandidateClassifier.create("Weights", "weights.pt", "content://pt", 1L, true))
        assertNull(LocalModelCandidateClassifier.create("Config", "config.json", "content://json", 1L, true))
        assertNull(LocalModelCandidateClassifier.create("Tokenizer", "tokenizer.model", "content://tokenizer", 1L, true))
    }
}
