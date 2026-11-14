package dev.islandcn.island

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {
    private var stage = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        advance()
    }

    override fun onResume() {
        super.onResume()
        advance()
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        IslandBridge.install(flutterEngine, this, this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41) {
            IslandBridge.onPermissionResult(this)
            advance()
        }
    }

    private fun advance() {
        val missing = IslandBridge.runtimePermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            if (stage < 1) {
                stage = 1
                ActivityCompat.requestPermissions(this, missing.toTypedArray(), 41)
            }
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            if (stage < 2) {
                stage = 2
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            return
        }
        IslandOverlayService.start(this)
        if (!IslandBridge.notificationAccess(this)) {
            if (stage < 3) {
                stage = 3
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            }
            return
        }
        stage = 4
    }

    fun requestAgain(kind: String) {
        when (kind) {
            "runtime" -> {
                stage = 0
                advance()
            }
            "overlay" -> startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            "notifications" -> startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            "phone" -> ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.ANSWER_PHONE_CALLS), 41)
        }
    }
}
