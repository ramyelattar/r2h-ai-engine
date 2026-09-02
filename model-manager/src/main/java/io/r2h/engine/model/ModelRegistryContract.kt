package io.r2h.engine.model

import java.io.IOException

enum class ModelRegistryState {
    READY,
    NO_REGISTRY_EXISTS,
    CORRUPT_REGISTRY_QUARANTINED,
}

data class ModelRegistrySnapshot(
    val state: ModelRegistryState,
    val records: List<LocalModelRecord>,
    internal val recoveryNewlyDetected: Boolean = false,
)

enum class ModelRegistryErrorCode {
    MANIFEST_READ_FAILED,
    INVALID_SCHEMA,
    QUARANTINE_FAILED,
    TEMP_WRITE_FAILED,
    SYNC_FAILED,
    REPLACE_FAILED,
    CORRUPT_REGISTRY_QUARANTINED,
    NOT_MUTATION_OWNER,
    DUPLICATE_MODEL_ID,
}

class ModelRegistryException(
    val code: ModelRegistryErrorCode,
    cause: Throwable? = null,
) : IOException(code.name, cause)

internal interface ModelManifestFaultInjector {
    fun beforeTempWrite() = Unit
    fun beforeSync() = Unit
    fun beforeReplace() = Unit
    fun beforeQuarantine() = Unit

    data object None : ModelManifestFaultInjector
}

internal object ModelRegistryProcessPolicy {
    fun isAuthoritative(packageName: String, processName: String?): Boolean =
        processName == "$packageName:engine"
}
