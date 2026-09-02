package io.r2h.engine.model

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed interface LocalModelScanResult {
    data object NoFolderSelected : LocalModelScanResult
    data class FolderUnreadable(val folderUri: String, val reason: String) : LocalModelScanResult
    data class Success(
        val folderUri: String,
        val folderDisplayName: String,
        val candidates: List<LocalModelCandidate>,
    ) : LocalModelScanResult
}

class LocalModelFolderManager(context: Context) {
    private val appContext = context.applicationContext
    private val selectionStore = LocalModelSelectionStore.from(appContext)
    private val importer = LocalModelImporter(appContext.filesDir)
    private val importTransaction = LocalModelImportTransaction(importer)

    val selectedFolderUri: String? get() = selectionStore.selectedFolderUri
    val activeModel: ActiveLocalModel? get() = selectionStore.activeModel

    fun persistFolderSelection(uri: Uri, resultFlags: Int) {
        val readFlag = resultFlags and Intent.FLAG_GRANT_READ_URI_PERMISSION
        check(readFlag != 0) { "The selected folder did not grant read access." }
        appContext.contentResolver.takePersistableUriPermission(uri, readFlag)
        selectionStore.selectedFolderUri = uri.toString()
    }

    suspend fun scanSelectedFolder(): LocalModelScanResult = withContext(Dispatchers.IO) {
        val uriText = selectionStore.selectedFolderUri ?: return@withContext LocalModelScanResult.NoFolderSelected
        try {
            val treeUri = Uri.parse(uriText)
            val root = DocumentFile.fromTreeUri(appContext, treeUri)
                ?: return@withContext LocalModelScanResult.FolderUnreadable(uriText, "Android could not open the selected folder.")
            if (!root.exists() || !root.isDirectory || !root.canRead()) {
                return@withContext LocalModelScanResult.FolderUnreadable(uriText, "The selected folder is no longer readable. Select it again.")
            }

            val candidates = mutableListOf<LocalModelCandidate>()
            fun visit(directory: DocumentFile, relativePath: String, depth: Int) {
                if (depth > MAX_SCAN_DEPTH || candidates.size >= MAX_CANDIDATES) return
                directory.listFiles().forEach { document ->
                    val name = document.name.orEmpty()
                    val childPath = if (relativePath.isBlank()) name else "$relativePath/$name"
                    when {
                        document.isDirectory -> visit(document, childPath, depth + 1)
                        document.isFile && LocalModelCandidateClassifier.isSupported(name) -> {
                            val readable = document.canRead() && runCatching {
                                appContext.contentResolver.openAssetFileDescriptor(document.uri, "r")?.use { true } ?: false
                            }.getOrDefault(false)
                            val candidate = LocalModelCandidateClassifier.create(
                                displayName = name.substringBeforeLast('.'),
                                fileName = name,
                                uri = document.uri.toString(),
                                sizeBytes = document.length(),
                                readable = readable,
                                relativePath = childPath,
                            )
                            if (candidate != null) candidates += candidate
                        }
                    }
                }
            }
            visit(root, "", 0)
            LocalModelScanResult.Success(
                folderUri = uriText,
                folderDisplayName = root.name ?: uriText,
                candidates = candidates.sortedWith(compareBy({ it.guessedRole.name }, { it.displayName.lowercase() })),
            )
        } catch (t: Throwable) {
            LocalModelScanResult.FolderUnreadable(uriText, t.message ?: t.javaClass.simpleName)
        }
    }

    suspend fun importAndActivate(
        candidate: LocalModelCandidate,
        register: suspend (File) -> String,
    ): ActiveLocalModel = withContext(Dispatchers.IO) {
        val committed = importTransaction.execute(
            candidate = candidate,
            openSource = {
                appContext.contentResolver.openInputStream(Uri.parse(candidate.uri))
                    ?: error("The selected model can no longer be opened.")
            },
            register = register,
        )
        selectionStore.saveActive(candidate, committed.import.file.absolutePath, committed.modelId)
        selectionStore.activeModel ?: error("The active model selection could not be persisted.")
    }

    companion object {
        private const val MAX_SCAN_DEPTH = 12
        private const val MAX_CANDIDATES = 2_000
    }
}
