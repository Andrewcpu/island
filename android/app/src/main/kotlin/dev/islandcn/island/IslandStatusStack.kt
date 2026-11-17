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
 * Status-bar band behind the island. The sliding mask is the status-bar foreground:
 * clock, icons, and scrim. Wallpaper and app pixels stay transparent.
 * The app-window difference is used when it actually separates those pixels. If that shot
 * is missing or the diff is empty or the whole band, the display band is filtered down to
 * the same foreground instead of skipping the animation or sliding the opaque band.
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
    private val logged = HashSet<String>()
    private var suppressUntil = 0L
    private var nextTry = 0L

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

    /** A hold with nothing to draw must still try a capture, and must say why if it cannot. */
    fun ensure() {
        val now = SystemClock.uptimeMillis()
        if (now < nextTry || frame != null || inflight) return
        if (service == null) {
            logSkip("accessibility service not attached")
            nextTry = now + 2000L
            return
        }
        if (main.hasCallbacks(capture)) return
        schedule(30)
    }

    private fun start() {
        val host = service
        if (host == null) {
            logSkip("accessibility service not attached")
            nextTry = SystemClock.uptimeMillis() + 2000L
            return
        }
        if (!interactive(host)) {
            logSkip("screen is not interactive")
            nextTry = SystemClock.uptimeMillis() + 2000L
            return
        }
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
            logSkip("status bar size ${screen.first}x$status")
            finish(null, looked = true)
            return
        }
        val app = if (Build.VERSION.SDK_INT >= 34) pickApp(snapshot(host)) else null
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
                val waiting = AtomicInteger(if (app != null) 2 else 1)
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
                    if (shot == null) {
                        appShot?.recycle()
                        logSkip("display screenshot missing")
                        main.post { finish(null, looked = true) }
                        return
                    }
                    // A missing app shot still builds a foreground mask. It does not skip the slide.
                    compose(shot, appShot, app, screen.first, status)
                }
                try {
                    shootDisplay(host) { arrive(true, it) }
                    if (app != null) shootWindow(host, app.id) { arrive(false, it) }
                } catch (error: Exception) {
                    Log.w(TAG, "Status capture failed to start", error)
                    shown()
                    logSkip("capture failed to start")
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

    /** One line per distinct reason, so a skipped capture cannot stay silent. */
    private fun logSkip(reason: String) {
        val key = reason.substringBefore(';').substringBefore('(').trim()
        if (!logged.add(key)) return
        Log.w(TAG, "Status slide skipped: $reason")
    }

    private fun logKept(line: String) {
        if (!logged.add("kept")) return
        Log.i(TAG, line)
    }

    private fun interactive(context: Context): Boolean {
        if (IslandMonitors.screenLit()) return true
        return context.getSystemService(android.os.PowerManager::class.java)?.isInteractive == true
    }

    private fun compose(
        display: Bitmap,
        appBmp: Bitmap?,
        app: Win?,
        screenW: Int,
        status: Int,
    ) {
        work.execute {
            var built: Frame? = null
            var report: String? = null
            try {
                // Both shots are already ARGB_8888 copies. Diff those, never the hardware buffers.
                val displayBand = cropTop(display, screenW, status)
                val row = rowBelow(display, screenW, status)
                var mask: Bitmap? = null
                var plate: Bitmap? = null
                var reason = if (app == null) "no app window" else "app screenshot missing"
                if (appBmp != null && app != null) {
                    val appBand = strip(screenW, status)
                    blit(appBand, appBmp, app.bounds, status)
                    val cut = difference(displayBand, appBand)
                    if (cut != null && useful(cut)) {
                        fillHoles(appBand, row)
                        plate = appBand
                        mask = cut.bitmap
                        report = "Status strip diff kept ${cut.kept} of ${cut.total}"
                    } else {
                        reason = when {
                            cut == null -> "app window does not cover the status bar"
                            cut.kept * 5 > cut.total * 4 -> "app diff is the whole band (${cut.kept} of ${cut.total})"
                            else -> "app diff empty (${cut.kept} of ${cut.total})"
                        }
                        cut?.bitmap?.recycle()
                        appBand.recycle()
                    }
                }
                val diffMask = mask
                val diffPlate = plate
                if (diffMask == null || diffPlate == null) {
                    val fg = foreground(displayBand, row)
                    if (fg.kept < 24) {
                        fg.bitmap.recycle()
                        report = "$reason; foreground found nothing"
                    } else {
                        val still = backgroundPlate(screenW, status, row)
                        report = "$reason; foreground kept ${fg.kept} of ${fg.total}"
                        built = Frame(still, fg.bitmap, screenW, status)
                    }
                } else {
                    built = Frame(diffPlate, diffMask, screenW, status)
                }
                displayBand.recycle()
            } catch (error: Exception) {
                Log.w(TAG, "Status stack compose failed", error)
            }
            display.recycle()
            appBmp?.recycle()
            val line = report
            val frame = built
            main.post {
                if (frame == null) nextTry = SystemClock.uptimeMillis() + 2000L
                if (line != null) {
                    when {
                        frame == null -> logSkip(line)
                        line.startsWith("Status strip") -> logKept(line)
                        else -> logKept("Status slide foreground: $line")
                    }
                }
                finish(frame, looked = true)
            }
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
     * Returns null when the app shot does not cover the band.
     */
    private fun difference(display: Bitmap, app: Bitmap): Cut? {
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
        var kept = 0
        for (i in shown.indices) {
            val appPixel = behind[i]
            if (appPixel ushr 24 == 0) {
                shown[i] = 0
                continue
            }
            covered++
            val displayPixel = shown[i]
            if (sameColor(displayPixel, appPixel)) {
                shown[i] = 0
            } else {
                shown[i] = displayPixel or 0xFF000000.toInt()
                kept++
            }
        }
        if (covered * 2 < count) return null
        val out = strip(width, height)
        out.setPixels(shown, 0, width, 0, 0, width, height)
        return Cut(out, kept, count)
    }

    /** A diff that is empty, or that kept the whole band, is not a foreground mask. */
    private fun useful(cut: Cut): Boolean {
        if (cut.kept < 48) return false
        return cut.kept * 5 <= cut.total * 4
    }

    /** Clock, icons, and scrim against the wallpaper row just under the status bar. */
    private fun foreground(band: Bitmap, row: IntArray): Cut {
        val width = band.width
        val height = band.height
        val total = width * height
        val pixels = IntArray(total)
        band.getPixels(pixels, 0, width, 0, 0, width, height)
        var kept = paintForeground(pixels, row, glyphsOnly = false, loose = false)
        if (kept * 5 > total * 4) {
            band.getPixels(pixels, 0, width, 0, 0, width, height)
            kept = paintForeground(pixels, row, glyphsOnly = true, loose = false)
        } else if (kept < 48) {
            band.getPixels(pixels, 0, width, 0, 0, width, height)
            kept = paintForeground(pixels, row, glyphsOnly = false, loose = true)
        }
        val out = strip(width, height)
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return Cut(out, kept, total)
    }

    private fun paintForeground(pixels: IntArray, row: IntArray, glyphsOnly: Boolean, loose: Boolean): Int {
        val glyphDelta = if (loose) 28 else 48
        val glyphLuma = if (loose) 24 else 42
        var kept = 0
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val ref = if (row.isEmpty()) pixel else row[i % row.size]
            val scrim = isScrim(pixel, ref)
            val glyph = isGlyph(pixel, ref, glyphDelta, glyphLuma)
            val color = when {
                glyph -> pixel or 0xFF000000.toInt()
                !glyphsOnly && scrim -> scrimColor(pixel, ref)
                else -> 0
            }
            pixels[i] = color
            if (color != 0) kept++
        }
        return kept
    }

    /** One display row just below the status bar, scaled to the screen width. Wallpaper and app live here. */
    private fun rowBelow(display: Bitmap, screenW: Int, status: Int): IntArray {
        val row = IntArray(screenW)
        if (display.width < 1 || display.height < 1 || screenW < 1) return row
        val y = (status.toFloat() / screenW.coerceAtLeast(1) * display.width).toInt().coerceIn(0, display.height - 1)
        val sample = IntArray(display.width)
        display.getPixels(sample, 0, display.width, 0, y, display.width, 1)
        val scale = display.width.toFloat() / screenW
        for (x in 0 until screenW) {
            val sx = (x * scale).toInt().coerceIn(0, display.width - 1)
            row[x] = sample[sx]
        }
        return row
    }

    private fun backgroundPlate(width: Int, height: Int, row: IntArray): Bitmap {
        val plate = strip(width, height)
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val start = y * width
            for (x in 0 until width) {
                val sample = if (row.isEmpty()) 0 else row[x % row.size]
                pixels[start + x] = sample or 0xFF000000.toInt()
            }
        }
        plate.setPixels(pixels, 0, width, 0, 0, width, height)
        return plate
    }

    private fun fillHoles(plate: Bitmap, row: IntArray) {
        val width = plate.width
        val height = plate.height
        val pixels = IntArray(width * height)
        plate.getPixels(pixels, 0, width, 0, 0, width, height)
        var hole = false
        for (i in pixels.indices) {
            if (pixels[i] ushr 24 == 0) {
                val sample = if (row.isEmpty()) 0 else row[i % row.size]
                pixels[i] = sample or 0xFF000000.toInt()
                hole = true
            }
        }
        if (hole) plate.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun isScrim(pixel: Int, ref: Int): Boolean {
        val dr = channel(ref, 16) - channel(pixel, 16)
        val dg = channel(ref, 8) - channel(pixel, 8)
        val db = channel(ref, 0) - channel(pixel, 0)
        if (dr < 10 || dg < 10 || db < 10) return false
        if (dr > 90 || dg > 90 || db > 90) return false
        return abs(dr - dg) <= 18 && abs(dg - db) <= 18 && abs(dr - db) <= 18
    }

    /** A flat veil. The wallpaper texture underneath is not copied into the sliding image. */
    private fun scrimColor(pixel: Int, ref: Int): Int {
        val drop = (
            (channel(ref, 16) - channel(pixel, 16)) +
                (channel(ref, 8) - channel(pixel, 8)) +
                (channel(ref, 0) - channel(pixel, 0))
            ) / 3
        val gray = drop.coerceIn(16, 140)
        return (0xFF shl 24) or (gray shl 16) or (gray shl 8) or gray
    }

    private fun isGlyph(pixel: Int, ref: Int, maxDeltaMin: Int, lumaMin: Int): Boolean {
        if (isScrim(pixel, ref)) return false
        val dr = abs(channel(pixel, 16) - channel(ref, 16))
        val dg = abs(channel(pixel, 8) - channel(ref, 8))
        val db = abs(channel(pixel, 0) - channel(ref, 0))
        if (max(dr, max(dg, db)) >= maxDeltaMin) return true
        val lumaP = luma(pixel)
        val lumaR = luma(ref)
        if (abs(lumaP - lumaR) >= lumaMin) return true
        if (lumaP >= 214 && lumaR <= 196) return true
        if (lumaP <= 30 && lumaR >= 64) return true
        return false
    }

    private fun channel(color: Int, shift: Int) = (color ushr shift) and 0xFF

    private fun luma(color: Int) = (channel(color, 16) * 77 + channel(color, 8) * 150 + channel(color, 0) * 29) ushr 8

    private fun sameColor(a: Int, b: Int): Boolean {
        val dr = abs(channel(a, 16) - channel(b, 16))
        val dg = abs(channel(a, 8) - channel(b, 8))
        val db = abs(channel(a, 0) - channel(b, 0))
        return dr <= MATCH_DELTA && dg <= MATCH_DELTA && db <= MATCH_DELTA
    }

    private class Cut(val bitmap: Bitmap, val kept: Int, val total: Int)

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
