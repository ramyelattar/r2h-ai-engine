package io.r2h.engine.model

import android.content.Context

data class ActiveLocalModel(
    val sourceUri: String,
    val displayName: String,
    val fileName: String,
    val extension: String,
    val sizeBytes: Long,
    val guessedRole: LocalModelRole,
    val importedPath: String,
    val modelId: String,
)

interface LocalModelPreferences {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(keys: Set<String>)
}

class LocalModelSelectionStore(private val preferences: LocalModelPreferences) {
    var selectedFolderUri: String?
        get() = preferences.getString(KEY_FOLDER_URI)
        set(value) {
            if (value == null) preferences.remove(setOf(KEY_FOLDER_URI))
            else preferences.putString(KEY_FOLDER_URI, value)
        }

    val activeModel: ActiveLocalModel?
        get() {
            val sourceUri = preferences.getString(KEY_ACTIVE_SOURCE_URI) ?: return null
            val displayName = preferences.getString(KEY_ACTIVE_DISPLAY_NAME) ?: return null
            val fileName = preferences.getString(KEY_ACTIVE_FILE_NAME) ?: return null
            val extension = preferences.getString(KEY_ACTIVE_EXTENSION) ?: return null
            val importedPath = preferences.getString(KEY_ACTIVE_IMPORTED_PATH) ?: return null
            val modelId = preferences.getString(KEY_ACTIVE_MODEL_ID) ?: return null
            val sizeBytes = preferences.getString(KEY_ACTIVE_SIZE_BYTES)?.toLongOrNull() ?: 0L
            val role = preferences.getString(KEY_ACTIVE_ROLE)
                ?.let { runCatching { LocalModelRole.valueOf(it) }.getOrNull() }
                ?: LocalModelRole.UNKNOWN
            return ActiveLocalModel(
                sourceUri = sourceUri,
                displayName = displayName,
                fileName = fileName,
                extension = extension,
                sizeBytes = sizeBytes,
                guessedRole = role,
                importedPath = importedPath,
                modelId = modelId,
            )
        }

    fun saveActive(candidate: LocalModelCandidate, importedPath: String, modelId: String) {
        preferences.putString(KEY_ACTIVE_SOURCE_URI, candidate.uri)
        preferences.putString(KEY_ACTIVE_DISPLAY_NAME, candidate.displayName)
        preferences.putString(KEY_ACTIVE_FILE_NAME, candidate.fileName)
        preferences.putString(KEY_ACTIVE_EXTENSION, candidate.extension)
        preferences.putString(KEY_ACTIVE_SIZE_BYTES, candidate.sizeBytes.toString())
        preferences.putString(KEY_ACTIVE_ROLE, candidate.guessedRole.name)
        preferences.putString(KEY_ACTIVE_IMPORTED_PATH, importedPath)
        preferences.putString(KEY_ACTIVE_MODEL_ID, modelId)
    }

    fun clearFolderSelection() {
        preferences.remove(ALL_KEYS)
    }

    companion object {
        private const val KEY_FOLDER_URI = "selected_folder_uri"
        private const val KEY_ACTIVE_SOURCE_URI = "active_source_uri"
        private const val KEY_ACTIVE_DISPLAY_NAME = "active_display_name"
        private const val KEY_ACTIVE_FILE_NAME = "active_file_name"
        private const val KEY_ACTIVE_EXTENSION = "active_extension"
        private const val KEY_ACTIVE_SIZE_BYTES = "active_size_bytes"
        private const val KEY_ACTIVE_ROLE = "active_role"
        private const val KEY_ACTIVE_IMPORTED_PATH = "active_imported_path"
        private const val KEY_ACTIVE_MODEL_ID = "active_model_id"
        private val ALL_KEYS = setOf(
            KEY_FOLDER_URI,
            KEY_ACTIVE_SOURCE_URI,
            KEY_ACTIVE_DISPLAY_NAME,
            KEY_ACTIVE_FILE_NAME,
            KEY_ACTIVE_EXTENSION,
            KEY_ACTIVE_SIZE_BYTES,
            KEY_ACTIVE_ROLE,
            KEY_ACTIVE_IMPORTED_PATH,
            KEY_ACTIVE_MODEL_ID,
        )

        fun from(context: Context): LocalModelSelectionStore = LocalModelSelectionStore(
            SharedPreferencesLocalModelPreferences(context.applicationContext),
        )
    }
}

private class SharedPreferencesLocalModelPreferences(context: Context) : LocalModelPreferences {
    private val preferences = context.getSharedPreferences("local_model_manager", Context.MODE_PRIVATE)

    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun putString(key: String, value: String) {
        check(preferences.edit().putString(key, value).commit()) { "Could not persist local model selection." }
    }

    override fun remove(keys: Set<String>) {
        val editor = preferences.edit()
        keys.forEach(editor::remove)
        check(editor.commit()) { "Could not clear local model selection." }
    }
}
