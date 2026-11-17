package dev.islandcn.island

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Status-bar band behind the island. The sliding mask is the status-bar foreground:
 * clock, icons, and scrim. Wallpaper and app pixels stay transparent.
 * The app-window difference is used when it actually separates those pixels. If that shot
 * is missing or the diff is empty or the whole band, the display band is filtered down to
 * the same foreground instead of skipping the animation or sliding the opaque band.
 * The still plate is the app window's own status-bar pixels, drawn first so it covers the
 * real status bar. Only the clock, icons, and scrim slide on top of it.
 * The island window is never hidden for a capture. While the strip is already up, the last
 * frame is kept. The island's own pixels are punched out of the mask.
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
        if (frame != null && IslandOverlayService.stripOpen()) {
            logSkip("keeping the last frame")
            nextTry = now + 2000L
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
        if (screen.first < 2 || status < 2) {
            logSkip("status bar size ${screen.first}x$status")
            finish(null, looked = true)
            return
        }
        val apps = if (Build.VERSION.SDK_INT >= 34) rankApps(snapshot(host), screen.first, status) else emptyList()
        val island = IslandOverlayService.pillBounds()
        var displayBmp: Bitmap? = null
        var appBmp: Bitmap? = null
        var appWin: Win? = null
        var displayDone = false
        var appDone = false
        fun finishPair() {
            if (!displayDone || !appDone) return
            val shot = displayBmp
            val shotApp = appBmp
            val shotWin = appWin
            displayBmp = null
            appBmp = null
            if (!inflight) {
                shot?.recycle()
                shotApp?.recycle()
                return
            }
            if (shot == null) {
                shotApp?.recycle()
                logSkip("display screenshot missing")
                finish(null, looked = true)
                return
            }
            compose(shot, shotApp, shotWin, island, screen.first, status)
        }
        fun shootApps(index: Int) {
            if (index >= apps.size) {
                if (appBmp == null) logSkip("app status-bar band missing")
                appDone = true
                finishPair()
                return
            }
            val win = apps[index]
            try {
                shootWindow(host, win.id) { bmp ->
                    main.post {
                        if (bmp != null && coversBand(win, bmp, screen.first, status)) {
                            appBmp = bmp
                            appWin = win
                            appDone = true
                            finishPair()
                        } else {
                            bmp?.recycle()
                            shootApps(index + 1)
                        }
                    }
                }
            } catch (error: Exception) {
                Log.w(TAG, "Status capture failed to start", error)
                shootApps(index + 1)
            }
        }
        try {
            shootDisplay(host) { bmp ->
                main.post {
                    displayBmp = bmp
                    displayDone = true
                    finishPair()
                }
            }
            shootApps(0)
        } catch (error: Exception) {
            Log.w(TAG, "Status capture failed to start", error)
            logSkip("capture failed to start")
            finish(null, looked = true)
        }
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
        island: Rect?,
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
                    if (placeAppBand(appBand, appBmp, app.bounds, status)) {
                        plate = appBand
                        // The island is in the display shot. Match the plate there so it is not content.
                        copyRect(displayBand, appBand, island)
                        val cut = difference(displayBand, appBand)
                        if (cut != null && useful(cut)) {
                            mask = cut.bitmap
                            report = "Status strip diff kept ${cut.kept} of ${cut.total}"
                        } else {
                            reason = when {
                                cut == null -> "app window does not cover the status bar"
                                cut.kept * 5 > cut.total * 4 -> "app diff is the whole band (${cut.kept} of ${cut.total})"
                                else -> "app diff empty (${cut.kept} of ${cut.total})"
                            }
                            cut?.bitmap?.recycle()
                        }
                    } else {
                        appBand.recycle()
                        reason = "app status-bar band is empty"
                    }
                }
                if (mask == null) {
                    val fg = foreground(displayBand, row)
                    if (fg.kept < 24) {
                        fg.bitmap.recycle()
                        plate?.recycle()
                        plate = null
                        report = "$reason; foreground found nothing"
                    } else {
                        mask = fg.bitmap
                        report = "$reason; foreground kept ${fg.kept} of ${fg.total}"
                    }
                }
                val slide = mask
                if (slide != null && plate != null) {
                    if (island != null) clearRect(slide, island)
                    built = Frame(plate, slide, screenW, status)
                } else {
                    slide?.recycle()
                    plate?.recycle()
                    if (report == null) report = "$reason; no covering app band"
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

    /** App-window pixels in the status-bar rect, made opaque so they cover the real status bar. */
    private fun placeAppBand(dest: Bitmap, src: Bitmap, bounds: Rect, status: Int): Boolean {
        blit(dest, src, bounds, status)
        solidify(dest)
        val area = dest.width * dest.height
        return area > 0 && covered(dest) * 2 >= area
    }

    private fun covered(bitmap: Bitmap): Int {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 1 || height < 1) return 0
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        var count = 0
        for (pixel in pixels) if (pixel ushr 24 != 0) count++
        return count
    }

    private fun solidify(bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        if (width < 1 || height < 1) return
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) {
            if (pixels[i] ushr 24 != 0) pixels[i] = pixels[i] or 0xFF000000.toInt()
        }
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    private fun copyRect(dest: Bitmap, src: Bitmap, rect: Rect?) {
        if (rect == null || dest.width != src.width || dest.height != src.height) return
        val left = rect.left.coerceIn(0, dest.width)
        val right = rect.right.coerceIn(0, dest.width)
        val top = rect.top.coerceIn(0, dest.height)
        val bottom = rect.bottom.coerceIn(0, dest.height)
        if (right <= left || bottom <= top) return
        val width = right - left
        val height = bottom - top
        val pixels = IntArray(width * height)
        src.getPixels(pixels, 0, width, left, top, width, height)
        dest.setPixels(pixels, 0, width, left, top, width, height)
    }

    private fun clearRect(bitmap: Bitmap, rect: Rect) {
        val left = rect.left.coerceIn(0, bitmap.width)
        val right = rect.right.coerceIn(0, bitmap.width)
        val top = rect.top.coerceIn(0, bitmap.height)
        val bottom = rect.bottom.coerceIn(0, bitmap.height)
        if (right <= left || bottom <= top) return
        val width = right - left
        val height = bottom - top
        bitmap.setPixels(IntArray(width * height), 0, width, left, top, width, height)
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
        val paint = Paint()
        paint.isFilterBitmap = false
        canvas.drawBitmap(src, srcRect, dst, paint)
    }

    /** True when this shot's own status-bar pixels cover the band. A lower row is not stretched up. */
    private fun coversBand(win: Win, bmp: Bitmap, screenW: Int, status: Int): Boolean {
        if (screenW < 2 || status < 2 || bmp.width < 2 || bmp.height < 2) return false
        if (win.bounds.top > status / 2 || win.bounds.width() < screenW / 2) return false
        val probe = strip(screenW, status)
        val ok = placeAppBand(probe, bmp, win.bounds, status)
        probe.recycle()
        return ok
    }

    private fun rankApps(wins: List<Win>, screenW: Int, status: Int): List<Win> {
        return wins.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.bounds.width() > 1 }
            .sortedWith(
                compareByDescending<Win> { overlapArea(it, screenW, status) }
                    .thenByDescending { it.bounds.width() * it.bounds.height() },
            )
    }

    private fun overlapArea(win: Win, screenW: Int, status: Int): Int {
        val rect = Rect(win.bounds)
        if (!rect.intersect(0, 0, screenW, status)) return 0
        return rect.width() * rect.height()
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

    /**
     * One display row just under the status bar, used only to tell glyphs and scrim from wallpaper.
     * It is not drawn. The still plate is the app window's status-bar pixels.
     */
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
