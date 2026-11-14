package dev.islandcn.island

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

class IslandAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        instance = this
        IslandOverlayService.onAccessibilityConnected(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        if (instance == this) instance = null
        IslandOverlayService.onAccessibilityDisconnected()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
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
