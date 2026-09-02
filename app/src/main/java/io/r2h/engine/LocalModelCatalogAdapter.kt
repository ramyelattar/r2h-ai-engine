package io.r2h.engine

import io.r2h.engine.api.model.EngineError
import io.r2h.engine.api.model.ErrorCode
import io.r2h.engine.api.model.ErrorStage
import io.r2h.engine.api.model.ModelValidationState
import io.r2h.engine.core.ModelCapability
import io.r2h.engine.core.ModelCatalogRecord
import io.r2h.engine.core.ModelDescriptor
import io.r2h.engine.core.ModelType
import io.r2h.engine.core.Modality
import io.r2h.engine.core.TaskType
import io.r2h.engine.model.ActiveLocalModel
import io.r2h.engine.model.LocalModelRecord
import io.r2h.engine.model.LocalModelRole
import java.io.File

/**
 * Converts the private model-manager manifest into the engine-core catalog truth.
 *
 * The repository manifest remains the authoritative persistence layer. This
 * adapter only maps validated records to engine descriptors; it does not copy,
 * register, delete, or mutate model files.
 */
internal class LocalModelCatalogAdapter(
    private val recordsSource: suspend () -> List<LocalModelRecord>,
    private val validatedPathSource: suspend (modelId: String) -> String?,
    private val activeModelSource: () -> ActiveLocalModel?,
) {
    suspend fun snapshot(): List<ModelCatalogRecord> {
        val activeModel = activeModelSource()

        return recordsSource().map { record ->
            val validatedPath = validatedPathSource(record.modelId)
            val descriptor = LocalModelDescriptorFactory.fromRecord(
                record = record,
                activeModel = activeModel,
                privatePath = validatedPath ?: record.absolutePath,
            )
            val installedFile = File(record.absolutePath)
            val validationError = if (validatedPath == null) {
                EngineError(
                    ErrorStage.MODEL_VALIDATION,
                    ErrorCode.MODEL_NOT_LOADABLE,
                    "Registered model '${record.modelId}' failed private-file validation.",
                )
            } else {
                null
            }

            ModelCatalogRecord(
                descriptor = descriptor,
                fileSizeBytes = record.sizeBytes,
                installed = installedFile.isFile && installedFile.length() > 0L,
                validationState = if (validatedPath != null) {
                    ModelValidationState.VALID
                } else {
                    ModelValidationState.INVALID
                },
                validationError = validationError,
            )
        }
    }

    suspend fun resolveDescriptor(modelId: String): ModelDescriptor? {
        val record = recordsSource().firstOrNull { it.modelId == modelId } ?: return null
        val validatedPath = validatedPathSource(modelId) ?: return null

        return LocalModelDescriptorFactory.fromRecord(
            record = record,
            activeModel = activeModelSource(),
            privatePath = validatedPath,
        )
    }
}

/**
 * Single descriptor contract used by startup auto-load, explicit warmup, catalog
 * truth, and AIDL capability discovery.
 */
internal object LocalModelDescriptorFactory {
    fun fromActive(
        active: ActiveLocalModel,
        privatePath: String,
    ): ModelDescriptor = build(
        modelId = active.modelId,
        displayName = active.displayName,
        extension = active.extension,
        privatePath = privatePath,
        sizeBytes = File(privatePath).length().takeIf { it > 0L } ?: active.sizeBytes,
        role = active.guessedRole,
        quantization = "UNKNOWN",
    )

    fun fromRecord(
        record: LocalModelRecord,
        activeModel: ActiveLocalModel?,
        privatePath: String,
    ): ModelDescriptor {
        val extension = File(privatePath).extension
            .ifBlank { File(record.absolutePath).extension }
        val role = activeModel
            ?.takeIf { it.modelId == record.modelId }
            ?.guessedRole
            ?: if (extension.equals("gguf", ignoreCase = true)) {
                LocalModelRole.TEXT
            } else {
                LocalModelRole.UNKNOWN
            }

        return build(
            modelId = record.modelId,
            displayName = record.displayName,
            extension = extension,
            privatePath = privatePath,
            sizeBytes = record.sizeBytes,
            role = role,
            quantization = record.quantization,
        )
    }

    private fun build(
        modelId: String,
        displayName: String,
        extension: String,
        privatePath: String,
        sizeBytes: Long,
        role: LocalModelRole,
        quantization: String,
    ): ModelDescriptor {
        val normalizedExtension = extension.trim().trimStart('.').lowercase()
        val isGguf = normalizedExtension == "gguf"
        val isVision = role == LocalModelRole.VISION

        val capabilities = when {
            isGguf -> setOf(
                ModelCapability.Input.Text,
                ModelCapability.Output.Text,
                ModelCapability.Execution.Local,
                ModelCapability.Lifecycle.ExplicitLoad,
                ModelCapability.Lifecycle.ExplicitUnload,
            )

            isVision -> setOf(
                ModelCapability.Input.Image,
                ModelCapability.Output.Text,
                ModelCapability.Execution.Local,
                ModelCapability.Lifecycle.ExplicitLoad,
                ModelCapability.Lifecycle.ExplicitUnload,
            )

            else -> setOf(
                ModelCapability.Execution.Local,
                ModelCapability.Lifecycle.ExplicitLoad,
            )
        }

        val supportedTaskTypes = when {
            isGguf -> setOf(
                TaskType.TEXT_GENERATION,
                TaskType.CHAT,
            )

            isVision -> setOf(TaskType.IMAGE_UNDERSTANDING)
            else -> emptySet()
        }

        return ModelDescriptor(
            id = modelId,
            displayName = displayName,
            modelType = when {
                isGguf -> ModelType.GGUF
                isVision -> ModelType.VISION
                else -> ModelType.GENERIC_TASK
            },
            capabilities = capabilities,
            backendKey = if (isGguf) "llama-cpp" else "onnx",
            modality = if (isVision) Modality.IMAGE else Modality.TEXT,
            source = ModelDescriptor.Source.Local(
                artifactRef = privatePath,
                format = normalizedExtension.ifBlank { "unknown" },
                sizeBytes = sizeBytes,
            ),
            executionTarget = ModelDescriptor.ExecutionTarget.LOCAL,
            supportedTaskTypes = supportedTaskTypes,
            metadata = mapOf("quantization" to quantization),
        )
    }
}