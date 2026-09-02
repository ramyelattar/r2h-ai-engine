package io.r2h.engine.model

import android.content.Context
import android.util.Log
import io.r2h.engine.api.privacy.DiagnosticErrorCode
import io.r2h.engine.api.privacy.DiagnosticOperation
import io.r2h.engine.api.privacy.DiagnosticStatus
import io.r2h.engine.api.privacy.PrivacySafeDiagnostics
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

private const val TAG = "ModelManifest"
private const val MANIFEST_FILE = "model_manifest.json"
private const val QUARANTINE_PREFIX = "model_manifest.corrupt."
private const val QUARANTINE_SUFFIX = ".json"

/** Authoritative, bounded and failure-safe persistence for local model records. */
internal class ModelManifest private constructor(
    filesDir: File,
    private val faultInjector: ModelManifestFaultInjector,
    private val uniqueToken: () -> String,
) {
    private val manifestFile = File(filesDir, MANIFEST_FILE)
    private val parentDir = manifestFile.parentFile ?: filesDir

    val pathKey: String = manifestFile.canonicalPath

    fun snapshot(): ModelRegistrySnapshot {
        if (!manifestFile.exists()) {
            return if (hasQuarantineEvidence()) {
                ModelRegistrySnapshot(
                    state = ModelRegistryState.CORRUPT_REGISTRY_QUARANTINED,
                    records = emptyList(),
                    recoveryNewlyDetected = false,
                )
            } else {
                ModelRegistrySnapshot(ModelRegistryState.NO_REGISTRY_EXISTS, emptyList())
            }
        }

        return try {
            val records = parseRecords(readBoundedBytes().toString(Charsets.UTF_8))
            ModelRegistrySnapshot(ModelRegistryState.READY, records)
        } catch (failure: RegistrySchemaException) {
            quarantineCorruptManifest(failure)
        } catch (failure: IOException) {
            safeLog(ModelRegistryErrorCode.MANIFEST_READ_FAILED)
            throw ModelRegistryException(ModelRegistryErrorCode.MANIFEST_READ_FAILED, failure)
        }
    }

    /**
     * Persists a complete validated state. Callers serialize logical read-modify-write
     * operations; this method guarantees durable temp write followed by atomic replace.
     */
    fun writeAll(records: List<LocalModelRecord>) {
        val normalized = validateRecords(records)
        val bytes = serialize(normalized).toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_MANIFEST_BYTES) {
            throw ModelRegistryException(ModelRegistryErrorCode.INVALID_SCHEMA)
        }

        parentDir.mkdirs()
        val temporary = File(parentDir, "$MANIFEST_FILE.tmp.${UUID.randomUUID()}")
        var phase = WritePhase.TEMP_WRITE
        try {
            faultInjector.beforeTempWrite()
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.flush()
                phase = WritePhase.SYNC
                faultInjector.beforeSync()
                output.fd.sync()
            }

            phase = WritePhase.REPLACE
            faultInjector.beforeReplace()
            Files.move(
                temporary.toPath(),
                manifestFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (failure: Exception) {
            val code = when (phase) {
                WritePhase.TEMP_WRITE -> ModelRegistryErrorCode.TEMP_WRITE_FAILED
                WritePhase.SYNC -> ModelRegistryErrorCode.SYNC_FAILED
                WritePhase.REPLACE -> ModelRegistryErrorCode.REPLACE_FAILED
            }
            safeLog(code)
            throw ModelRegistryException(code, failure)
        } finally {
            if (temporary.exists()) temporary.delete()
        }
    }

    private fun quarantineCorruptManifest(cause: Throwable): ModelRegistrySnapshot {
        val target = nextQuarantineFile()
        try {
            faultInjector.beforeQuarantine()
            Files.move(manifestFile.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } catch (failure: Exception) {
            safeLog(ModelRegistryErrorCode.QUARANTINE_FAILED)
            throw ModelRegistryException(ModelRegistryErrorCode.QUARANTINE_FAILED, failure)
        }
        safeLog(ModelRegistryErrorCode.CORRUPT_REGISTRY_QUARANTINED)
        return ModelRegistrySnapshot(
            state = ModelRegistryState.CORRUPT_REGISTRY_QUARANTINED,
            records = emptyList(),
            recoveryNewlyDetected = true,
        )
    }

    private fun readBoundedBytes(): ByteArray {
        if (manifestFile.length() > MAX_MANIFEST_BYTES) {
            throw RegistrySchemaException("manifest exceeds byte limit")
        }
        val output = ByteArrayOutputStream(minOf(manifestFile.length().toInt().coerceAtLeast(32), MAX_MANIFEST_BYTES))
        val buffer = ByteArray(16 * 1024)
        FileInputStream(manifestFile).use { input ->
            var total = 0
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_MANIFEST_BYTES) {
                    throw RegistrySchemaException("manifest exceeds byte limit")
                }
                output.write(buffer, 0, read)
            }
        }
        return output.toByteArray()
    }

    private fun parseRecords(json: String): List<LocalModelRecord> {
        try {
            val tokener = JSONTokener(json)
            val root = tokener.nextValue()
            if (root !is JSONArray || tokener.nextClean() != 0.toChar()) {
                throw RegistrySchemaException("top level must be one JSON array")
            }
            if (root.length() > MAX_RECORDS) {
                throw RegistrySchemaException("record limit exceeded")
            }

            val records = ArrayList<LocalModelRecord>(root.length())
            val ids = HashSet<String>(root.length())
            for (index in 0 until root.length()) {
                val value = root.get(index)
                if (value !is JSONObject) throw RegistrySchemaException("record must be an object")
                val record = parseRecord(value)
                if (!ids.add(record.modelId)) throw RegistrySchemaException("duplicate model ID")
                records += record
            }
            return records
        } catch (failure: RegistrySchemaException) {
            throw failure
        } catch (failure: Exception) {
            throw RegistrySchemaException("invalid registry JSON", failure)
        }
    }

    private fun parseRecord(json: JSONObject): LocalModelRecord {
        val keys = buildSet {
            val iterator = json.keys()
            while (iterator.hasNext()) add(iterator.next())
        }
        if (keys != RECORD_FIELDS) throw RegistrySchemaException("record fields do not match schema")

        val sizeValue = json.get("sizeBytes")
        val size = when (sizeValue) {
            is Byte -> sizeValue.toLong()
            is Short -> sizeValue.toLong()
            is Int -> sizeValue.toLong()
            is Long -> sizeValue
            else -> throw RegistrySchemaException("sizeBytes must be an integer")
        }
        return validateRecord(
            LocalModelRecord(
                modelId = requiredString(json, "modelId"),
                displayName = requiredString(json, "displayName"),
                absolutePath = requiredString(json, "absolutePath"),
                sizeBytes = size,
                sha256 = requiredString(json, "sha256"),
                quantization = requiredString(json, "quantization"),
            )
        )
    }

    private fun requiredString(json: JSONObject, key: String): String {
        val value = json.get(key)
        return value as? String ?: throw RegistrySchemaException("$key must be a string")
    }

    private fun validateRecords(records: List<LocalModelRecord>): List<LocalModelRecord> {
        if (records.size > MAX_RECORDS) throw ModelRegistryException(ModelRegistryErrorCode.INVALID_SCHEMA)
        val ids = HashSet<String>(records.size)
        return records.map { record ->
            val normalized = try {
                validateRecord(record)
            } catch (failure: RegistrySchemaException) {
                throw ModelRegistryException(ModelRegistryErrorCode.INVALID_SCHEMA, failure)
            }
            if (!ids.add(normalized.modelId)) {
                throw ModelRegistryException(ModelRegistryErrorCode.INVALID_SCHEMA)
            }
            normalized
        }
    }

    private fun validateRecord(record: LocalModelRecord): LocalModelRecord {
        if (!MODEL_ID.matches(record.modelId) || record.modelId.length > MAX_MODEL_ID_CHARS) {
            throw RegistrySchemaException("invalid modelId")
        }
        validateBoundedText(record.displayName, MAX_DISPLAY_NAME_CHARS, "displayName")
        validateBoundedText(record.absolutePath, MAX_PATH_CHARS, "absolutePath")
        validateBoundedText(record.quantization, MAX_QUANTIZATION_CHARS, "quantization")
        if (record.sizeBytes <= 0L) throw RegistrySchemaException("sizeBytes must be positive")
        val digest = Sha256Contract.normalize(record.sha256)
            ?: throw RegistrySchemaException("invalid sha256")
        return record.copy(sha256 = digest)
    }

    private fun validateBoundedText(value: String, maximum: Int, field: String) {
        if (value.isBlank() || value.length > maximum || '\u0000' in value) {
            throw RegistrySchemaException("invalid $field")
        }
    }

    private fun serialize(records: List<LocalModelRecord>): String {
        val array = JSONArray()
        records.forEach { record ->
            array.put(
                JSONObject()
                    .put("modelId", record.modelId)
                    .put("displayName", record.displayName)
                    .put("absolutePath", record.absolutePath)
                    .put("sizeBytes", record.sizeBytes)
                    .put("sha256", record.sha256)
                    .put("quantization", record.quantization)
            )
        }
        return array.toString(2)
    }

    private fun nextQuarantineFile(): File {
        val token = uniqueToken()
            .replace(Regex("[^A-Za-z0-9._-]"), "-")
            .take(96)
            .ifBlank { "recovery" }
        var collision = 0
        while (true) {
            val suffix = if (collision == 0) "" else ".$collision"
            val candidate = File(parentDir, "$QUARANTINE_PREFIX$token$suffix$QUARANTINE_SUFFIX")
            if (!candidate.exists()) return candidate
            collision++
        }
    }

    private fun hasQuarantineEvidence(): Boolean = parentDir.listFiles().orEmpty().any { file ->
        file.isFile && file.name.startsWith(QUARANTINE_PREFIX) && file.name.endsWith(QUARANTINE_SUFFIX)
    }

    private fun safeLog(code: ModelRegistryErrorCode) {
        runCatching {
            Log.e(
                TAG,
                PrivacySafeDiagnostics.contentEvent(
                    operation = DiagnosticOperation.MODEL_VALIDATION,
                    status = DiagnosticStatus.FAILED,
                    outputContent = code.name,
                    errorCode = DiagnosticErrorCode.MODEL_LOAD_FAILED,
                ),
            )
        }
    }

    private enum class WritePhase { TEMP_WRITE, SYNC, REPLACE }

    companion object {
        const val MAX_MANIFEST_BYTES = 4 * 1024 * 1024
        const val MAX_RECORDS = 4_096
        private const val MAX_MODEL_ID_CHARS = 256
        private const val MAX_DISPLAY_NAME_CHARS = 512
        private const val MAX_PATH_CHARS = 4_096
        private const val MAX_QUANTIZATION_CHARS = 128
        private val MODEL_ID = Regex("^[a-z0-9][a-z0-9-]*$")
        private val RECORD_FIELDS = setOf(
            "modelId",
            "displayName",
            "absolutePath",
            "sizeBytes",
            "sha256",
            "quantization",
        )

        fun from(context: Context): ModelManifest = ModelManifest(
            filesDir = context.filesDir,
            faultInjector = ModelManifestFaultInjector.None,
            uniqueToken = { "${System.currentTimeMillis()}.${UUID.randomUUID()}" },
        )

        fun forTesting(
            filesDir: File,
            faultInjector: ModelManifestFaultInjector = ModelManifestFaultInjector.None,
            uniqueToken: () -> String = { UUID.randomUUID().toString() },
        ): ModelManifest = ModelManifest(filesDir, faultInjector, uniqueToken)
    }
}

private class RegistrySchemaException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)
