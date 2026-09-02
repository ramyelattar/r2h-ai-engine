package io.r2h.engine

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.r2h.engine.core.CallerValidatorPort
import io.r2h.engine.core.CallerValidationResult
import io.r2h.engine.core.CallerRateLimiterPort
import io.r2h.engine.core.CallerRateLimitResult
import io.r2h.engine.core.DefaultRuntimeRegistry
import io.r2h.engine.core.DefaultTaskRouter
import io.r2h.engine.core.EngineServiceImpl
import io.r2h.engine.core.InferenceEventSink
import io.r2h.engine.core.JniTextBackendRuntime
import io.r2h.engine.core.ModelDescriptor
import io.r2h.engine.core.ModelDescriptorPort
import io.r2h.engine.core.ModelRegistrationPort
import io.r2h.engine.core.ModelResolverPort
import io.r2h.engine.core.RegistrationPolicy
import io.r2h.engine.model.ActiveLocalModel
import io.r2h.engine.model.LocalModelSelectionStore
import io.r2h.engine.model.ModelRepository
import io.r2h.engine.api.privacy.DiagnosticErrorCode
import io.r2h.engine.api.privacy.DiagnosticOperation
import io.r2h.engine.api.privacy.PrivacySafeDiagnostics
import io.r2h.engine.security.CallerValidator
import io.r2h.engine.security.UidRateLimitDecision
import io.r2h.engine.security.UidRateLimiter
import io.r2h.engine.security.ValidationResult
import io.r2h.engine.telemetry.EngineLogger
import io.r2h.engine.telemetry.EngineLoggerAdapter
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

private const val TAG = "EngineService"
private const val NOTIFICATION_ID = 1001
private const val RATE_LIMIT_CAPACITY = 40.0
private const val RATE_LIMIT_REFILL_PER_SECOND = 1.0
private const val RATE_LIMIT_MAX_TRACKED_UIDS = 256
private const val RATE_LIMIT_STALE_AFTER_MS = 15 * 60 * 1_000L

class EngineService : LifecycleService() {

