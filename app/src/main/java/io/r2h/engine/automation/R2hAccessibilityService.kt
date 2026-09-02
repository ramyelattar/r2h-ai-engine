package io.r2h.engine.automation

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class R2hAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        lastEventSummary = event?.eventType?.toString().orEmpty()
    }

    override fun onInterrupt() {
        lastEventSummary = "Interrupted"
    }

    companion object {
        @Volatile
        var lastEventSummary: String = ""
            private set
    }
}
