package io.r2h.engine

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import io.r2h.engine.tools.ToolAuditStore
import java.io.File

/** Notification channel ID for the persistent engine foreground notification. */
const val NOTIFICATION_CHANNEL_ID = "r2h_engine_service"

/**
 * Application class. Runs in every process of the app (UI process and :engine process).
 *
 * Responsibilities here are minimal: register the notification channel so
 * [EngineService] can post its persistent notification on Android 8+.
 */
class EngineApplication : Application() {

    /**
     * Durable audit ownership is deliberately restricted to the default UI
     * process. EngineApplication also exists in :engine, so access there fails
     * closed instead of silently creating a second writer.
     */
    val toolAuditStore: ToolAuditStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ToolAuditStore.requireDefaultProcess(packageName, Application.getProcessName())
        ToolAuditStore(File(filesDir, "tool-audit/tool-audit.jsonl"))
    }

    override fun onCreate() {
        super.onCreate()
        registerNotificationChannel()
    }

    private fun registerNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }
}
