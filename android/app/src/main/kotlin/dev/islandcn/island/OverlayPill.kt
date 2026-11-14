package dev.islandcn.island

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import androidx.core.view.ViewCompat

object OverlayPill {
    private var view: TextView? = null

    fun show(context: Context, label: String) {
        if (!Settings.canDrawOverlays(context)) return
        val window = context.getSystemService(WindowManager::class.java) ?: return
        val existing = view
        if (existing != null) {
            existing.text = label
            return
        }
        val pill = TextView(context).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(36, 18, 36, 18)
            background = GradientDrawable().apply {
                setColor(Color.BLACK)
                cornerRadius = 80f
            }
            setOnClickListener {
                val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                context.startActivity(intent)
            }
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 24
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        try {
            window.addView(pill, params)
            view = pill
            ViewCompat.requestApplyInsets(pill)
        } catch (_: Exception) {
            view = null
        }
    }

    fun hide(context: Context) {
        val pill = view ?: return
        val window = context.getSystemService(WindowManager::class.java) ?: return
        try {
            window.removeView(pill)
        } catch (_: Exception) {
        }
        view = null
    }
}
