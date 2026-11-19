package com.droidbridge.root

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * Minimal opt-in accessibility bridge declaration. Root automation remains the primary control
 * path; this service does not inspect or store window text.
 */
class DroidBridgeAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
}
