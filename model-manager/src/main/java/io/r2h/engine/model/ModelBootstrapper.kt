package io.r2h.engine.model

import android.content.Context
import android.util.Log
import io.r2h.engine.api.privacy.DiagnosticErrorCode
import io.r2h.engine.api.privacy.DiagnosticOperation
import io.r2h.engine.api.privacy.DiagnosticStatus
import io.r2h.engine.api.privacy.PrivacySafeDiagnostics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "ModelBootstrapper"
private const val MODELS_DIR = "models"

/**
 * Bootstraps the engine's local model directory on device storage.
 *
 * Responsibilities:
 *  - Ensure the app-private models directory exists.
 *  - Copy required seed model files into that directory if they are missing.
 *  - Register/discover models through [ModelRepository].
 *
 * This class does not load models into native memory and does not perform
 * inference. It only prepares on-device storage and repository visibility.
 *
 * Current bootstrap source:
 *  - app-private bootstrap directory: filesDir/bootstrap-models
 *
 * Expected files there:
 *  - gemma-4-E2B-it-Q5_K_S.gguf
 *  - mmproj-F16.gguf
 *
 * After copy/sync, the files are expected under:
 *  - filesDir/models/gemma-4-E2B-it-Q5_K_S.gguf
 *  - filesDir/models/mmproj-F16.gguf
 */
class ModelBootstrapper(
    context: Context,
    private val repository: ModelRepository,
) {
    private val appContext = context.applicationContext
    private val modelsDir = File(appContext.filesDir, MODELS_DIR)
    private val bootstrapDir = File(appContext.filesDir, "bootstrap-models")

    suspend fun bootstrap(): BootstrapResult = withContext(Dispatchers.IO) {
        modelsDir.mkdirs()
        bootstrapDir.mkdirs()

        val copied = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val missingSources = mutableListOf<String>()
        val errors = mutableListOf<String>()

        for (fileName in REQUIRED_MODEL_FILES) {
            val source = File(bootstrapDir, fileName)
            val target = File(modelsDir, fileName)

            if (target.exists()) {
                skipped += fileName
                logBootstrap(DiagnosticStatus.UPDATED)
                continue
            }

            if (!source.exists()) {
                missingSources += fileName
                logBootstrap(DiagnosticStatus.FAILED, fileName)
                continue
            }

            try {
                copyFile(source = source, target = target)
                copied += fileName
                logBootstrap(DiagnosticStatus.COMPLETED)
            } catch (t: Throwable) {
                errors += "$fileName: ${t.message ?: t.javaClass.simpleName}"
                logBootstrap(t)
            }
        }

        val discovered = try {
            repository.syncWithDisk()
        } catch (t: Throwable) {
            logBootstrap(t)
            errors += "syncWithDisk: ${t.message ?: t.javaClass.simpleName}"
            emptyList()
        }

        val registeredModels = try {
            repository.listModels()
        } catch (t: Throwable) {
            logBootstrap(t)
            errors += "listModels: ${t.message ?: t.javaClass.simpleName}"
            emptyList()
        }

        BootstrapResult(
            modelsDir = modelsDir.absolutePath,
            bootstrapDir = bootstrapDir.absolutePath,
            copiedFiles = copied,
            skippedFiles = skipped,
            missingSourceFiles = missingSources,
            discoveredModelIds = discovered.map { it.modelId },
            registeredModelIds = registeredModels.map { it.modelId },
            success = errors.isEmpty(),
            errors = errors,
        )
    }

    suspend fun listRegisteredModels() = repository.listModels()

    companion object {
        val REQUIRED_MODEL_FILES = listOf(
            "gemma-4-E2B-it-Q5_K_S.gguf",
            "mmproj-F16.gguf",
        )
    }
}

data class BootstrapResult(
    val modelsDir: String,
    val bootstrapDir: String,
    val copiedFiles: List<String>,
    val skippedFiles: List<String>,
    val missingSourceFiles: List<String>,
    val discoveredModelIds: List<String>,
    val registeredModelIds: List<String>,
    val success: Boolean,
    val errors: List<String>,
)

private fun copyFile(source: File, target: File) {
    val parent = target.parentFile
    if (parent != null && !parent.exists()) {
        parent.mkdirs()
    }

    source.inputStream().use { input ->
        target.outputStream().use { output ->
            input.copyTo(output, DEFAULT_BUFFER_SIZE)
        }
    }
}

private fun logBootstrap(status: DiagnosticStatus, detail: CharSequence? = null) {
    Log.i(
        TAG,
        PrivacySafeDiagnostics.contentEvent(
            operation = DiagnosticOperation.MODEL_RUNTIME,
            status = status,
            outputContent = detail,
            errorCode = if (status == DiagnosticStatus.FAILED) {
                DiagnosticErrorCode.MODEL_LOAD_FAILED
            } else {
                null
            },
        ),
    )
}

private fun logBootstrap(failure: Throwable) {
    Log.e(
        TAG,
        PrivacySafeDiagnostics.failureEvent(
            operation = DiagnosticOperation.MODEL_RUNTIME,
            errorCode = DiagnosticErrorCode.MODEL_LOAD_FAILED,
            failure = failure,
        ),
    )
}
