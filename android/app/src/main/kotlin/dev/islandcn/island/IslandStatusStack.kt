package dev.islandcn.island

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Status-bar band behind the island: the app window, then the status-bar mask, then the island.
 * Captures are event-driven. A window that cannot be screenshotted is left out; if the app
 * window itself fails, the stack is dropped and the island draws as it does today.
 * Nothing is written to disk.
 *
 * Display takeScreenshot captures the accessibility overlay too, so the strip under the island
 * is composited from takeScreenshotOfWindow of the windows below it. The mask is the pixels
 * where that strip differs from the app window.
 */
internal object IslandStatusStack {
    class Frame(
        val windowStrip: Bitmap,
        val mask: Bitmap,
        val widthPx: Int,
        val statusPx: Int,
    )

    /** Last probe. Null until a capture has looked for the status-bar window. */
    @Volatile var statusBarWindowReachable: Boolean? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()
    private var service: IslandAccessibilityService? = null
    private var inflight = false
    private var pending = false
    private var lastStart = 0L
    private var registered = false
    private var frame: Frame? = null

    private val capture = Runnable { start() }

    private val clock = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> main.removeCallbacks(capture)
                Intent.ACTION_SCREEN_ON, Intent.ACTION_TIME_TICK -> schedule(400)
            }
        }
    }

    fun attach(host: IslandAccessibilityService) {
        service = host
        if (!registered) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                host.registerReceiver(clock, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                host.registerReceiver(clock, filter)
            }
            registered = true
        }
        schedule(800)
    }

    fun detach(host: IslandAccessibilityService) {
        if (service == host) service = null
        main.removeCallbacks(capture)
        if (registered) {
            try {
                host.unregisterReceiver(clock)
            } catch (_: Exception) {
            }
            registered = false
        }
        publish(null)
    }

    fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: return
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            schedule(500)
        }
    }

    private fun schedule(delayMs: Long) {
        main.removeCallbacks(capture)
        main.postDelayed(capture, delayMs)
    }

    private fun start() {
        val host = service ?: return
        if (Build.VERSION.SDK_INT < 34) return
        if (!IslandMonitors.screenLit()) return
        val now = SystemClock.uptimeMillis()
        if (inflight) {
            pending = true
            return
        }
        if (now - lastStart < 700L) {
            schedule(700L - (now - lastStart))
            return
        }
        lastStart = now
        inflight = true
        val token = now
        main.postDelayed({
            if (inflight && lastStart == token) {
                Log.w(TAG, "Status stack capture timed out")
                inflight = false
                if (pending) {
                    pending = false
                    schedule(700)
                }
            }
        }, 4500)
        val screen = screenSize(host)
        val status = statusBarPx(host, screen.second)
        val wins = snapshot(host)
        val app = pickApp(wins)
        if (app == null || screen.first < 2 || status < 2) {
            finish(null, statusBar = false, looked = app != null)
            return
        }
        val statusBar = pickStatusBar(wins, screen.first, status)
        // Topmost first. The overlay is not part of the screen under the island.
        val layers = wins.filter {
            it.type != AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY && intersectsBand(it, screen.first, status)
        }
        shoot(host, app.id) { appBmp ->
            if (appBmp == null) {
                main.post { finish(null, statusBar = false, looked = true) }
                return@shoot
            }
            val others = layers.filter { it.id != app.id }
            if (others.isEmpty()) {
                compose(app, appBmp, layers, emptyMap(), screen.first, status, statusBar?.id == app.id)
                return@shoot
            }
            val shots = HashMap<Int, Bitmap?>()
            var left = others.size
            for (win in others) {
                shoot(host, win.id) { bmp ->
                    shots[win.id] = bmp
                    left -= 1
                    if (left == 0) {
                        val reachable = statusBar != null && shots[statusBar.id] != null
                        compose(app, appBmp, layers, shots, screen.first, status, reachable)
                    }
                }
            }
        }
    }

    private fun compose(
        app: Win,
        appBmp: Bitmap,
        layersTopFirst: List<Win>,
        shots: Map<Int, Bitmap?>,
        screenW: Int,
        status: Int,
        reachable: Boolean,
    ) {
        work.execute {
            val built = try {
                val windowStrip = strip(screenW, status)
                blit(windowStrip, appBmp, app.bounds, status)
                val full = strip(screenW, status)
                // Lower windows first, so the status bar lands on top of the app.
                for (win in layersTopFirst.asReversed()) {
                    val bmp = if (win.id == app.id) appBmp else shots[win.id]
                    if (bmp != null) blit(full, bmp, win.bounds, status)
                }
                val mask = difference(windowStrip, full)
                full.recycle()
                Frame(windowStrip, mask, screenW, status)
            } catch (error: Exception) {
                Log.w(TAG, "Status stack compose failed", error)
                null
            }
            appBmp.recycle()
            for (bmp in shots.values) bmp?.recycle()
            main.post { finish(built, reachable, looked = true) }
        }
    }

    private fun finish(next: Frame?, statusBar: Boolean, looked: Boolean) {
        if (looked && statusBarWindowReachable != statusBar) {
            statusBarWindowReachable = statusBar
            Log.i(TAG, if (statusBar) "Status bar window screenshot reachable" else "Status bar window not reachable; difference mask only")
        }
        publish(next)
        inflight = false
        if (pending) {
            pending = false
            schedule(700)
        }
    }

    private fun publish(next: Frame?) {
        val previous = frame
        frame = next
        IslandOverlayService.statusFrame(next)
        if (previous != null && previous !== next) {
            previous.windowStrip.recycle()
            previous.mask.recycle()
        }
    }

    private fun shoot(host: AccessibilityService, windowId: Int, done: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < 34) {
            done(null)
            return
        }
        try {
            host.takeScreenshotOfWindow(windowId, work, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    done(software(screenshot))
                }

                override fun onFailure(errorCode: Int) {
                    Log.w(TAG, "Window screenshot $windowId failed: $errorCode")
                    done(null)
                }
            })
        } catch (error: Exception) {
            Log.w(TAG, "Window screenshot $windowId threw", error)
            done(null)
        }
    }

    private fun software(result: AccessibilityService.ScreenshotResult): Bitmap? {
        val buffer = result.hardwareBuffer ?: return null
        return try {
            val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return null
            val copy = hardware.copy(Bitmap.Config.ARGB_8888, false)
            hardware.recycle()
            copy
        } catch (error: Exception) {
            Log.w(TAG, "Screenshot copy failed", error)
            null
        } finally {
            buffer.close()
        }
    }

    private fun strip(width: Int, height: Int) = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    private fun blit(dest: Bitmap, src: Bitmap, bounds: Rect, status: Int) {
        if (src.width < 1 || src.height < 1 || bounds.width() < 1 || bounds.height() < 1) return
        val canvas = Canvas(dest)
        val dst = Rect(bounds)
        if (!dst.intersect(0, 0, dest.width, min(dest.height, status))) return
        val srcRect = Rect(
            ((dst.left - bounds.left).toFloat() / bounds.width() * src.width).toInt().coerceIn(0, src.width),
            ((dst.top - bounds.top).toFloat() / bounds.height() * src.height).toInt().coerceIn(0, src.height),
            ((dst.right - bounds.left).toFloat() / bounds.width() * src.width).toInt().coerceIn(0, src.width),
            ((dst.bottom - bounds.top).toFloat() / bounds.height() * src.height).toInt().coerceIn(0, src.height),
        )
        if (srcRect.width() < 1 || srcRect.height() < 1) return
        canvas.drawBitmap(src, srcRect, dst, null)
    }

    /** Pixels that differ are the status bar. The mask keeps the composited pixel there. */
    private fun difference(windowStrip: Bitmap, full: Bitmap): Bitmap {
        val width = min(windowStrip.width, full.width)
        val height = min(windowStrip.height, full.height)
        val mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val appPx = IntArray(width * height)
        val fullPx = IntArray(width * height)
        val out = IntArray(width * height)
        windowStrip.getPixels(appPx, 0, width, 0, 0, width, height)
        full.getPixels(fullPx, 0, width, 0, 0, width, height)
        for (i in out.indices) {
            val app = appPx[i]
            val over = fullPx[i]
            out[i] = if (differs(app, over)) over else 0
        }
        mask.setPixels(out, 0, width, 0, 0, width, height)
        return mask
    }

    private fun differs(a: Int, b: Int): Boolean {
        if (abs((a ushr 24) - (b ushr 24)) > 12) return true
        if (abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) > 12) return true
        if (abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) > 12) return true
        return abs((a and 0xFF) - (b and 0xFF)) > 12
    }

    private fun snapshot(host: AccessibilityService): List<Win> {
        val list = host.windows ?: return emptyList()
        val copy = ArrayList<Win>(list.size)
        for (info in list) {
            val bounds = Rect()
            info.getBoundsInScreen(bounds)
            copy.add(
                Win(
                    id = info.id,
                    type = info.type,
                    bounds = bounds,
                    focused = info.isFocused,
                    active = info.isActive,
                    layer = info.layer,
                    title = info.title?.toString().orEmpty(),
                ),
            )
            info.recycle()
        }
        return copy
    }

    private fun pickApp(wins: List<Win>): Win? {
        val apps = wins.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.bounds.width() > 1 }
        return apps.maxWithOrNull(compareBy<Win> { it.focused }.thenBy { it.active }.thenBy { it.layer })
    }

    /** The tightest system window that actually covers the status band. */
    private fun pickStatusBar(wins: List<Win>, screenW: Int, status: Int): Win? {
        val band = Rect(0, 0, screenW, status)
        return wins.asSequence()
            .filter { it.type == AccessibilityWindowInfo.TYPE_SYSTEM }
            .map { win ->
                val hit = Rect(win.bounds)
                hit.intersect(band)
                win to hit
            }
            .filter { it.second.width() > screenW / 2 && it.second.height() > status / 3 }
            .minByOrNull { it.first.bounds.height() }
            ?.first
    }

    private fun intersectsBand(win: Win, screenW: Int, status: Int): Boolean {
        val hit = Rect(win.bounds)
        return hit.intersect(0, 0, screenW, status) && hit.width() > 1 && hit.height() > 1
    }

    private fun screenSize(context: Context): Pair<Int, Int> {
        val bounds = context.getSystemService(WindowManager::class.java)?.currentWindowMetrics?.bounds
        return (bounds?.width() ?: 0) to (bounds?.height() ?: 0)
    }

    private fun statusBarPx(context: Context, screenH: Int): Int {
        val res = context.resources
        val id = res.getIdentifier("status_bar_height", "dimen", "android")
        val dimen = if (id > 0) res.getDimensionPixelSize(id) else 0
        val cutout = context.getSystemService(WindowManager::class.java)
            ?.currentWindowMetrics?.windowInsets?.displayCutout?.safeInsetTop ?: 0
        return max(dimen, cutout).coerceAtMost(if (screenH > 0) screenH / 3 else dimen).coerceAtLeast(1)
    }

    private class Win(
        val id: Int,
        val type: Int,
        val bounds: Rect,
        val focused: Boolean,
        val active: Boolean,
        val layer: Int,
        val title: String,
    )

    private const val TAG = "IslandOverlay"
}
