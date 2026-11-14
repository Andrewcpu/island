package dev.islandcn.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.accessibilityservice.AccessibilityService
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Region
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.flutter.FlutterInjector
import io.flutter.embedding.android.FlutterTextureView
import io.flutter.embedding.android.FlutterView
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.embedding.engine.dart.DartExecutor
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugins.GeneratedPluginRegistrant

class IslandOverlayService : Service() {
    private var engine: FlutterEngine? = null
    private var flutterView: FlutterView? = null
    private var root: FrameLayout? = null
    private var window: WindowManager? = null
    private var appliedX = Int.MIN_VALUE
    private var appliedY = Int.MIN_VALUE
    private var appliedW = Int.MIN_VALUE
    private var appliedH = Int.MIN_VALUE
    private var shiftY = 0
    private var calibrated = false
    private var windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    private var hostContext: Context = this
    var commands: MethodChannel? = null
    var ready: Boolean = false
    private var callbacks: ComponentCallbacks? = null

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
            if (root == null && overlay) {
                windowType = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                hostContext = this
                window = getSystemService(WindowManager::class.java)
                showNativePill()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        if (instance == this) instance = null
        val view = root
        if (view != null) {
            try {
                window?.removeView(view)
            } catch (_: Exception) {
            }
        }
        flutterView?.detachFromFlutterEngine()
        flutterView = null
        root = null
        callbacks?.let { application.unregisterComponentCallbacks(it) }
        callbacks = null
        engine?.lifecycleChannel?.appIsDetached()
        engine?.destroy()
        engine = null
        commands = null
        ready = false
        super.onDestroy()
    }

    private fun present(context: Context, type: Int) {
        if (root != null && windowType == type && flutterView != null) return
        windowType = type
        hostContext = context
        removeWindow()
        window = context.getSystemService(WindowManager::class.java)
        showNativePill()
        ensureEngine()
    }

    private fun removeWindow() {
        val flutter = flutterView
        if (flutter != null) (flutter.parent as? ViewGroup)?.removeView(flutter)
        val view = root
        if (view != null) {
            try {
                window?.removeView(view)
            } catch (_: Exception) {
            }
        }
        root = null
        calibrated = false
    }

    private fun ensureEngine() {
        val created = engine
        if (created != null) {
            placeFlutter()
            created.lifecycleChannel.appIsResumed()
            return
        }
        val loader = FlutterInjector.instance().flutterLoader()
        loader.startInitialization(applicationContext)
        loader.ensureInitializationComplete(applicationContext, null)
        val fresh = FlutterEngine(applicationContext)
        GeneratedPluginRegistrant.registerWith(fresh)
        IslandBridge.install(fresh, applicationContext, null)
        val channel = MethodChannel(fresh.dartExecutor.binaryMessenger, COMMANDS)
        commands = channel
        val texture = FlutterTextureView(applicationContext)
        texture.isOpaque = false
        val flutter = FlutterView(applicationContext, texture)
        flutter.setBackgroundColor(Color.TRANSPARENT)
        flutter.isClickable = true
        flutter.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        engine = fresh
        flutterView = flutter
        placeFlutter()
        flutter.attachToFlutterEngine(fresh)
        fresh.lifecycleChannel.appIsResumed()
        fresh.dartExecutor.executeDartEntrypoint(
            DartExecutor.DartEntrypoint(loader.findAppBundlePath(), "overlayMain"),
        )
        val watcher = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                commands?.invokeMethod("metrics", null)
            }

