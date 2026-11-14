package dev.islandcn.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentCallbacks
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
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
    private var window: WindowManager? = null
    var commands: MethodChannel? = null
    var ready: Boolean = false
    private var title: String = "Island"
    private var body: String = "Showing over other apps"
    private var callbacks: ComponentCallbacks? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        instance = this
        title = intent?.getStringExtra("title") ?: title
        body = intent?.getStringExtra("body") ?: body
        refreshForeground()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (flutterView == null) boot()
        return START_STICKY
    }

    override fun onDestroy() {
        if (instance == this) instance = null
        val view = flutterView
        if (view != null) {
            try {
                window?.removeView(view)
            } catch (_: Exception) {
            }
            view.detachFromFlutterEngine()
        }
        flutterView = null
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
        val loader = FlutterInjector.instance().flutterLoader()
        loader.startInitialization(applicationContext)
        loader.ensureInitializationComplete(applicationContext, null)
        val created = FlutterEngine(this)
        GeneratedPluginRegistrant.registerWith(created)
        IslandBridge.install(created, applicationContext, null)
        val channel = MethodChannel(created.dartExecutor.binaryMessenger, COMMANDS)
        commands = channel
        created.dartExecutor.executeDartEntrypoint(
            DartExecutor.DartEntrypoint(loader.findAppBundlePath(), "overlayMain"),
        )
        created.lifecycleChannel.appIsResumed()
        val texture = FlutterTextureView(this)
        texture.isOpaque = false
        val view = FlutterView(this, texture)
        view.setBackgroundColor(Color.TRANSPARENT)
        view.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                channel.invokeMethod("outside", null)
                true
            } else {
                false
            }
        }
        val manager = getSystemService(WindowManager::class.java)
        window = manager
        manager.addView(view, initialParams())
        view.attachToFlutterEngine(created)
        engine = created
        flutterView = view
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

    private fun initialParams(): WindowManager.LayoutParams {
        val density = resources.displayMetrics.density
        val width = (150 * density).toInt()
        val height = (48 * density).toInt()
        val screen = resources.displayMetrics.widthPixels
        return layoutParams(width, height).apply {
            x = (screen - width) / 2
            y = (12 * density).toInt()
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
                fitInsetsTypes = 0
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
            val channel = NotificationChannel(CHANNEL, "Island", NotificationManager.IMPORTANCE_LOW)
            channel.description = "The island showing over other apps"
            manager.createNotificationChannel(channel)
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .setContentIntent(pending)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "island.live"
        private const val COMMANDS = "dev.islandcn/commands"
        private val queue = mutableListOf<Pair<String, Map<String, Any?>>>()

        @Volatile var instance: IslandOverlayService? = null
        @Volatile var useMic: Boolean = false

        fun start(context: Context) {
            val intent = Intent(context, IslandOverlayService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun note(context: Context, title: String, body: String) {
            val service = instance
            if (service == null) {
                val intent = Intent(context, IslandOverlayService::class.java)
                    .putExtra("title", title)
                    .putExtra("body", body)
                ContextCompat.startForegroundService(context, intent)
                return
            }
            service.title = title
            service.body = body
            service.refreshForeground()
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

        fun applyFrame(left: Float, top: Float, width: Float, height: Float) {
            val service = instance ?: return
            val view = service.flutterView ?: return
            val manager = service.window ?: return
            val density = service.resources.displayMetrics.density
            val params = (view.layoutParams as? WindowManager.LayoutParams) ?: return
            val nextX = (left * density).toInt()
            val nextY = (top * density).toInt()
            val nextW = (width * density).toInt().coerceAtLeast(1)
            val nextH = (height * density).toInt().coerceAtLeast(1)
            if (params.x == nextX && params.y == nextY && params.width == nextW && params.height == nextH) return
            params.x = nextX
            params.y = nextY
            params.width = nextW
            params.height = nextH
            try {
                manager.updateViewLayout(view, params)
            } catch (_: Exception) {
            }
        }
    }
}
