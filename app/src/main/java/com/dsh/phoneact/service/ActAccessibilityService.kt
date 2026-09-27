package com.dsh.phoneact.service

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

class ActAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        inst = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        com.dsh.phoneact.core.FrameHub.onAccessibilityEvent(event)
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (inst === this) inst = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var inst: ActAccessibilityService? = null
            private set

        val isConnected: Boolean get() = inst != null
    }
}