    private val serviceImpl: EngineServiceImpl by lazy { buildServiceImpl() }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "EngineService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        try {
            startForeground(NOTIFICATION_ID, buildNotification())
            Log.i(TAG, "EngineService started in foreground (startId=$startId)")
        } catch (t: Throwable) {
            Log.e(
                TAG,
                PrivacySafeDiagnostics.failureEvent(
                    DiagnosticOperation.ENGINE_SERVICE,
                    errorCode = DiagnosticErrorCode.RUNTIME_FAILURE,
                    failure = t,
                ),
            )
            stopSelf()
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        Log.i(TAG, "Client binding to EngineService")
        return serviceImpl
    }

    override fun onUnbind(intent: Intent?): Boolean {
        Log.i(TAG, "Last client unbound from EngineService")
        return false
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.w(TAG, "Task removed for UI process; engine process/service may continue")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        Log.i(TAG, "EngineService destroyed")
        super.onDestroy()
    }

    private fun buildServiceImpl(): EngineServiceImpl {
        val callerValidator = CallerValidator(applicationContext)
        val uidRateLimiter = UidRateLimiter(
            capacity = RATE_LIMIT_CAPACITY,
            refillTokensPerSecond = RATE_LIMIT_REFILL_PER_SECOND,
            maxTrackedUids = RATE_LIMIT_MAX_TRACKED_UIDS,
            staleAfterMs = RATE_LIMIT_STALE_AFTER_MS,
        )
        val modelRepository = ModelRepository.authoritativeWriter(applicationContext)
        val localModelSelectionStore = LocalModelSelectionStore.from(applicationContext)
        val localModelCatalogAdapter = LocalModelCatalogAdapter(
            recordsSource = { modelRepository.listRecords() },
            validatedPathSource = modelRepository::resolveValidatedPath,
            activeModelSource = { localModelSelectionStore.activeModel },
        )
        val logger = EngineLogger(applicationContext, lifecycleScope)
        val loggerAdapter = EngineLoggerAdapter(logger)
        val runtimeRegistry = DefaultRuntimeRegistry()
        runtimeRegistry.register(JniTextBackendRuntime(), RegistrationPolicy.REPLACE_ON_DUPLICATE)

        val callerValidatorPort = CallerValidatorPort { uid ->
            when (val result = callerValidator.validate(uid)) {
                is ValidationResult.Allowed -> {
                    CallerValidationResult.Allowed(result.packageName)
                }
                is ValidationResult.Denied -> CallerValidationResult.Denied(result.reason)
            }
        }

        val callerRateLimiterPort = CallerRateLimiterPort { uid, cost ->
            when (val decision = uidRateLimiter.tryAcquire(uid, cost.toDouble())) {
                UidRateLimitDecision.Allowed -> CallerRateLimitResult.Allowed
                is UidRateLimitDecision.Denied -> CallerRateLimitResult.Denied(decision.retryAfterMs)
            }
        }

        val modelResolverPort = ModelResolverPort { modelId ->
            runBlocking { modelRepository.resolveValidatedPath(modelId) }
        }

        val eventSink = object : InferenceEventSink {
            override fun onQueueFull(callerUid: Int) =
                loggerAdapter.onQueueFull(callerUid)

            override fun onSecurityRejection(callerUid: Int, reason: String) =
                loggerAdapter.onSecurityRejection(callerUid, reason)

            override fun onModelLoaded(modelId: String, durationMs: Long) =
                loggerAdapter.onModelLoaded(modelId, durationMs)

            override fun onModelLoadFailed(modelId: String, reason: String) =
                loggerAdapter.onModelLoadFailed(modelId, reason)

            override fun onInferenceCompleted(
                modelId: String,
                callerUid: Int,
                promptTokenCount: Int,
                generatedTokenCount: Int,
                firstTokenMs: Long,
                totalMs: Long,
                finishReason: String,
            ) = loggerAdapter.onInferenceCompleted(
                modelId,
                callerUid,
                promptTokenCount,
                generatedTokenCount,
                firstTokenMs,
                totalMs,
                finishReason,
            )
        }

        val impl = EngineServiceImpl(
            scope = lifecycleScope,
            runtimeRegistry = runtimeRegistry,
            taskRouter = DefaultTaskRouter(),
            callerRateLimiter = callerRateLimiterPort,
            callerValidator = callerValidatorPort,
            eventSink = eventSink,
            modelResolver = modelResolverPort,
            modelInfoSource = { modelRepository.listModels() },
            // P0 TEXT readiness: private model storage is the authoritative source
            // for both dashboard catalog truth and warmup descriptor resolution.
            modelCatalogSource = localModelCatalogAdapter::snapshot,
            modelDescriptorPort = ModelDescriptorPort { modelId ->
                runBlocking(Dispatchers.IO) {
                    localModelCatalogAdapter.resolveDescriptor(modelId)
                }
            },
            modelRegistrationPort = ModelRegistrationPort { file ->
                runBlocking(Dispatchers.IO) {
                    modelRepository.registerImportedFile(file, file.nameWithoutExtension)?.modelId
                        ?: error("Imported model is not a readable private model file.")
                }
            },
            inputRefMaterializer = ::materializeInputRef,
        )

        lifecycleScope.launch(Dispatchers.IO) {
            val active = localModelSelectionStore.activeModel
            val path = active?.let { modelRepository.resolveValidatedPath(it.modelId) }
            if (active != null && path != null) {
                impl.loadInstalledModel(buildImportedDescriptor(active, path))
            } else {
                impl.refreshModelCatalog()
            }
        }

        return impl
    }

    private fun buildImportedDescriptor(
        active: ActiveLocalModel,
        privatePath: String,
    ): ModelDescriptor = LocalModelDescriptorFactory.fromActive(active, privatePath)

    private fun materializeInputRef(ref: String, requestId: String, modality: String): String {
        if (!ref.startsWith("content://", ignoreCase = true)) return ref
        val extension = when (modality.uppercase()) {
            "IMAGE" -> ".png"
            "AUDIO" -> ".wav"
            "VIDEO" -> ".mp4"
            else -> ".bin"
        }
        val targetDir = File(cacheDir, "client-inputs/${requestId.replace(Regex("[^A-Za-z0-9._-]"), "_")}")
            .also { it.mkdirs() }
        val target = File(targetDir, "input-${System.currentTimeMillis()}$extension")
        val uri = Uri.parse(ref)
        contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        } ?: error("Cannot open input URI for $modality.")
        return target.absolutePath
    }

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text_idle))
            .setSmallIcon(R.drawable.ic_engine_status)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }
}
