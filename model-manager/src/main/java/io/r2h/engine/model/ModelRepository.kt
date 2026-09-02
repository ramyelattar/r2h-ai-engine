package io.r2h.engine.model

import android.app.Application
import android.content.Context
import android.util.Log
import io.r2h.engine.api.model.ModelInfo
import io.r2h.engine.api.privacy.DiagnosticOperation
import io.r2h.engine.api.privacy.DiagnosticStatus
import io.r2h.engine.api.privacy.PrivacySafeDiagnostics
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private const val TAG = "ModelRepository"
private const val MODELS_DIR = "models"

/**
 * Read authority for the private model catalog and, only when explicitly enabled,
 * the serialized mutation authority for its manifest.
 */
class ModelRepository private constructor(
    filesDir: File,
    private val mutationEnabled: Boolean,
    private val manifest: ModelManifest,
) {
    private val modelsDir = File(filesDir, MODELS_DIR).canonicalFile.also { it.mkdirs() }
    private val validator = ModelValidator()
    private val mutationMutex = ModelRegistryMutexes.forPath(manifest.pathKey)

    suspend fun registrySnapshot(): ModelRegistrySnapshot = withContext(Dispatchers.IO) {
        mutationMutex.withLock { manifest.snapshot() }
    }

    suspend fun listModels(): List<ModelInfo> = withContext(Dispatchers.IO) {
        loadReadableRecords().map { it.toModelInfo(isLoaded = false) }
    }

    suspend fun listRecords(): List<LocalModelRecord> = withContext(Dispatchers.IO) {
        loadReadableRecords()
    }

    suspend fun resolveValidatedPath(modelId: String): String? = withContext(Dispatchers.IO) {
        val record = loadReadableRecords().firstOrNull { it.modelId == modelId }
            ?: return@withContext null
        when (validator.validate(record)) {
            is ModelValidationResult.Valid -> record.absolutePath
            is ModelValidationResult.FileMissing,
            is ModelValidationResult.DigestMismatch,
            is ModelValidationResult.IoError,
            -> {
                safeLog("VALIDATION_REJECTED")
                null
            }
        }
    }

    suspend fun register(
        fileName: String,
        displayName: String,
        quantization: String,
        sha256: String? = null,
    ): LocalModelRecord? = withContext(Dispatchers.IO) {
        requireMutationAuthority()
        val file = runCatching { File(modelsDir, fileName).canonicalFile }.getOrNull()
            ?: return@withContext null
        if (!isWithinModelsRoot(file) || !file.isFile) {
            safeLog("PATH_REJECTED")
            return@withContext null
        }
        registerValidatedFile(file, displayName, quantization, sha256)
    }

    suspend fun registerImportedFile(
        file: File,
        displayName: String,
        quantization: String = "UNKNOWN",
    ): LocalModelRecord? = withContext(Dispatchers.IO) {
        requireMutationAuthority()
        val canonical = runCatching { file.canonicalFile }.getOrNull() ?: return@withContext null
        if (!isWithinModelsRoot(canonical) || !canonical.isFile) {
            safeLog("PATH_REJECTED")
            return@withContext null
        }
        registerValidatedFile(canonical, displayName, quantization, expectedSha256 = null)
    }

    suspend fun unregister(modelId: String): Boolean = withContext(Dispatchers.IO) {
        mutate(allowDeliberateRecovery = false) { current ->
            val next = current.filterNot { it.modelId == modelId }
            RegistryMutation(
                nextRecords = next,
                result = next.size != current.size,
                changed = next.size != current.size,
            )
        }
    }

    suspend fun syncWithDisk(): List<LocalModelRecord> = withContext(Dispatchers.IO) {
        mutate(allowDeliberateRecovery = false) { current ->
            val byId = LinkedHashMap<String, LocalModelRecord>()
            current.forEach { byId[it.modelId] = it }
            val discovered = mutableListOf<LocalModelRecord>()

            modelsDir.walkTopDown()
                .filter { it.isFile && LocalModelCandidateClassifier.isSupported(it.name) }
                .sortedBy { it.canonicalPath }
                .forEach { file ->
                    val modelId = modelIdFor(file)
                    if (modelId.isBlank() || byId.containsKey(modelId)) return@forEach
                    val digest = validateAndDigest(file) ?: return@forEach
                    val record = LocalModelRecord(
                        modelId = modelId,
                        displayName = file.nameWithoutExtension,
                        absolutePath = file.canonicalPath,
                        sizeBytes = file.length(),
                        sha256 = digest,
                        quantization = "UNKNOWN",
                    )
                    byId[modelId] = record
                    discovered += record
                }

            RegistryMutation(
                nextRecords = byId.values.toList(),
                result = discovered,
                changed = discovered.isNotEmpty(),
            )
        }
    }

    private suspend fun registerValidatedFile(
        file: File,
        displayName: String,
        quantization: String,
        expectedSha256: String?,
    ): LocalModelRecord? {
        if (!LocalModelCandidateClassifier.isSupported(file.name)) {
            safeLog("FORMAT_REJECTED")
            return null
        }
        val normalizedExpected = when {
            expectedSha256 == null -> null
            else -> Sha256Contract.normalize(expectedSha256) ?: return null.also { safeLog("DIGEST_REJECTED") }
        }
        val actualDigest = validateAndDigest(file) ?: return null
        if (normalizedExpected != null && normalizedExpected != actualDigest) {
            safeLog("DIGEST_MISMATCH")
            return null
        }

        val record = LocalModelRecord(
            modelId = modelIdFor(file),
            displayName = displayName,
            absolutePath = file.canonicalPath,
            sizeBytes = file.length(),
            sha256 = actualDigest,
            quantization = quantization,
        )
        if (record.modelId.isBlank()) return null

        return mutate(allowDeliberateRecovery = true) { current ->
            val existing = current.firstOrNull { it.modelId == record.modelId }
            when {
                existing != null && existing.sha256 != record.sha256 -> {
                    safeLog(ModelRegistryErrorCode.DUPLICATE_MODEL_ID.name)
                    RegistryMutation(current, result = null, changed = false)
                }
                existing == record -> RegistryMutation(current, result = existing, changed = false)
                else -> {
                    val next = current.filterNot { it.modelId == record.modelId } + record
                    RegistryMutation(next, result = record, changed = true)
                }
            }
        }
    }

    private fun validateAndDigest(file: File): String? {
        return try {
            ModelFileContentValidator.validate(file)
            Sha256Contract.compute(file)
        } catch (_: ModelImportException) {
            safeLog("FORMAT_REJECTED")
            null
        } catch (_: IOException) {
            safeLog("DIGEST_IO_FAILURE")
            null
        }
    }

    private suspend fun <T> mutate(
        allowDeliberateRecovery: Boolean,
        block: (List<LocalModelRecord>) -> RegistryMutation<T>,
    ): T {
        requireMutationAuthority()
        return mutationMutex.withLock {
            val snapshot = manifest.snapshot()
            val current = when (snapshot.state) {
                ModelRegistryState.READY,
                ModelRegistryState.NO_REGISTRY_EXISTS,
                -> snapshot.records

                ModelRegistryState.CORRUPT_REGISTRY_QUARANTINED -> {
                    if (allowDeliberateRecovery && !snapshot.recoveryNewlyDetected) {
                        emptyList()
                    } else {
                        throw ModelRegistryException(ModelRegistryErrorCode.CORRUPT_REGISTRY_QUARANTINED)
                    }
                }
            }
            val mutation = block(current.toList())
            if (mutation.changed) manifest.writeAll(mutation.nextRecords)
            mutation.result
        }
    }

    private suspend fun loadReadableRecords(): List<LocalModelRecord> {
        return mutationMutex.withLock {
            val snapshot = manifest.snapshot()
            when (snapshot.state) {
                ModelRegistryState.READY,
                ModelRegistryState.NO_REGISTRY_EXISTS,
                -> snapshot.records

                ModelRegistryState.CORRUPT_REGISTRY_QUARANTINED ->
                    throw ModelRegistryException(ModelRegistryErrorCode.CORRUPT_REGISTRY_QUARANTINED)
            }
        }
    }

    private fun requireMutationAuthority() {
        if (!mutationEnabled) {
            throw ModelRegistryException(ModelRegistryErrorCode.NOT_MUTATION_OWNER)
        }
    }

    private fun isWithinModelsRoot(file: File): Boolean {
        val rootPath = modelsDir.toPath()
        return file.toPath().startsWith(rootPath) && file != modelsDir
    }

    private fun modelIdFor(file: File): String = file.nameWithoutExtension
        .lowercase()
        .replace(Regex("[^a-z0-9]"), "-")

    private fun safeLog(classification: String) {
        runCatching {
            Log.i(
                TAG,
                PrivacySafeDiagnostics.contentEvent(
                    operation = DiagnosticOperation.MODEL_RUNTIME,
                    status = DiagnosticStatus.UPDATED,
                    outputContent = classification,
                ),
            )
        }
    }

    private fun LocalModelRecord.toModelInfo(isLoaded: Boolean) = ModelInfo(
        modelId = modelId,
        displayName = displayName,
        sizeBytes = sizeBytes,
        quantization = quantization,
        isLoaded = isLoaded,
    )

    companion object {
        fun authoritativeWriter(context: Context): ModelRepository {
            if (!ModelRegistryProcessPolicy.isAuthoritative(context.packageName, Application.getProcessName())) {
                throw ModelRegistryException(ModelRegistryErrorCode.NOT_MUTATION_OWNER)
            }
            return ModelRepository(
                filesDir = context.filesDir,
                mutationEnabled = true,
                manifest = ModelManifest.from(context),
            )
        }

        internal fun forTesting(
            filesDir: File,
            faultInjector: ModelManifestFaultInjector = ModelManifestFaultInjector.None,
            mutationEnabled: Boolean = true,
        ): ModelRepository = ModelRepository(
            filesDir = filesDir,
            mutationEnabled = mutationEnabled,
            manifest = ModelManifest.forTesting(filesDir, faultInjector),
        )
    }
}

private data class RegistryMutation<T>(
    val nextRecords: List<LocalModelRecord>,
    val result: T,
    val changed: Boolean,
)

private object ModelRegistryMutexes {
    private val locks = ConcurrentHashMap<String, Mutex>()

    fun forPath(path: String): Mutex = locks.computeIfAbsent(path) { Mutex() }
}
