package dev.islandcn.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.accessibilityservice.AccessibilityService
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Hosts the island as native views. There is no Flutter view here: a Flutter
 * surface in this window never received vsync, and a focusable one swallowed
 * the back gesture. FLAG_NOT_FOCUSABLE keeps key events, including back, on
 * the app underneath. The window is only as large as the pill, and
 * FLAG_NOT_TOUCH_MODAL lets every touch outside that rect through.
 */
class IslandOverlayService : Service() {
    private var pill: IslandPillView? = null
    private var root: IslandPillView? = null
    private var window: WindowManager? = null
    private var windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    private var hostContext: Context = this
    private var shiftY = 0
    private var calibrated = false
    private var appliedX = Int.MIN_VALUE
    private var appliedY = Int.MIN_VALUE
    private var appliedW = Int.MIN_VALUE
    private var appliedH = Int.MIN_VALUE

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        refreshForeground()
        val accessibility = IslandAccessibilityService.instance
        val overlay = Settings.canDrawOverlays(this)
        if (accessibility == null && !overlay) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (accessibility != null) present(accessibility, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
            else present(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        } catch (error: Throwable) {
            Log.e(TAG, "Island overlay failed to start", error)
        }
        flushQueue()
        pill?.let { IslandOverlayService.applyLive(it) }
        IslandMonitors.watchPhone(this)
        IslandMonitors.watchBattery(this)
        IslandNotificationListener.instance?.resync()
        return START_STICKY
    }

    override fun onDestroy() {
        if (instance == this) instance = null
        removeWindow()
        pill = null
        super.onDestroy()
    }

    private fun present(context: Context, type: Int) {
        if (root != null && windowType == type) return
        windowType = type
        hostContext = context
        removeWindow()
        window = context.getSystemService(WindowManager::class.java)
        val view = pill ?: IslandPillView(this).also { created ->
            created.onWindow = { left, top, width, height -> applyWindow(left, top, width, height) }
            pill = created
        }
        view.bindCutout()
        val frame = view.windowFrame()
        val params = layoutParams(frame[2], frame[3]).apply {
            x = frame[0]
            y = frame[1]
        }
        val manager = window ?: return
        manager.addView(view, params)
        root = view
        appliedX = params.x
        appliedY = params.y
        appliedW = params.width
        appliedH = params.height
        view.post { calibrateShift() }
    }

    private fun removeWindow() {
        val view = root ?: return
        try {
            window?.removeView(view)
        } catch (_: Exception) {
        }
        root = null
        calibrated = false
    }

    fun applyMedia(track: IslandMedia.Track?) {
        pill?.onMedia(track)
    }

