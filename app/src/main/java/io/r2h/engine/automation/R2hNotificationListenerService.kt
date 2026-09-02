package io.r2h.engine.automation

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class R2hNotificationListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        captureStore.capture(
            packageName = sbn.packageName,
            title = { sbn.notification.extras?.getCharSequence(Notification.EXTRA_TITLE) },
            text = { sbn.notification.extras?.getCharSequence(Notification.EXTRA_TEXT) },
        )
    }

    override fun onListenerDisconnected() {
        captureStore.clearAll()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        captureStore.clearAll()
        super.onDestroy()
    }

    companion object {
        private val mainHandler by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            Handler(Looper.getMainLooper())
        }
        private val captureStore by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            NotificationCaptureStore(
                clock = NotificationMonotonicClock(SystemClock::elapsedRealtime),
                expiryScheduler = NotificationExpiryScheduler { delayMs, action ->
                    val runnable = Runnable(action)
                    check(mainHandler.postDelayed(runnable, delayMs)) {
                        "Notification capture expiry could not be scheduled"
                    }
                    NotificationExpiryCancellation { mainHandler.removeCallbacks(runnable) }
                },
            )
        }

        internal fun openSummaryCapture(scopeId: String): Boolean = captureStore.openScope(
            scopeId = scopeId,
            requestedFields = NotificationCaptureField.entries.toSet(),
            maxRecords = NotificationCaptureStore.MAX_RECORDS_PER_SCOPE,
            ttlMs = NotificationCaptureStore.DEFAULT_SCOPE_TTL_MS,
        )

        internal fun consumeSummaryCapture(scopeId: String): List<CapturedNotification> =
            captureStore.consumeAndClose(scopeId)

        internal fun closeSummaryCapture(scopeId: String): Boolean =
            captureStore.closeScope(scopeId)
    }
}
