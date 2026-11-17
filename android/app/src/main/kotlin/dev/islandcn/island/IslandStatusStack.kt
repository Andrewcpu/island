package dev.islandcn.island

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
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
import android.view.Choreographer
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Status-bar band behind the island. The sliding mask is the difference, inside the status-bar
 * band, between a display screenshot and the app-window screenshot. Pixels that match the app
 * are transparent. Clock, icons, and the status-bar scrim stay opaque and are what slide.
 * A missing app-window shot does not fall back to sliding the whole display band.
 * Nothing is written to disk.
 */
internal object IslandStatusStack {
    class Frame(
        val windowStrip: Bitmap,
        val mask: Bitmap,
        val widthPx: Int,
        val statusPx: Int,
    )

    /** Last probe. The status bar itself is not a window we can shoot; the display frame is. */
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
    private var loggedShot = false
    private var loggedSkip = false
    private var suppressUntil = 0L

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
        val caps = host.serviceInfo?.capabilities ?: 0
        val can = (caps and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) != 0
        Log.i(TAG, if (can) "Screenshot capability granted" else "Screenshot capability missing; toggle Island in Accessibility settings")
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
        if (SystemClock.uptimeMillis() < suppressUntil) return
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
                reveal()
                inflight = false
                if (pending) {
                    pending = false
                    schedule(700)
                }
            }
        }, 4500)
        val screen = screenSize(host)
        val status = statusBarPx(host, screen.second)
        if (screen.first < 2 || status < 2) {
            finish(null, looked = true)
            return
        }
        val app = pickApp(snapshot(host))
        // No app window means there is nothing to subtract. Do not slide the whole band.
        if (app == null || Build.VERSION.SDK_INT < 34) {
            skip("no app window to diff")
            finish(null, looked = true)
            return
        }
        // The display shot includes this overlay. Hide the island and the strip, then capture.
        suppressUntil = Long.MAX_VALUE
        IslandOverlayService.conceal(true)
        val frames = Choreographer.getInstance()
        frames.postFrameCallback {
            frames.postFrameCallback {
                if (service == null || !inflight) {
                    reveal()
                    return@postFrameCallback
                }
                val waiting = AtomicInteger(2)
                var displayBmp: Bitmap? = null
                var appBmp: Bitmap? = null
                val gate = Any()
                var revealed = false
                fun shown() {
                    if (revealed) return
                    revealed = true
                    reveal()
                }
                fun arrive(display: Boolean, bmp: Bitmap?) {
                    if (display) shown()
                    val pair = synchronized(gate) {
                        if (display) displayBmp = bmp else appBmp = bmp
                        if (waiting.decrementAndGet() != 0) null else displayBmp to appBmp
                    } ?: return
                    val (shot, appShot) = pair
                    if (shot == null || appShot == null) {
                        shot?.recycle()
                        appShot?.recycle()
                        skip("app or display screenshot missing")
                        main.post { finish(null, looked = true) }
                        return
                    }
                    compose(shot, appShot, app, screen.first, status)
                }
                try {
                    shootDisplay(host) { arrive(true, it) }
                    shootWindow(host, app.id) { arrive(false, it) }
                } catch (error: Exception) {
                    Log.w(TAG, "Status capture failed to start", error)
                    shown()
                    main.post { finish(null, looked = true) }
                }
            }
        }
    }

    /** Show the island again. A capture must not leave it hidden. */
    private fun reveal() {
        suppressUntil = SystemClock.uptimeMillis() + 800L
        main.post { IslandOverlayService.conceal(false) }
    }

    private fun skip(reason: String) {
        if (loggedSkip) return
        loggedSkip = true
        Log.w(TAG, "Status slide skipped: $reason")
    }

    private fun compose(
        display: Bitmap,
        appBmp: Bitmap,
        app: Win,
        screenW: Int,
        status: Int,
    ) {
        work.execute {
            var built: Frame? = null
            try {
                // Both shots are already ARGB_8888 copies. Diff those, never the hardware buffers.
                val displayBand = cropTop(display, screenW, status)
                val appBand = strip(screenW, status)
                blit(appBand, appBmp, app.bounds, status)
                val mask = difference(displayBand, appBand)
                displayBand.recycle()
                if (mask == null) {
                    appBand.recycle()
                    skip("app window does not cover the status bar")
                } else {
                    // appBand is held still. mask is the only image that slides.
                    built = Frame(appBand, mask, screenW, status)
                    if (!loggedShot) {
                        loggedShot = true
                        Log.i(TAG, "Status strip is the display minus the app window")
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Status stack compose failed", error)
            }
            display.recycle()
            appBmp.recycle()
            val frame = built
            main.post { finish(frame, looked = true) }
        }
    }

    private fun finish(next: Frame?, looked: Boolean) {
        if (!looked) return
        if (next != null || frame == null) publish(next)
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

    private fun shootDisplay(host: AccessibilityService, done: (Bitmap?) -> Unit) {
        try {
            host.takeScreenshot(Display.DEFAULT_DISPLAY, work, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    done(software(screenshot))
                }

                override fun onFailure(errorCode: Int) {
                    if (!loggedShot) {
                        loggedShot = true
                        val caps = host.serviceInfo?.capabilities ?: 0
                        val can = (caps and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) != 0
                        Log.e(TAG, "Status strip invisible: display screenshot failed code=$errorCode canTakeScreenshot=$can")
                    }
                    done(null)
                }
            })
        } catch (error: Exception) {
            if (!loggedShot) {
                loggedShot = true
                Log.e(TAG, "Status strip invisible: display screenshot threw", error)
            }
            done(null)
        }
    }

    private fun shootWindow(host: AccessibilityService, windowId: Int, done: (Bitmap?) -> Unit) {
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
                    Log.w(TAG, "App window screenshot $windowId failed: $errorCode")
                    done(null)
                }
            })
        } catch (error: Exception) {
            Log.w(TAG, "App window screenshot $windowId threw", error)
            done(null)
        }
    }

    private fun software(result: AccessibilityService.ScreenshotResult): Bitmap? {
        val buffer = result.hardwareBuffer ?: return null
        var hardware: Bitmap? = null
        return try {
            // wrapHardwareBuffer is a HARDWARE bitmap. A software Canvas cannot draw it
            // (IllegalArgumentException: Software rendering doesn't support hardware bitmaps).
            // copy() readback is what produces pixels we can crop and diff.
            hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return null
            val copy = hardware.copy(Bitmap.Config.ARGB_8888, false)
            if (copy == null) {
                Log.w(TAG, "Screenshot copy returned null")
                null
            } else {
                copy
            }
        } catch (error: Exception) {
            Log.w(TAG, "Screenshot copy failed", error)
            null
        } finally {
            try {
                hardware?.recycle()
            } catch (_: Exception) {
            }
            try {
                buffer.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun strip(width: Int, height: Int) = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

    /** Top [status] rows of [src], scaled to the screen width. */
    private fun cropTop(src: Bitmap, screenW: Int, status: Int): Bitmap {
        val out = strip(screenW, status)
        val srcH = (status.toFloat() / screenW.coerceAtLeast(1) * src.width).toInt().coerceIn(1, src.height)
        Canvas(out).drawBitmap(src, Rect(0, 0, src.width, srcH), Rect(0, 0, screenW, status), null)
        return out
    }

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

    /**
     * Display minus app, both already clipped to the status-bar band.
     * Matching pixels are transparent. Differing pixels keep the display color at full opacity.
     * Returns null when the app shot does not actually cover the band, so the caller skips
     * the frame instead of sliding the whole display.
     */
    private fun difference(display: Bitmap, app: Bitmap): Bitmap? {
        if (display.width != app.width || display.height != app.height) return null
        val width = display.width
        val height = display.height
        val count = width * height
        if (count < 1) return null
        val shown = IntArray(count)
        val behind = IntArray(count)
        display.getPixels(shown, 0, width, 0, 0, width, height)
        app.getPixels(behind, 0, width, 0, 0, width, height)
        var covered = 0
        for (i in shown.indices) {
            val appPixel = behind[i]
            if (appPixel ushr 24 == 0) {
                shown[i] = 0
                continue
            }
            covered++
            val displayPixel = shown[i]
            shown[i] = if (sameColor(displayPixel, appPixel)) 0 else displayPixel or 0xFF000000.toInt()
        }
        if (covered * 2 < count) return null
        val out = strip(width, height)
        out.setPixels(shown, 0, width, 0, 0, width, height)
        return out
    }

    private fun sameColor(a: Int, b: Int): Boolean {
        val dr = abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF))
        val dg = abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF))
        val db = abs((a and 0xFF) - (b and 0xFF))
        return dr <= MATCH_DELTA && dg <= MATCH_DELTA && db <= MATCH_DELTA
    }

    private fun snapshot(host: AccessibilityService): List<Win> {
        val list = try {
            host.windows
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        val copy = ArrayList<Win>(list.size)
        for (info in list) {
            val bounds = Rect()
            info.getBoundsInScreen(bounds)
            copy.add(Win(info.id, info.type, bounds, info.isFocused, info.isActive, info.layer))
            info.recycle()
        }
        return copy
    }

    private fun pickApp(wins: List<Win>): Win? {
        val apps = wins.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.bounds.width() > 1 }
        return apps.maxWithOrNull(compareBy<Win> { it.focused }.thenBy { it.active }.thenBy { it.layer })
    }

    internal fun statusBarPixels(context: Context): Int {
        val screenH = context.getSystemService(WindowManager::class.java)?.currentWindowMetrics?.bounds?.height() ?: 0
        return statusBarPx(context, screenH)
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
    )

    private const val TAG = "IslandOverlay"
    private const val MATCH_DELTA = 12
}