    private fun layoutParams(width: Int, height: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            width,
            height,
            windowType,
            // NOT_FOCUSABLE is what lets the system back gesture reach the
            // focused app. NOT_TOUCH_MODAL lets touches outside this rect through.
            // The window is only the pill, never the full screen.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
            if (Build.VERSION.SDK_INT >= 30) {
                setFitInsetsTypes(0)
                setFitInsetsSides(0)
                setFitInsetsIgnoringVisibility(true)
            }
        }
    }

    private fun applyWindow(left: Int, top: Int, width: Int, height: Int) {
        val view = root ?: return
        val manager = window ?: return
        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        val nextW = width.coerceAtLeast(1)
        val nextH = height.coerceAtLeast(1)
        val nextX = left
        val nextY = top - shiftY
        if (appliedX == nextX && appliedY == nextY && appliedW == nextW && appliedH == nextH) return
        params.x = nextX
        params.y = nextY
        params.width = nextW
        params.height = nextH
        try {
            manager.updateViewLayout(view, params)
            appliedX = nextX
            appliedY = nextY
            appliedW = nextW
            appliedH = nextH
        } catch (error: Exception) {
            Log.w(TAG, "Island resize failed", error)
        }
    }

    private fun calibrateShift() {
        val host = root ?: return
        if (calibrated) return
        val params = host.layoutParams as? WindowManager.LayoutParams ?: return
        val loc = IntArray(2)
        host.getLocationOnScreen(loc)
        calibrated = true
        val delta = loc[1] - params.y
        if (delta <= 1) return
        shiftY = delta
        params.y -= delta
        try {
            window?.updateViewLayout(host, params)
            appliedY = params.y
        } catch (error: Exception) {
            Log.w(TAG, "Could not lift the island into the status bar", error)
        }
    }

    fun refreshForeground() {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (useMic) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, 7, notification(), type)
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val existing = manager.getNotificationChannel(CHANNEL)
            if (existing == null) {
                val channel = NotificationChannel(CHANNEL, "Island", NotificationManager.IMPORTANCE_MIN)
                channel.description = "Required while the island overlay is showing"
                channel.setSound(null, null)
                channel.enableVibration(false)
                channel.enableLights(false)
                channel.setShowBadge(false)
                channel.lockscreenVisibility = Notification.VISIBILITY_SECRET
                manager.createNotificationChannel(channel)
            }
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Island")
            .setSilent(true)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .build()
    }

    private fun flushQueue() {
        val pending = queue.toList()
        queue.clear()
        val view = pill ?: return
        for ((name, args) in pending) view.command(name, args)
    }

    companion object {
        private const val TAG = "IslandOverlay"
        private const val CHANNEL = "island.fgs"
        private val queue = mutableListOf<Pair<String, Map<String, Any?>>>()
        private var latestMedia: IslandMedia.Track? = null
        private var mediaKnown = false
        private var latestCall: Pair<String, String>? = null
        private var latestMessage: Pair<String, String>? = null
        private var latestMissed: String? = null
        private var latestTimer: TimerSnap? = null
        private var latestProgress: ProgressSnap? = null
        private var latestRide: RideSnap? = null
        private var latestStatus: Pair<String, String>? = null
        private var latestBattery: Pair<Int, Boolean>? = null
        private var latestCallIcon: android.graphics.Bitmap? = null
        private var latestMissedIcon: android.graphics.Bitmap? = null
        private var latestMessageIcon: android.graphics.Bitmap? = null
        private var latestStatusIcon: android.graphics.Bitmap? = null

        private data class TimerSnap(val remainingMs: Long, val paused: Boolean, val label: String, val icon: android.graphics.Bitmap? = null)
        private data class ProgressSnap(val title: String, val detail: String, val fraction: Float, val label: String, val icon: android.graphics.Bitmap? = null)
        private data class RideSnap(val title: String, val detail: String, val value: String, val delivery: Boolean, val icon: android.graphics.Bitmap? = null)

        @Volatile var instance: IslandOverlayService? = null
        @Volatile var useMic: Boolean = false

        fun start(context: Context) {
            val intent = Intent(context, IslandOverlayService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun onAccessibilityConnected(service: AccessibilityService) {
            val overlay = instance
            if (overlay == null) {
                start(service)
                return
            }
            overlay.present(service, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY)
        }

        fun onAccessibilityDisconnected() {
            val overlay = instance ?: return
            if (Settings.canDrawOverlays(overlay)) {
                overlay.present(overlay, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            } else {
                overlay.removeWindow()
            }
        }

        fun note(context: Context, title: String, body: String) {
            if (instance == null) start(context)
        }

        fun setMic(context: Context?, on: Boolean) {
            useMic = on
            val service = instance
            if (service != null) service.refreshForeground()
            else if (on && context != null) start(context)
        }

        fun onMedia(context: Context, track: IslandMedia.Track?) {
            latestMedia = track
            mediaKnown = true
            deliver(context) { it.onMedia(track) }
        }

        fun onCall(context: Context, state: String, label: String, icon: android.graphics.Bitmap? = null) {
            latestCall = state to label
            latestCallIcon = icon ?: latestCallIcon
            deliver(context) { it.onLiveCall(state, label, latestCallIcon) }
        }

        fun onMissed(context: Context, name: String, icon: android.graphics.Bitmap? = null) {
            latestMissed = name
            latestMissedIcon = icon ?: latestMissedIcon
            deliver(context) { it.onMissedCall(name, latestMissedIcon) }
        }

        fun onMissedCleared() {
            latestMissed = null
            instance?.pill?.onMissedCallCleared()
        }

        fun onMessage(context: Context, sender: String, body: String, icon: android.graphics.Bitmap? = null) {
            latestMessage = sender to body
            latestMessageIcon = icon ?: latestMessageIcon
            deliver(context) { it.onLiveMessage(sender, body, latestMessageIcon) }
        }

        fun onMessageCleared() {
            latestMessage = null
            instance?.pill?.onLiveMessageCleared()
        }

        fun onTimer(context: Context, remainingMs: Long, paused: Boolean, label: String, icon: android.graphics.Bitmap? = null) {
            latestTimer = TimerSnap(remainingMs, paused, label, icon ?: latestTimer?.icon)
            deliver(context) { it.onLiveTimer(remainingMs, paused, label, latestTimer?.icon) }
        }

        fun onTimerCleared() {
            latestTimer = null
            instance?.pill?.onLiveTimerCleared()
        }

        fun onProgress(context: Context, title: String, detail: String, fraction: Float, label: String, icon: android.graphics.Bitmap? = null) {
            latestProgress = ProgressSnap(title, detail, fraction, label, icon ?: latestProgress?.icon)
            deliver(context) { it.onLiveProgress(title, detail, fraction, label, latestProgress?.icon) }
        }

        fun onProgressCleared() {
            latestProgress = null
            instance?.pill?.onLiveProgressCleared()
        }

        fun onRide(context: Context, title: String, detail: String, value: String, delivery: Boolean, icon: android.graphics.Bitmap? = null) {
            latestRide = RideSnap(title, detail, value, delivery, icon ?: latestRide?.icon)
            deliver(context) { it.onLiveRide(title, detail, value, delivery, latestRide?.icon) }
        }

        fun onRideCleared() {
            latestRide = null
            instance?.pill?.onLiveRideCleared()
        }

        fun batteryLive(): Boolean = latestBattery != null

        fun onBattery(context: Context, percent: Int, charging: Boolean) {
            latestBattery = percent to charging
            deliver(context) { it.onLiveBattery(percent, charging) }
        }

        fun onBatteryCleared() {
            latestBattery = null
            instance?.pill?.onLiveBatteryCleared()
        }

        fun onStatus(context: Context, title: String, detail: String, icon: android.graphics.Bitmap? = null) {
            latestStatus = title to detail
            latestStatusIcon = icon ?: latestStatusIcon
            deliver(context) { it.onLiveStatus(title, detail, latestStatusIcon) }
        }

        fun onStatusCleared() {
            latestStatus = null
            instance?.pill?.onLiveStatusCleared()
        }

        private fun deliver(context: Context, apply: (IslandPillView) -> Unit) {
            val service = instance
            val view = service?.pill
            if (view != null) {
                apply(view)
                return
            }
            if (service == null) start(context)
        }

        private fun applyLive(view: IslandPillView) {
            if (mediaKnown) view.onMedia(latestMedia)
            latestMissed?.let { view.onMissedCall(it, latestMissedIcon) }
            latestCall?.let { view.onLiveCall(it.first, it.second, latestCallIcon) }
            latestMessage?.let { view.onLiveMessage(it.first, it.second, latestMessageIcon) }
            latestTimer?.let { view.onLiveTimer(it.remainingMs, it.paused, it.label, it.icon) }
            latestProgress?.let { view.onLiveProgress(it.title, it.detail, it.fraction, it.label, it.icon) }
            latestRide?.let { view.onLiveRide(it.title, it.detail, it.value, it.delivery, it.icon) }
            latestStatus?.let { view.onLiveStatus(it.first, it.second, latestStatusIcon) }
            latestBattery?.let { view.onLiveBattery(it.first, it.second) }
        }

        fun dispatch(context: Context, name: String, args: Map<String, Any?>) {
            val view = instance?.pill
            if (view != null) view.command(name, args)
            else {
                queue.add(name to args)
                start(context)
            }
        }

        fun markReady() {
            instance?.flushQueue()
        }

        fun currentFrame(): Map<String, Double> {
            val service = instance ?: return mapOf("left" to 0.0, "top" to 0.0, "width" to 0.0, "height" to 0.0)
            val density = service.resources.displayMetrics.density.toDouble().coerceAtLeast(1.0)
            return mapOf(
                "left" to service.appliedX / density,
                "top" to (service.appliedY + service.shiftY) / density,
                "width" to service.appliedW / density,
                "height" to service.appliedH / density,
            )
        }

        @Suppress("UNUSED_PARAMETER")
        fun applyFrame(left: Float, top: Float, width: Float, height: Float, touches: List<Map<String, Any?>>? = null) {
            // The native pill sizes itself. Kept so a stale caller does not crash.
        }
    }
}