            override fun onLowMemory() {}
        }
        callbacks = watcher
        application.registerComponentCallbacks(watcher)
        IslandMonitors.watchBattery(this)
        IslandMonitors.watchPhone(this)
    }

    private fun placeFlutter() {
        val host = root ?: return
        val flutter = flutterView ?: return
        if (flutter.parent === host) return
        (flutter.parent as? ViewGroup)?.removeView(flutter)
        host.addView(flutter)
    }

    private fun showNativePill() {
        if (root != null) return
        val manager = window ?: hostContext.getSystemService(WindowManager::class.java)
        window = manager
        val host = IslandHost(hostContext)
        host.onOutside = { commands?.invokeMethod("outside", null) }
        host.setBackgroundColor(Color.TRANSPARENT)
        host.addView(PillBackdrop(hostContext), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        val params = initialParams()
        manager.addView(host, params)
        root = host
        appliedX = params.x
        appliedY = params.y
        appliedW = params.width
        appliedH = params.height
        host.post { calibrateShift() }
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
        if (hasRequest) {
            applyFrame(requestedLeft, requestedTop, requestedWidth, requestedHeight)
            return
        }
        params.y -= delta
        try {
            window?.updateViewLayout(host, params)
            appliedY = params.y
        } catch (error: Exception) {
            Log.w(TAG, "Could not lift the island into the status bar", error)
        }
    }

    private fun initialParams(): WindowManager.LayoutParams {
        val density = resources.displayMetrics.density
        val metrics = getSystemService(WindowManager::class.java).currentWindowMetrics
        val screen = metrics.bounds
        val cutout = metrics.windowInsets.displayCutout
        val hole = cutout?.boundingRects?.minByOrNull { rect ->
            kotlin.math.abs(rect.centerX() - screen.centerX())
        }
        val width = (96 * density).toInt()
        val height = (37 * density).toInt()
        val centerX = hole?.centerX() ?: screen.centerX()
        val centerY = hole?.centerY() ?: (18 * density).toInt()
        return layoutParams(width, height).apply {
            x = (centerX - width / 2).coerceIn(0, (screen.width() - width).coerceAtLeast(0))
            y = centerY - height / 2
        }
    }

    private fun layoutParams(width: Int, height: Int): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            width,
            height,
            windowType,
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
                // Zero types and sides, or the parent frame starts under the status bar and Gravity pushes the island down.
                setFitInsetsTypes(0)
                setFitInsetsSides(0)
                setFitInsetsIgnoringVisibility(true)
            }
            applyTouchRegion(this, 0, 0, width, height, null)
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

    companion object {
        private const val TAG = "IslandOverlay"
        private const val CHANNEL = "island.fgs"
        private const val COMMANDS = "dev.islandcn/commands"
        private val queue = mutableListOf<Pair<String, Map<String, Any?>>>()
        private var hasRequest = false
        private var requestedLeft = 0f
        private var requestedTop = 0f
        private var requestedWidth = 0f
        private var requestedHeight = 0f
        private var requestedTouches: List<Map<String, Any?>>? = null

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

        fun dispatch(context: Context, name: String, args: Map<String, Any?>) {
            val service = instance
            val channel = service?.commands
            if (service?.ready == true && channel != null) {
                channel.invokeMethod(name, args)
            } else {
                queue.add(name to args)
                start(context)
            }
        }

        fun markReady() {
            val service = instance ?: return
            service.ready = true
            val channel = service.commands ?: return
            val pending = queue.toList()
            queue.clear()
            for ((name, args) in pending) channel.invokeMethod(name, args)
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

        fun applyFrame(left: Float, top: Float, width: Float, height: Float, touches: List<Map<String, Any?>>? = null) {
            val service = instance ?: return
            val view = service.root ?: return
            val manager = service.window ?: return
            hasRequest = true
            requestedLeft = left
            requestedTop = top
            requestedWidth = width
            requestedHeight = height
            if (touches != null) requestedTouches = touches
            val density = service.resources.displayMetrics.density
            val params = (view.layoutParams as? WindowManager.LayoutParams) ?: return
            val nextX = (left * density).toInt()
            val nextY = (top * density).toInt() - service.shiftY
            val nextW = (width * density).toInt().coerceAtLeast(1)
            val nextH = (height * density).toInt().coerceAtLeast(1)
            service.applyTouchRegion(params, nextX, nextY + service.shiftY, nextW, nextH, requestedTouches)
            if (service.appliedX == nextX && service.appliedY == nextY && service.appliedW == nextW && service.appliedH == nextH && touches == null) return
            params.x = nextX
            params.y = nextY
            params.width = nextW
            params.height = nextH
            try {
                manager.updateViewLayout(view, params)
                service.appliedX = nextX
                service.appliedY = nextY
                service.appliedW = nextW
                service.appliedH = nextH
            } catch (error: Exception) {
                Log.w(TAG, "Overlay frame update failed", error)
            }
        }
    }

    private fun applyTouchRegion(params: WindowManager.LayoutParams, originX: Int, originY: Int, width: Int, height: Int, touches: List<Map<String, Any?>>?) {
        val density = resources.displayMetrics.density
        val region = Region()
        val targets = ArrayList<RectF>()
        if (touches != null) {
            for (touch in touches) {
                val left = ((touch["left"] as? Number)?.toFloat() ?: continue) * density - originX
                val top = ((touch["top"] as? Number)?.toFloat() ?: continue) * density - originY
                val right = ((touch["right"] as? Number)?.toFloat() ?: continue) * density - originX
                val bottom = ((touch["bottom"] as? Number)?.toFloat() ?: continue) * density - originY
                if (right - left < 1f || bottom - top < 1f) continue
                targets.add(RectF(left, top, right, bottom))
            }
        }
        if (targets.isEmpty()) targets.add(RectF(0f, 0f, width.toFloat(), height.toFloat()))
        for (local in targets) {
            val path = Path()
            val radius = (local.height() / 2f).coerceAtMost(48f * density)
            path.addRoundRect(local, radius, radius, Path.Direction.CW)
            val clip = Region(local.left.toInt() - 1, local.top.toInt() - 1, local.right.toInt() + 1, local.bottom.toInt() + 1)
            val part = Region()
            part.setPath(path, clip)
            region.op(part, Region.Op.UNION)
        }
        // The touchable-region fields are hidden. Reflection lets the gap and the
        // capsule corners pass through; FLAG_NOT_TOUCH_MODAL already does that
        // for anything outside the window if the platform blocks the fields.
        try {
            val clazz = WindowManager.LayoutParams::class.java
            val insets = clazz.getDeclaredField("touchableInsets")
            val regionField = clazz.getDeclaredField("touchableRegion")
            insets.isAccessible = true
            regionField.isAccessible = true
            val existing = regionField.get(params) as? Region
            if (existing == null) regionField.set(params, region) else existing.set(region)
            insets.setInt(params, 3)
        } catch (error: Throwable) {
            Log.w(TAG, "Island touch region not applied", error)
        }
    }
}

private class IslandHost(context: Context) : FrameLayout(context) {
    var onOutside: (() -> Unit)? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            onOutside?.invoke()
            return true
        }
        return super.dispatchTouchEvent(ev)
    }
}

private class PillBackdrop(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 1f || h < 1f) return
        val density = resources.displayMetrics.density
        val radius = (h / 2f).coerceAtMost(48f * density)
        canvas.drawRoundRect(RectF(0f, 0f, w, h), radius, radius, paint)
    }
}
