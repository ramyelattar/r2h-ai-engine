package io.r2h.engine.permissions

import android.Manifest
import android.app.AlarmManager
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import io.r2h.engine.automation.R2hAccessibilityService
import io.r2h.engine.automation.R2hNotificationListenerService

enum class AgentPermissionStatus(val displayName: String) {
    GRANTED("Granted"),
    NOT_REQUIRED("Not required"),
    NOT_GRANTED("Not granted"),
    REQUIRES_SETTINGS("Requires settings"),
    UNAVAILABLE("Unavailable on this device"),
}

enum class AgentPermissionRisk(val displayName: String) {
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High"),
}

enum class AgentPermissionRequestKind {
    RUNTIME,
    SETTINGS,
    NONE,
}

data class AgentPermissionItem(
    val id: String,
    val name: String,
    val status: AgentPermissionStatus,
    val enables: String,
    val risk: AgentPermissionRisk,
    val confirmationRequired: Boolean,
    val requestKind: AgentPermissionRequestKind,
    val runtimePermission: String? = null,
    val runtimePermissions: List<String> = emptyList(),
    val settingsIntent: Intent? = null,
    val group: String = "Runtime permission",
    val toolDependency: String = "Local tool",
)

object AgentPermissionCenter {
    fun items(context: Context): List<AgentPermissionItem> = listOf(
        runtime(
            context = context,
            id = "camera",
            name = "Camera",
            permission = Manifest.permission.CAMERA,
            enables = "Camera capture, image analysis input, and flashlight control.",
            risk = AgentPermissionRisk.MEDIUM,
            confirmationRequired = true,
            toolDependency = "Camera Capture, Torch",
        ),
        runtime(
            context = context,
            id = "microphone",
            name = "Microphone",
            permission = Manifest.permission.RECORD_AUDIO,
            enables = "Short local audio notes, STT, and audio understanding input.",
            risk = AgentPermissionRisk.MEDIUM,
            confirmationRequired = true,
            toolDependency = "Microphone Record scaffold, STT input",
        ),
        picker(
            id = "media_images",
            name = "Photos / Images",
            enables = "User-selected images through Android scoped file pickers. Broad photo library permission is not required.",
            toolDependency = "Studio image, OCR, multimodal prompt",
        ),
        picker(
            id = "media_video",
            name = "Videos",
            enables = "User-selected videos through Android scoped file pickers. Broad video library permission is not required.",
            toolDependency = "Studio video analysis",
        ),
        picker(
            id = "media_audio",
            name = "Audio files",
            enables = "User-selected audio through Android scoped file pickers. Broad audio library permission is not required.",
            toolDependency = "Studio audio analysis",
        ),
        picker(
            id = "file_picker",
            name = "Documents",
            enables = "User-selected documents through Android scoped file pickers.",
            toolDependency = "OCR documents, model import",
        ),
        notificationRuntime(context),
        runtime(
            context = context,
            id = "calendar",
            name = "Calendar",
            permission = Manifest.permission.READ_CALENDAR,
            enables = "Read approved event context and create user-confirmed events.",
            risk = AgentPermissionRisk.HIGH,
            confirmationRequired = true,
            secondaryPermission = Manifest.permission.WRITE_CALENDAR,
            toolDependency = "Calendar",
        ),
        runtime(
            context = context,
            id = "contacts",
            name = "Contacts",
            permission = Manifest.permission.READ_CONTACTS,
            enables = "Search selected contacts without dumping the full address book.",
            risk = AgentPermissionRisk.HIGH,
            confirmationRequired = true,
            toolDependency = "Contacts Lookup",
        ),
        runtime(
            context = context,
            id = "location",
            name = "Location",
            permission = Manifest.permission.ACCESS_FINE_LOCATION,
            enables = "Local location-aware task context when explicitly approved.",
            risk = AgentPermissionRisk.HIGH,
            confirmationRequired = true,
            secondaryPermission = Manifest.permission.ACCESS_COARSE_LOCATION,
            toolDependency = "Location-aware local task context",
        ),
        bluetooth(context),
        special(
            id = "notification_listener",
            name = "Notification Listener",
            granted = isNotificationListenerEnabled(context),
            enables = "Summarize recent notifications locally with user control.",
            risk = AgentPermissionRisk.HIGH,
            confirmationRequired = true,
            intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
            toolDependency = "Notification Summary",
        ),
        special(
            id = "usage_access",
            name = "Usage Access",
            granted = hasUsageAccess(context),
            enables = "Read-only local app usage summaries.",
            risk = AgentPermissionRisk.MEDIUM,
            confirmationRequired = false,
            intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
            toolDependency = "Usage Insight",
        ),
        special(
            id = "accessibility",
            name = "Accessibility Automation",
            granted = isAccessibilityEnabled(context),
            enables = "Disabled-by-default approved action scaffold. It never runs hidden actions.",
            risk = AgentPermissionRisk.HIGH,
            confirmationRequired = true,
            intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            toolDependency = "Accessibility Action scaffold",
        ),
        special(
            id = "battery_optimization",
            name = "Battery unrestricted",
            granted = isIgnoringBatteryOptimizations(context),
            enables = "Long-running local tasks only when the user explicitly allows it.",
            risk = AgentPermissionRisk.MEDIUM,
            confirmationRequired = true,
            intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            toolDependency = "Long local tasks",
        ),
        special(
            id = "unused_app_restrictions",
            name = "Manage app if unused",
            granted = false,
            enables = "Recommended OFF for continuous local agent availability. Open App Info to disable unused-app restrictions when available.",
            risk = AgentPermissionRisk.LOW,
            confirmationRequired = false,
            intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
            toolDependency = "Continuous local agent availability",
        ),
        special(
            id = "overlay",
            name = "Display over other apps",
            granted = Settings.canDrawOverlays(context),
            enables = "Not implemented. No hidden overlay or floating approval surface is active.",
            risk = AgentPermissionRisk.HIGH,
            confirmationRequired = true,
            intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")),
            available = false,
            toolDependency = "No overlay tool implemented",
        ),
        exactAlarms(context),
        special(
            id = "all_files_access",
            name = "All files access",
            granted = false,
            enables = "Not required. Studio uses scoped pickers instead of broad file access.",
            risk = AgentPermissionRisk.HIGH,
            confirmationRequired = true,
            intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
            } else {
                Intent(Settings.ACTION_SETTINGS)
            },
            available = false,
            toolDependency = "No all-files tool implemented",
        ),
    )

    private fun runtime(
        context: Context,
        id: String,
        name: String,
        permission: String,
        enables: String,
        risk: AgentPermissionRisk,
        confirmationRequired: Boolean,
        secondaryPermission: String? = null,
        toolDependency: String,
    ): AgentPermissionItem {
        val permissions = listOfNotNull(permission, secondaryPermission)
        val granted = permissions.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        return AgentPermissionItem(
            id = id,
            name = name,
            status = if (granted) AgentPermissionStatus.GRANTED else AgentPermissionStatus.NOT_GRANTED,
            enables = enables,
            risk = risk,
            confirmationRequired = confirmationRequired,
            requestKind = AgentPermissionRequestKind.RUNTIME,
            runtimePermission = permission,
            runtimePermissions = permissions,
            toolDependency = toolDependency,
        )
    }

    private fun picker(id: String, name: String, enables: String, toolDependency: String): AgentPermissionItem =
        AgentPermissionItem(
            id = id,
            name = name,
            status = AgentPermissionStatus.NOT_REQUIRED,
            enables = enables,
            risk = AgentPermissionRisk.LOW,
            confirmationRequired = false,
            requestKind = AgentPermissionRequestKind.NONE,
            group = "Scoped picker",
            toolDependency = toolDependency,
        )

    private fun notificationRuntime(context: Context): AgentPermissionItem =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runtime(
                context = context,
                id = "notifications",
                name = "Notifications",
                permission = Manifest.permission.POST_NOTIFICATIONS,
                enables = "Engine foreground status notification and local action visibility.",
                risk = AgentPermissionRisk.LOW,
                confirmationRequired = false,
                toolDependency = "Engine foreground status notification",
            )
        } else {
            AgentPermissionItem(
                id = "notifications",
                name = "Notifications",
                status = AgentPermissionStatus.GRANTED,
                enables = "Notifications are granted by install state on this Android version.",
                risk = AgentPermissionRisk.LOW,
                confirmationRequired = false,
                requestKind = AgentPermissionRequestKind.NONE,
                toolDependency = "Engine foreground status notification",
            )
        }

    private fun bluetooth(context: Context): AgentPermissionItem =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runtime(
                context = context,
                id = "bluetooth",
                name = "Nearby / Bluetooth",
                permission = Manifest.permission.BLUETOOTH_SCAN,
                enables = "Local nearby-device discovery only if a local workflow uses it.",
                risk = AgentPermissionRisk.MEDIUM,
                confirmationRequired = true,
                secondaryPermission = Manifest.permission.BLUETOOTH_CONNECT,
                toolDependency = "Nearby/Bluetooth local workflows",
            )
        } else {
            AgentPermissionItem(
                id = "bluetooth",
                name = "Nearby / Bluetooth",
                status = AgentPermissionStatus.UNAVAILABLE,
                enables = "The modern Nearby Devices runtime permission is unavailable on this Android version.",
                risk = AgentPermissionRisk.MEDIUM,
                confirmationRequired = true,
                requestKind = AgentPermissionRequestKind.NONE,
                toolDependency = "Nearby/Bluetooth local workflows",
            )
        }

    private fun special(
        id: String,
        name: String,
        granted: Boolean,
        enables: String,
        risk: AgentPermissionRisk,
        confirmationRequired: Boolean,
        intent: Intent,
        available: Boolean = true,
        toolDependency: String,
    ): AgentPermissionItem =
        AgentPermissionItem(
            id = id,
            name = name,
            status = when {
                !available -> AgentPermissionStatus.UNAVAILABLE
                granted -> AgentPermissionStatus.GRANTED
                else -> AgentPermissionStatus.REQUIRES_SETTINGS
            },
            enables = enables,
            risk = risk,
            confirmationRequired = confirmationRequired,
            requestKind = if (available && !granted) AgentPermissionRequestKind.SETTINGS else AgentPermissionRequestKind.NONE,
            settingsIntent = intent,
            group = "Special access",
            toolDependency = toolDependency,
        )

    private fun exactAlarms(context: Context): AgentPermissionItem {
        val available = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        val granted = if (available) {
            context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() == true
        } else {
            true
        }
        return special(
            id = "exact_alarms",
            name = "Exact alarms",
            granted = granted,
            enables = "Not implemented. Exact local reminders are not active in this pass.",
            risk = AgentPermissionRisk.MEDIUM,
            confirmationRequired = true,
            intent = if (available) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) else Intent(),
            available = false,
            toolDependency = "No exact-alarm reminder tool implemented",
        )
    }

    fun isNotificationListenerEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        return enabled.contains(R2hNotificationListenerService::class.java.name, ignoreCase = true)
    }

    fun isAccessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return enabled.contains(R2hAccessibilityService::class.java.name, ignoreCase = true)
    }

    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java) ?: return false
        val mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
        val power = context.getSystemService(PowerManager::class.java) ?: return false
        return power.isIgnoringBatteryOptimizations(context.packageName)
    }
}
