package dev.islandcn.island

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

class IslandAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        android.util.Log.i("IslandOverlay", "Accessibility service connected")
        instance = this
        try {
            val info = serviceInfo ?: throw IllegalStateException("serviceInfo null")
            info.flags = info.flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            info.eventTypes = info.eventTypes or
                AccessibilityEvent.TYPE_WINDOWS_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            serviceInfo = info
        } catch (error: Exception) {
            android.util.Log.w("IslandOverlay", "Accessibility info update failed", error)
        }
        IslandStatusStack.attach(this)
        IslandOverlayService.onAccessibilityConnected(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        IslandStatusStack.onAccessibilityEvent(event)
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        IslandStatusStack.detach(this)
        if (instance == this) instance = null
        IslandOverlayService.onAccessibilityDisconnected()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        IslandStatusStack.detach(this)
        if (instance == this) instance = null
        IslandOverlayService.onAccessibilityDisconnected()
        super.onDestroy()
    }

    companion object {
        @Volatile var instance: IslandAccessibilityService? = null

        fun enabled(context: Context): Boolean {
            val master = Settings.Secure.getInt(context.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            if (master != 1) return false
            val setting = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val expected = ComponentName(context, IslandAccessibilityService::class.java).flattenToString()
            return setting.split(':').any { it.equals(expected, ignoreCase = true) }
        }
    }
}
