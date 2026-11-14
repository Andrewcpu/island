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
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
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
    var commands: MethodChannel? = null
    var ready: Boolean = false
    private var callbacks: ComponentCallbacks? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        refreshForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (root == null) {
            try {
                boot()
            } catch (error: Throwable) {
                Log.e(TAG, "Island overlay failed to start", error)
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

    private fun boot() {
        showNativePill()
        val loader = FlutterInjector.instance().flutterLoader()
        loader.startInitialization(applicationContext)
        loader.ensureInitializationComplete(applicationContext, null)
        val created = FlutterEngine(this)
        GeneratedPluginRegistrant.registerWith(created)
        IslandBridge.install(created, applicationContext, null)
        val channel = MethodChannel(created.dartExecutor.binaryMessenger, COMMANDS)
        commands = channel
        val texture = FlutterTextureView(this)
        texture.isOpaque = false
        val flutter = FlutterView(this, texture)
        flutter.setBackgroundColor(Color.TRANSPARENT)
        flutter.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        )
        root?.addView(flutter)
        flutter.attachToFlutterEngine(created)
        created.lifecycleChannel.appIsResumed()
        created.dartExecutor.executeDartEntrypoint(
            DartExecutor.DartEntrypoint(loader.findAppBundlePath(), "overlayMain"),
        )
        engine = created
        flutterView = flutter
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

    private fun showNativePill() {
        if (root != null) return
        val manager = getSystemService(WindowManager::class.java)
        window = manager
        val host = FrameLayout(this)
        host.setBackgroundColor(Color.TRANSPARENT)
        host.addView(PillBackdrop(this), FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT,
        ))
        host.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                commands?.invokeMethod("outside", null)
                true
            } else {
                false
            }
        }
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
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
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

        @Volatile var instance: IslandOverlayService? = null
        @Volatile var useMic: Boolean = false

        fun start(context: Context) {
            val intent = Intent(context, IslandOverlayService::class.java)
            ContextCompat.startForegroundService(context, intent)
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

        fun applyFrame(left: Float, top: Float, width: Float, height: Float) {
            val service = instance ?: return
            val view = service.root ?: return
            val manager = service.window ?: return
            hasRequest = true
            requestedLeft = left
            requestedTop = top
            requestedWidth = width
            requestedHeight = height
            val density = service.resources.displayMetrics.density
            val params = (view.layoutParams as? WindowManager.LayoutParams) ?: return
            val nextX = (left * density).toInt()
            val nextY = (top * density).toInt() - service.shiftY
            val nextW = (width * density).toInt().coerceAtLeast(1)
            val nextH = (height * density).toInt().coerceAtLeast(1)
            if (service.appliedX == nextX && service.appliedY == nextY && service.appliedW == nextW && service.appliedH == nextH) return
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
