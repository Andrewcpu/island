package dev.islandcn.island

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.view.WindowCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val channelName = "dev.islandcn/platform"
    private val eventName = "dev.islandcn/events"
    private var permissionResult: MethodChannel.Result? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        IslandBus.attach(this)
        EventChannel(flutterEngine.dartExecutor.binaryMessenger, eventName).setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                IslandBus.sink = events
                IslandBus.flush()
            }

            override fun onCancel(arguments: Any?) {
                IslandBus.sink = null
            }
        })
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, channelName).setMethodCallHandler { call, result ->
            when (call.method) {
                "cutout" -> result.success(mapOf("cutouts" to IslandMonitors.cutouts(this)))
                "battery" -> result.success(IslandMonitors.battery(this))
                "calendar" -> Thread {
                    val payload = IslandMonitors.calendar(this)
                    runOnUiThread { result.success(payload) }
                }.start()
                "permissionStatus" -> result.success(permissionMap())
                "requestPermissions" -> {
                    val names = call.argument<List<String>>("permissions") ?: emptyList()
                    permissionResult = result
                    val needed = names.filter {
                        ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                    }
                    if (needed.isEmpty()) {
                        permissionResult = null
                        result.success(permissionMap())
                    } else {
                        ActivityCompat.requestPermissions(this, needed.toTypedArray(), 41)
                    }
                }
                "openNotificationAccess" -> {
                    startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    result.success(mapOf("ok" to true))
                }
                "openOverlaySettings" -> {
                    val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    startActivity(intent)
                    result.success(mapOf("ok" to true))
                }
                "openAppSettings" -> {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                    startActivity(intent)
                    result.success(mapOf("ok" to true))
                }
                "acceptCall" -> result.success(mapOf("ok" to IslandMonitors.acceptCall(this)))
                "endCall" -> result.success(mapOf("ok" to IslandMonitors.endCall(this)))
                "startMic" -> result.success(mapOf("ok" to IslandMonitors.startMic(this)))
                "stopMic" -> {
                    IslandMonitors.stopMic()
                    result.success(mapOf("ok" to true))
                }
                "setSpeaker" -> {
                    IslandMonitors.setSpeaker(this, call.argument<Boolean>("speaker") == true)
                    result.success(mapOf("ok" to true))
                }
                "mediaAction" -> {
                    IslandMonitors.mediaAction(call.argument<String>("action") ?: "")
                    result.success(mapOf("ok" to true))
                }
                "setLive" -> {
                    LiveActivityService.start(this, call.argument<String>("title") ?: "Island", call.argument<String>("body") ?: "")
                    result.success(mapOf("ok" to true))
                }
                "clearLive" -> {
                    LiveActivityService.stop(this)
                    result.success(mapOf("ok" to true))
                }
                "showOverlay" -> {
                    OverlayPill.show(this, call.argument<String>("label") ?: "Island")
                    result.success(mapOf("ok" to Settings.canDrawOverlays(this)))
                }
                "hideOverlay" -> {
                    OverlayPill.hide(this)
                    result.success(mapOf("ok" to true))
                }
                else -> result.notImplemented()
            }
        }
        if (hasPhonePermission()) IslandMonitors.watchPhone(this)
        IslandMonitors.watchBattery(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41) {
            if (hasPhonePermission()) IslandMonitors.watchPhone(this)
            permissionResult?.success(permissionMap())
            permissionResult = null
            IslandBus.emit(permissionMap())
        }
    }

    override fun onDestroy() {
        IslandMonitors.stopMic()
        IslandBus.detach(this)
        super.onDestroy()
    }

    private fun hasPhonePermission(): Boolean {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
    }

    private fun permissionMap(): Map<String, Any> {
        fun granted(name: String) = ActivityCompat.checkSelfPermission(this, name) == PackageManager.PERMISSION_GRANTED
        return mapOf(
            "type" to "permissions",
            "READ_PHONE_STATE" to granted(Manifest.permission.READ_PHONE_STATE),
            "ANSWER_PHONE_CALLS" to granted(Manifest.permission.ANSWER_PHONE_CALLS),
            "READ_SMS" to granted(Manifest.permission.READ_SMS),
            "RECEIVE_SMS" to granted(Manifest.permission.RECEIVE_SMS),
            "READ_CALENDAR" to granted(Manifest.permission.READ_CALENDAR),
            "RECORD_AUDIO" to granted(Manifest.permission.RECORD_AUDIO),
            "POST_NOTIFICATIONS" to if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.POST_NOTIFICATIONS) else true,
            "notificationAccess" to notificationAccess(),
            "overlay" to Settings.canDrawOverlays(this),
        )
    }

    private fun notificationAccess(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(packageName)
    }
}
