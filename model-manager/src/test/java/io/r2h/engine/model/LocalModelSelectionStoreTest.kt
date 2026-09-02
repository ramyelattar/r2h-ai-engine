package io.r2h.engine.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalModelSelectionStoreTest {

    @Test
    fun `folder URI and active model metadata round trip`() {
        val store = LocalModelSelectionStore(FakeLocalModelPreferences())
        val candidate = LocalModelCandidate(
            displayName = "Qwen Local",
            uri = "content://tree/models/document/qwen.gguf",
            fileName = "qwen.gguf",
            extension = "gguf",
            sizeBytes = 4096L,
            guessedRole = LocalModelRole.TEXT,
            readable = true,
        )

        store.selectedFolderUri = "content://tree/models"
        store.saveActive(candidate, "/data/user/0/io.r2h.engine/files/models/imported/abc/qwen.gguf", "qwen")

        assertEquals("content://tree/models", store.selectedFolderUri)
        assertEquals(
            ActiveLocalModel(
                sourceUri = candidate.uri,
                displayName = candidate.displayName,
                fileName = candidate.fileName,
                extension = candidate.extension,
                sizeBytes = candidate.sizeBytes,
                guessedRole = candidate.guessedRole,
                importedPath = "/data/user/0/io.r2h.engine/files/models/imported/abc/qwen.gguf",
                modelId = "qwen",
            ),
            store.activeModel,
        )
    }

    @Test
    fun `clearing folder also clears active selection`() {
        val store = LocalModelSelectionStore(FakeLocalModelPreferences())
        store.selectedFolderUri = "content://tree/models"
        store.saveActive(
            LocalModelCandidate("Model", "content://model", "model.gguf", "gguf", 1L, LocalModelRole.TEXT, true),
            "/private/model.gguf",
            "model",
        )

        store.clearFolderSelection()

        assertNull(store.selectedFolderUri)
        assertNull(store.activeModel)
    }

    private class FakeLocalModelPreferences : LocalModelPreferences {
        private val values = mutableMapOf<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String) { values[key] = value }
        override fun remove(keys: Set<String>) { keys.forEach(values::remove) }
    }
}
