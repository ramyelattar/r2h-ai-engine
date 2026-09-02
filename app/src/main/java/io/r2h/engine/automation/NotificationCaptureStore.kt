package io.r2h.engine.automation

import io.r2h.engine.tools.ToolAuditEntry
import io.r2h.engine.tools.ToolRiskLevel

internal fun interface NotificationMonotonicClock {
    fun nowMs(): Long
}

internal fun interface NotificationExpiryCancellation {
    fun cancel()
}

internal fun interface NotificationExpiryScheduler {
    fun schedule(delayMs: Long, action: () -> Unit): NotificationExpiryCancellation
}

internal enum class NotificationCaptureField {
    PACKAGE_NAME,
    TITLE,
    TEXT,
}

internal data class CapturedNotification(
    val packageName: String,
    val title: String,
    val text: String,
)

internal data class NotificationCaptureScopeInfo(
    val scopeId: String,
    val startedAtElapsedMs: Long,
    val expiresAtElapsedMs: Long,
    val requestedFields: Set<NotificationCaptureField>,
    val maxRecords: Int,
    val active: Boolean,
)

/** Volatile, synchronized notification content owned by the default process. */
internal class NotificationCaptureStore(
    private val clock: NotificationMonotonicClock,
    private val expiryScheduler: NotificationExpiryScheduler,
) {
    private data class ActiveScope(
        val scopeId: String,
        val startedAtElapsedMs: Long,
        val expiresAtElapsedMs: Long,
        val requestedFields: Set<NotificationCaptureField>,
        val maxRecords: Int,
        val records: MutableList<CapturedNotification> = mutableListOf(),
        var expiryCancellation: NotificationExpiryCancellation? = null,
    )

    private var activeScope: ActiveScope? = null

    val activeScopeCount: Int
        @Synchronized get() {
            purgeExpiredLocked()
            return if (activeScope == null) 0 else 1
        }

    @Synchronized
    fun openScope(
        scopeId: String,
        requestedFields: Set<NotificationCaptureField>,
        maxRecords: Int,
        ttlMs: Long,
    ): Boolean {
        purgeExpiredLocked()
        if (
            activeScope != null ||
            scopeId.isBlank() ||
            scopeId.length > MAX_SCOPE_ID_CHARS ||
            requestedFields.isEmpty() ||
            maxRecords !in 1..MAX_RECORDS_PER_SCOPE ||
            ttlMs !in 1..DEFAULT_SCOPE_TTL_MS
        ) {
            return false
        }

        val startedAt = clock.nowMs()
        val scope = ActiveScope(
            scopeId = scopeId,
            startedAtElapsedMs = startedAt,
            expiresAtElapsedMs = startedAt + ttlMs,
            requestedFields = requestedFields.toSet(),
            maxRecords = maxRecords,
        )
        activeScope = scope
        return try {
            scope.expiryCancellation = expiryScheduler.schedule(ttlMs) {
                expireScope(scope.scopeId, scope.expiresAtElapsedMs)
            }
            true
        } catch (_: Throwable) {
            clearActiveLocked(cancelExpiry = true)
            false
        }
    }

    /**
     * Providers are evaluated under the state lock only when a live scope has
     * requested the corresponding field. With no scope, content is untouched.
     */
    @Synchronized
    fun capture(
        packageName: String,
        title: () -> CharSequence?,
        text: () -> CharSequence?,
    ): Int {
        purgeExpiredLocked()
        val scope = activeScope ?: return 0
        val fields = scope.requestedFields
        val item = CapturedNotification(
            packageName = if (NotificationCaptureField.PACKAGE_NAME in fields) {
                packageName.take(MAX_PACKAGE_CHARS)
            } else {
                ""
            },
            title = if (NotificationCaptureField.TITLE in fields) {
                title()?.toString().orEmpty().take(MAX_TITLE_CHARS)
            } else {
                ""
            },
            text = if (NotificationCaptureField.TEXT in fields) {
                text()?.toString().orEmpty().take(MAX_TEXT_CHARS)
            } else {
                ""
            },
        )
        scope.records.add(0, item)
        while (scope.records.size > scope.maxRecords) scope.records.removeAt(scope.records.lastIndex)
        return 1
    }

    @Synchronized
    fun snapshot(scopeId: String): List<CapturedNotification> {
        purgeExpiredLocked()
        return activeScope?.takeIf { it.scopeId == scopeId }?.records?.toList().orEmpty()
    }

    @Synchronized
    fun consumeAndClose(scopeId: String): List<CapturedNotification> {
        purgeExpiredLocked()
        val scope = activeScope?.takeIf { it.scopeId == scopeId } ?: return emptyList()
        val snapshot = scope.records.toList()
        clearActiveLocked(cancelExpiry = true)
        return snapshot
    }

    @Synchronized
    fun scopeInfo(scopeId: String): NotificationCaptureScopeInfo? {
        purgeExpiredLocked()
        val scope = activeScope?.takeIf { it.scopeId == scopeId } ?: return null
        return NotificationCaptureScopeInfo(
            scopeId = scope.scopeId,
            startedAtElapsedMs = scope.startedAtElapsedMs,
            expiresAtElapsedMs = scope.expiresAtElapsedMs,
            requestedFields = scope.requestedFields,
            maxRecords = scope.maxRecords,
            active = true,
        )
    }

    @Synchronized
    fun closeScope(scopeId: String): Boolean {
        purgeExpiredLocked()
        if (activeScope?.scopeId != scopeId) return false
        clearActiveLocked(cancelExpiry = true)
        return true
    }

    @Synchronized
    fun clearAll() {
        clearActiveLocked(cancelExpiry = true)
    }

    @Synchronized
    private fun expireScope(scopeId: String, expectedExpiryMs: Long) {
        val scope = activeScope ?: return
        if (
            scope.scopeId == scopeId &&
            scope.expiresAtElapsedMs == expectedExpiryMs &&
            clock.nowMs() >= expectedExpiryMs
        ) {
            clearActiveLocked(cancelExpiry = false)
        }
    }

    private fun purgeExpiredLocked() {
        val scope = activeScope ?: return
        if (clock.nowMs() >= scope.expiresAtElapsedMs) clearActiveLocked(cancelExpiry = true)
    }

    private fun clearActiveLocked(cancelExpiry: Boolean) {
        val scope = activeScope ?: return
        activeScope = null
        scope.records.clear()
        if (cancelExpiry) scope.expiryCancellation?.cancel()
        scope.expiryCancellation = null
    }

    companion object {
        const val MAX_ACTIVE_SCOPES = 1
        const val MAX_RECORDS_PER_SCOPE = 8
        const val MAX_PACKAGE_CHARS = 256
        const val MAX_TITLE_CHARS = 80
        const val MAX_TEXT_CHARS = 160
        const val DEFAULT_SCOPE_TTL_MS = 60_000L
        private const val MAX_SCOPE_ID_CHARS = 128
    }
}

internal data class NotificationSummary(
    val displayText: String,
    val capturedRecordCount: Int,
) {
    fun toAuditEntry(timestampMs: Long): ToolAuditEntry = ToolAuditEntry(
        timestampMs = timestampMs,
        toolId = "notification_summary",
        actionSummary = "Notification summary requested during an explicit capture scope.",
        result = "Summarized $capturedRecordCount notification(s) captured during the active scope.",
        riskLevel = ToolRiskLevel.HIGH,
        approvedByUser = true,
        rawDetails = "capturedRecords=$capturedRecordCount; contentPersisted=false",
    )
}

internal object NotificationSummaryFormatter {
    fun format(notifications: List<CapturedNotification>): NotificationSummary {
        val display = if (notifications.isEmpty()) {
            "No notifications were captured during the active request window."
        } else {
            notifications.joinToString(separator = "\n") { item ->
                "- ${item.packageName}: ${item.title.ifBlank { "(no title)" }} ${item.text}".trim()
            }
        }
        return NotificationSummary(display, notifications.size)
    }
}
