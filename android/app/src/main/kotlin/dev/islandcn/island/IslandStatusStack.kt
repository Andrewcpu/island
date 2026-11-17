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
 * Prepare app/display pairs while collapsed, when our status band is transparent.
 * Expansion freezes the latest prepared frame before its first draw; collapse uses the
 * same pixels in reverse. Capturing after expansion starts is too late for the spring,
 * and can photograph the growing pill. Never capture while the split is visible.
 * The compact pill stays visible and is excluded from the fallback reconstruction.
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
    private var live = false
    private var registered = false
    private var frame: Frame? = null
    private var loggedShot = false
    private var captureGen = 0
    private var nextShotAt = 0L
    private val logged = HashSet<String>()

    /** takeScreenshot rejects a second call inside this window (ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT). */
    private const val SHOT_GAP_MS = 400L
    private const val PREPARE_GAP_MS = 1000L

    private val prepare = object : Runnable {
        override fun run() {
            val host = service ?: return
            if (!live && !inflight && !IslandOverlayService.stripOpen() && interactive(host) &&
                SystemClock.elapsedRealtime() >= nextShotAt) {
                beginPair(++captureGen)
            }
            main.postDelayed(this, PREPARE_GAP_MS)
        }
    }

    private val clock = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    captureGen++
                    inflight = false
                    live = false
                    if (frame != null) publish(null)
                }
                Intent.ACTION_SCREEN_ON, Intent.ACTION_TIME_TICK -> ensure()
            }
        }
    }

    fun attach(host: IslandAccessibilityService) {
        service = host
        main.removeCallbacks(prepare)
        main.post(prepare)
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
    }

    fun detach(host: IslandAccessibilityService) {
        if (service != host) return
        service = null
        main.removeCallbacks(prepare)
        captureGen++
        inflight = false
        live = false
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
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) ensure()
    }

    /** Refresh the prepared frame without ever sampling our visible status band. */
    fun ensure() {
        if (service == null || live) return
        main.removeCallbacks(prepare)
        main.post(prepare)
    }

    /** Freeze before the opening draw, and keep the frame until collapse has settled. */
    fun tick(active: Boolean) {
        if (active == live) return
        live = active
        // A collapsed capture still in flight might observe the growing pill. Keep the
        // last completed frame instead; never widen its exclusion to the expanded window.
        captureGen++
        inflight = false
        if (active) {
            logged.clear()
        } else {
            ensure()
        }
    }

    private fun beginPair(gen: Int) {
        val host = service
        if (host == null) {
            logSkip("accessibility service not attached")
            return
        }
        if (!interactive(host)) {
            logSkip("screen is not interactive")
            return
        }
        if (gen != captureGen) return
        inflight = true
        val screen = screenSize(host)
        val status = statusBarPx(host, screen.second)
        if (screen.first < 2 || status < 2) {
            logSkip("status bar size ${screen.first}x$status")
            inflight = false
            return
        }
        nextShotAt = SystemClock.elapsedRealtime() + SHOT_GAP_MS
        val windows = if (Build.VERSION.SDK_INT >= 34) snapshot(host) else emptyList()
        val apps = rankApps(windows, screen.first, status)
        val systemBar = windows.firstOrNull {
            val bounds = it.bounds
            it.type == AccessibilityWindowInfo.TYPE_SYSTEM && bounds.left == 0 &&
                bounds.top == 0 && bounds.width() == screen.first &&
                bounds.height() in 1..(status + 8)
        }
        val island = IslandOverlayService.pillBounds()?.apply { inset(-3, -3) }

        fun captureBackground(icons: Bitmap?, index: Int) {
            if (gen != captureGen) {
                icons?.recycle()
                return
            }
            val app = apps.getOrNull(index)
            if (app != null) {
                shootWindow(host, app.id) { bitmap ->
                    val plate = bitmap?.let {
                        val band = strip(screen.first, status)
                        val valid = try {
                            placeAppBand(band, it, app.bounds, status, screen.second)
                        } finally {
                            it.recycle()
                        }
                        if (valid) band else null.also { band.recycle() }
                    }
                    main.post {
                        if (gen != captureGen) {
                            plate?.recycle()
                            icons?.recycle()
                        } else if (plate == null) {
                            captureBackground(icons, index + 1)
                        } else {
                            captureDisplayBackground(null, plate, island, screen.first, status, gen, host)
                        }
                    }
                }
            } else {
                captureDisplayBackground(icons, null, island, screen.first, status, gen, host)
            }
        }

        if (systemBar == null) {
            captureBackground(null, 0)
        } else {
            shootWindow(host, systemBar.id) { bitmap ->
                val icons = bitmap?.let {
                    val band = strip(screen.first, status)
                    blit(band, it, systemBar.bounds, status)
                    it.recycle()
                    val count = covered(band)
                    if (count in 1 until band.width * band.height) band else null.also { band.recycle() }
                }
                main.post {
                    if (icons == null) {
                        captureBackground(null, 0)
                    } else if (gen != captureGen) {
                        icons.recycle()
                    } else {
                        // The app buffer can omit the system bar backdrop or include decor
                        // that is clipped on screen. Use the visible pixels as the plate.
                        captureDisplayBackground(icons, null, island, screen.first, status, gen, host)
                    }
                }
            }
        }
    }

    private fun captureDisplayBackground(
        icons: Bitmap?,
        plate: Bitmap?,
        island: Rect?,
        width: Int,
        status: Int,
        gen: Int,
        host: AccessibilityService,
    ) {
        shootDisplay(host) { bitmap ->
            main.post {
                if (gen != captureGen || bitmap == null) {
                    bitmap?.recycle()
                    icons?.recycle()
                    plate?.recycle()
                    chain(gen)
                    return@post
                }
                compose(bitmap, plate, icons, island, width, status, gen)
            }
        }
    }

    /** One line per reason for this open/close. The next session logs again. */
    private fun logSkip(reason: String) {
        val key = reason.substringBefore(';').substringBefore('(').trim()
        if (!logged.add(key)) return
        Log.w(TAG, "Status slide skipped: $reason")
    }

    private fun logKept(line: String) {
        if (!logged.add("kept")) return
        Log.i(TAG, line)
    }

    private fun chain(gen: Int) {
        if (gen != captureGen) return
        inflight = false
    }

    private fun interactive(context: Context): Boolean {
        if (IslandMonitors.screenLit()) return true
        return context.getSystemService(android.os.PowerManager::class.java)?.isInteractive == true
    }

    private fun compose(
        display: Bitmap,
        appBand: Bitmap?,
        icons: Bitmap?,
        island: Rect?,
        screenW: Int,
        status: Int,
        gen: Int,
    ) {
        work.execute {
            var built: Frame? = null
            var report: String? = null
            try {
                // Both shots are already ARGB_8888 copies. Diff those, never the hardware buffers.
                val displayBand = cropTop(display, screenW, status)
                var mask: Bitmap? = null
                var plate: Bitmap? = null
                if (appBand != null) {
                    plate = appBand
                    copyRect(displayBand, appBand, island)
                    val cut = difference(displayBand, appBand)
                    mask = cut?.bitmap ?: strip(screenW, status)
                    report = "Status strip diff kept ${cut?.kept ?: 0} pixels"
                } else if (icons != null) {
                    // Use SystemUI alpha to locate the original glyphs exactly. Include
                    // the pill in the exclusion so it cannot supply background pixels.
                    val excluded = strip(screenW, status)
                    try {
                        // The display and SystemUI shots are asynchronous. Pad the cutout
                        // to cover antialiasing and small icon shifts between the shots.
                        val padding = (screenW / 180).coerceAtLeast(2)
                        val pixels = IntArray(screenW * status)
                        icons.getPixels(pixels, 0, screenW, 0, 0, screenW, status)
                        val canvas = Canvas(excluded)
                        val paint = Paint()
                        for (y in 0 until status) {
                            var x = 0
                            while (x < screenW) {
                                if (pixels[y * screenW + x] ushr 24 == 0) {
                                    x++
                                    continue
                                }
                                val start = x++
                                while (x < screenW && pixels[y * screenW + x] ushr 24 != 0) x++
                                canvas.drawRect(start - padding.toFloat(), y - padding.toFloat(),
                                    x + padding.toFloat(), y + padding + 1f, paint)
                            }
                        }
                        markExcluded(excluded, island)
                        plate = coverBand(displayBand, excluded)
                    } finally {
                        excluded.recycle()
                    }
                    mask = icons
                    report = "Status strip uses isolated SystemUI and reconstructed background"
                }
                if (plate == null || mask == null) {
                    mask?.recycle()
                    plate?.recycle()
                    // No app window, or its status-bar rows were empty. Slide the display
                    // foreground over a plate filled from those same rows.
                    val reference = rowBelow(display, screenW, status)
                    // The expanded pill can also cover the reference row below the bar.
                    // Borrow a neighbor outside its columns instead of classifying against it.
                    if (island != null) {
                        val left = island.left.coerceIn(0, screenW)
                        val right = island.right.coerceIn(left, screenW)
                        for (x in left until right) {
                            val donor = when {
                                left == 0 -> right.takeIf { it < screenW }
                                right == screenW -> left - 1
                                x - left < right - x -> left - 1
                                else -> right
                            }
                            reference[x] = donor?.let { reference[it] } ?: 0
                        }
                    }
                    val fg = foreground(displayBand, reference)
                    if (fg.kept > 0) {
                        // Exclude the pill BEFORE filling the plate. Clearing only the final
                        // mask leaves pill pixels in the stationary background (and as donors).
                        markExcluded(fg.bitmap, island)
                        val still = coverBand(displayBand, fg.bitmap)
                        plate = still
                        mask = fg.bitmap
                        report = "Status strip foreground kept ${fg.kept} of ${fg.total}"
                    } else {
                        fg.bitmap.recycle()
                        plate = null
                        mask = null
                        report = "foreground found nothing"
                    }
                }
                if (plate != null && mask != null) {
                    if (island != null && mask !== icons) clearRect(mask, island)
                    built = Frame(plate, mask, screenW, status)
                } else {
                    mask?.recycle()
                    plate?.recycle()
                }
                displayBand.recycle()
            } catch (error: Exception) {
                Log.w(TAG, "Status stack compose failed", error)
                report = "compose failed"
            }
            display.recycle()
            if (built == null) {
                appBand?.recycle()
                icons?.recycle()
            }
            val line = report
            val frame = built
            main.post {
                if (gen != captureGen || live) {
                    frame?.windowStrip?.recycle()
                    frame?.mask?.recycle()
                    return@post
                }
                if (line != null) {
                    when {
                        frame == null -> logSkip(line)
                        else -> logKept(line)
                    }
                }
                finish(frame, looked = true)
                chain(gen)
            }
        }
    }

    private fun finish(next: Frame?, looked: Boolean) {
        if (!looked) return
        if (next != null) publish(next)
        inflight = false
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
                    // A shot inside the platform interval is not a failure of the slide. The last frame stays.
                    if (errorCode != AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT && !loggedShot) {
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

    /**
     * Still layer when the app window shot is missing. Masked pixels take the nearest
     * unmasked pixel in the same status-bar row. A row that is entirely foreground borrows
     * another row inside this band. Nothing is read from below the bar.
     */
    private fun coverBand(display: Bitmap, mask: Bitmap): Bitmap {
        val width = display.width
        val height = display.height
        val src = IntArray(width * height)
        val cut = IntArray(width * height)
        display.getPixels(src, 0, width, 0, 0, width, height)
        mask.getPixels(cut, 0, width, 0, 0, width, height)
        val out = IntArray(width * height)
        val rowHas = BooleanArray(height)
        val nearestLeft = IntArray(width)
        for (y in 0 until height) {
            val row = y * width
            var last = -1
            var any = false
            for (x in 0 until width) {
                if (cut[row + x] ushr 24 == 0) {
                    last = x
                    any = true
                }
                nearestLeft[x] = last
            }
            rowHas[y] = any
            last = -1
            for (x in width - 1 downTo 0) {
                val i = row + x
                if (cut[i] ushr 24 == 0) {
                    out[i] = if (src[i] ushr 24 == 0) 0 else src[i] or 0xFF000000.toInt()
                    last = x
                    continue
                }
                val left = nearestLeft[x]
                val right = last
                val pick = when {
                    left < 0 && right < 0 -> -1
                    left < 0 -> right
                    right < 0 -> left
                    x - left <= right - x -> left
                    else -> right
                }
                out[i] = if (pick < 0) 0 else src[row + pick] or 0xFF000000.toInt()
            }
        }
        for (y in 0 until height) {
            if (rowHas[y]) continue
            var donor = -1
            var best = height
            for (yy in 0 until height) {
                if (!rowHas[yy]) continue
                val distance = abs(yy - y)
                if (distance < best) {
                    best = distance
                    donor = yy
                }
            }
            if (donor < 0) continue
            val row = y * width
            val from = donor * width
            for (x in 0 until width) out[row + x] = out[from + x]
        }
        val plate = strip(width, height)
        plate.setPixels(out, 0, width, 0, 0, width, height)
        return plate
    }

    /** Top [status] rows of [src], scaled to the screen width. */
    private fun cropTop(src: Bitmap, screenW: Int, status: Int): Bitmap {
        val out = strip(screenW, status)
        val srcH = (status.toFloat() / screenW.coerceAtLeast(1) * src.width).toInt().coerceIn(1, src.height)
        Canvas(out).drawBitmap(src, Rect(0, 0, src.width, srcH), Rect(0, 0, screenW, status), null)
        return out
    }

    /**
     * App-window pixels that already sit in the status-bar rect. A screen-sized buffer
     * contributes its top rows. A window that only starts below the bar contributes nothing.
     */
    private fun placeAppBand(dest: Bitmap, src: Bitmap, bounds: Rect, status: Int, screenH: Int): Boolean {
        val paint = Paint()
        paint.isFilterBitmap = false
        val screenW = dest.width
        if (fullScreenBuffer(src, screenW, screenH, status) && bounds.width() >= screenW / 2) {
            val srcH = (status.toFloat() / screenW.coerceAtLeast(1) * src.width).toInt().coerceIn(1, src.height)
            Canvas(dest).drawBitmap(src, Rect(0, 0, src.width, srcH), Rect(0, 0, screenW, dest.height), paint)
        } else if (bounds.top < status) {
            blit(dest, src, bounds, status)
        } else {
            return false
        }
        solidify(dest)
        val area = dest.width * dest.height
        return area > 0 && covered(dest) * 4 >= area
    }

    /** Buffer matches the screen, so its top rows are the status bar, not content from below it. */
    private fun fullScreenBuffer(src: Bitmap, screenW: Int, screenH: Int, status: Int): Boolean {
        if (screenW < 2 || screenH <= status || src.width < screenW / 2 || src.height < 2) return false
        val scale = src.width.toFloat() / screenW
        val full = screenH * scale
        val inset = (screenH - status) * scale
        return abs(src.height - full) + 1f < abs(src.height - inset)
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

    /** Nontransparent pixels cannot be donors in coverBand. Cleared before publication. */
    private fun markExcluded(mask: Bitmap, rect: Rect?) {
        if (rect == null) return
        val clipped = Rect(rect)
        if (!clipped.intersect(0, 0, mask.width, mask.height)) return
        Canvas(mask).drawRect(clipped, Paint().apply { color = android.graphics.Color.BLACK })
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
    private fun coversBand(win: Win, bmp: Bitmap, screenW: Int, screenH: Int, status: Int): Boolean {
        if (screenW < 2 || status < 2 || bmp.width < 2 || bmp.height < 2) return false
        if (win.bounds.width() < screenW / 2) return false
        val probe = strip(screenW, status)
        val ok = placeAppBand(probe, bmp, win.bounds, status, screenH)
        probe.recycle()
        return ok
    }

    private fun rankApps(wins: List<Win>, screenW: Int, status: Int): List<Win> {
        return wins.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.bounds.width() > 1 }
            .sortedWith(
                compareByDescending<Win> { it.focused }
                    .thenByDescending { it.active }
                    .thenByDescending { overlapArea(it, screenW, status) }
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
     * Both shots are published even when nothing differs.
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
        var kept = 0
        for (i in shown.indices) {
            val appPixel = behind[i]
            if (appPixel ushr 24 == 0) {
                shown[i] = 0
                continue
            }
            val displayPixel = shown[i]
            if (sameColor(displayPixel, appPixel)) {
                shown[i] = 0
            } else {
                shown[i] = displayPixel or 0xFF000000.toInt()
                kept++
            }
        }
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
