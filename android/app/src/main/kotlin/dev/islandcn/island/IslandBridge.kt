package dev.islandcn.island

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel

object IslandBridge {
    const val PLATFORM = "dev.islandcn/platform"
    const val EVENTS = "dev.islandcn/events"
    private var permissionResult: MethodChannel.Result? = null

    fun install(engine: FlutterEngine, context: Context, activity: Activity?) {
        EventChannel(engine.dartExecutor.binaryMessenger, EVENTS).setStreamHandler(object : EventChannel.StreamHandler {
            private var sink: EventChannel.EventSink? = null

            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                sink = events
                if (events != null) IslandBus.add(events)
            }

            override fun onCancel(arguments: Any?) {
                sink?.let { IslandBus.remove(it) }
                sink = null
            }
        })
        MethodChannel(engine.dartExecutor.binaryMessenger, PLATFORM).setMethodCallHandler { call, result ->
            dispatch(context, activity, call, result)
        }
    }

    fun runtimePermissions(): Array<String> {
        val names = mutableListOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.ANSWER_PHONE_CALLS,
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.RECORD_AUDIO,
        )
        if (Build.VERSION.SDK_INT >= 33) names.add(Manifest.permission.POST_NOTIFICATIONS)
        return names.toTypedArray()
    }

    fun permissionMap(context: Context): Map<String, Any> {
        fun granted(name: String) = ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED
        return mapOf(
            "type" to "permissions",
            "READ_PHONE_STATE" to granted(Manifest.permission.READ_PHONE_STATE),
            "ANSWER_PHONE_CALLS" to granted(Manifest.permission.ANSWER_PHONE_CALLS),
            "READ_SMS" to granted(Manifest.permission.READ_SMS),
            "RECEIVE_SMS" to granted(Manifest.permission.RECEIVE_SMS),
            "READ_CALENDAR" to granted(Manifest.permission.READ_CALENDAR),
            "RECORD_AUDIO" to granted(Manifest.permission.RECORD_AUDIO),
            "POST_NOTIFICATIONS" to if (Build.VERSION.SDK_INT >= 33) granted(Manifest.permission.POST_NOTIFICATIONS) else true,
            "notificationAccess" to notificationAccess(context),
            "overlay" to Settings.canDrawOverlays(context),
            "accessibility" to IslandAccessibilityService.enabled(context),
        )
    }

    fun notificationAccess(context: Context): Boolean {
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: return false
        return flat.contains(context.packageName)
    }

    fun dispatch(context: Context, activity: Activity?, call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "display" -> result.success(IslandMonitors.display(context))
            "cutout" -> result.success(mapOf("cutouts" to IslandMonitors.cutouts(context)))
            "battery" -> result.success(IslandMonitors.battery(context))
            "calendar" -> Thread {
                val payload = IslandMonitors.calendar(context)
                android.os.Handler(android.os.Looper.getMainLooper()).post { result.success(payload) }
            }.start()
            "permissionStatus" -> result.success(permissionMap(context))
            "requestPermissions" -> request(context, activity, call.argument<List<String>>("permissions") ?: emptyList(), result)
            "openNotificationAccess" -> {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                result.success(mapOf("ok" to true))
            }
            "openAccessibilitySettings" -> {
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                result.success(mapOf("ok" to true))
            }
            "openOverlaySettings" -> {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                result.success(mapOf("ok" to true))
            }
            "openAppSettings" -> {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                result.success(mapOf("ok" to true))
            }
            "acceptCall" -> result.success(mapOf("ok" to IslandMonitors.acceptCall(context)))
            "endCall" -> result.success(mapOf("ok" to IslandMonitors.endCall(context)))
            "startMic" -> result.success(mapOf("ok" to IslandMonitors.startMic(context)))
            "stopMic" -> {
                IslandMonitors.stopMic()
                result.success(mapOf("ok" to true))
            }
            "setSpeaker" -> {
                IslandMonitors.setSpeaker(context, call.argument<Boolean>("speaker") == true)
                result.success(mapOf("ok" to true))
            }
            "mediaAction" -> {
                IslandMonitors.mediaAction(call.argument<String>("action") ?: "")
                result.success(mapOf("ok" to true))
            }
            "setLive" -> result.success(mapOf("ok" to true))
            "clearLive" -> result.success(mapOf("ok" to true))
            "ensureOverlay" -> {
                val accessibility = IslandAccessibilityService.enabled(context)
                val allowed = Settings.canDrawOverlays(context) || accessibility
                if (allowed) IslandOverlayService.start(context)
                result.success(mapOf("ok" to allowed, "accessibility" to accessibility))
            }
            "command" -> {
                val name = call.argument<String>("name") ?: ""
                @Suppress("UNCHECKED_CAST")
                val args = call.argument<Map<String, Any?>>("args") ?: emptyMap()
                IslandOverlayService.dispatch(context, name, args)
                result.success(mapOf("ok" to true))
            }
            "overlayFrame" -> result.success(IslandOverlayService.currentFrame())
            "setFrame" -> {
                @Suppress("UNCHECKED_CAST")
                val touches = call.argument<List<Map<String, Any?>>>("touches")
                IslandOverlayService.applyFrame(
                    (call.argument<Number>("left"))?.toFloat() ?: 0f,
                    (call.argument<Number>("top"))?.toFloat() ?: 0f,
                    (call.argument<Number>("width"))?.toFloat() ?: 1f,
                    (call.argument<Number>("height"))?.toFloat() ?: 1f,
                    touches,
                )
                result.success(mapOf("ok" to true))
            }
            "ready" -> {
                IslandOverlayService.markReady()
                result.success(mapOf("ok" to true))
            }
            else -> result.notImplemented()
        }
    }

    private fun request(context: Context, activity: Activity?, names: List<String>, result: MethodChannel.Result) {
        val host = activity ?: run {
            result.success(permissionMap(context))
            return
        }
        val needed = names.filter {
            ContextCompat.checkSelfPermission(host, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isEmpty()) {
            result.success(permissionMap(host))
            return
        }
        permissionResult = result
        ActivityCompat.requestPermissions(host, needed.toTypedArray(), 41)
    }

    fun onPermissionResult(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
            IslandMonitors.watchPhone(context)
        }
        val map = permissionMap(context)
        permissionResult?.success(map)
        permissionResult = null
        IslandBus.emit(map)
    }
}
