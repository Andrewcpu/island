package dev.islandcn.island

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.animation.PathInterpolator
import android.animation.ValueAnimator
import kotlin.math.min

/**
 * The overlay island. It is a normal Android view, sized to the pill, so the
 * window around it can stay touch-through and unfocused.
 */
class IslandPillView(context: Context) : View(context) {
    var onLayout: ((width: Int, height: Int, expanded: Boolean) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val titlePaint = textPaint(15f, Color.WHITE, true)
    private val trailPaint = textPaint(15f, Color.WHITE, true)
    private val detailPaint = textPaint(13f, 0x99EBEBF5.toInt(), false)
    private val bigPaint = textPaint(22f, Color.WHITE, true)
    private val buttonText = textPaint(13f, Color.WHITE, true)
    private val clip = Path()
    private val body = RectF()
    private val bubble = RectF()
    private val declineHit = RectF()
    private val acceptHit = RectF()
    private val bar = RectF()

    private var kind = "idle"
    private var title = ""
    private var trail = ""
    private var detail = ""
    private var accent = Color.WHITE
    private var bubbleLabel: String? = null
    private var expanded = false
    private var paused = false
    private var progress = 0f
    private var endsAt = 0L
    private var heldMs = 0L
    private var talking = false
    private var micOn = false

    private var curW = dp(96)
    private var curH = dp(37)
    private var animator: ValueAnimator? = null
    private var downAt = 0L
    private var holding = false
    private val expandHold = Runnable {
        holding = false
        if (kind != "idle") expand(true)
    }

    init {
        isClickable = true
        isFocusable = false
        isFocusableInTouchMode = false
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(tick)
        post(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        removeCallbacks(expandHold)
        super.onDetachedFromWindow()
    }

    fun command(name: String, args: Map<String, Any?>) {
        when (name) {
            "outside" -> if (expanded) expand(false)
            "startTimer" -> {
                kind = "timer"
                title = "Timer"
                detail = "5 min timer"
                accent = Color.WHITE
                bubbleLabel = null
                paused = false
                heldMs = 5 * 60 * 1000L
                endsAt = SystemClock.elapsedRealtime() + heldMs
                expand(false)
            }
            "playSample" -> show("music", "Low Tide Radio", "Playing", "Harbor Birds", 0xFF46F0C6.toInt(), false)
            "incomingCall" -> {
                talking = false
                show("call", args["caller"]?.toString()?.ifBlank { null } ?: "Mochi", "Incoming", "Audio call", 0xFF31D259.toInt(), true)
            }
            "acceptCall" -> accept()
            "endCall" -> if (kind == "call") clear()
            "startUpload" -> {
                progress = 0f
                show("upload", "Uploading", "0%", "biscuit-in-the-sun.jpg", 0xFF0A84FF.toInt(), false)
            }
            "startEta" -> {
                val ride = args["kind"]?.toString() != "delivery"
                if (ride) show("ride", "Alex Kim", "6 min", "Blue hatchback · 8YQ 214", 0xFFFF9F0A.toInt(), false)
                else show("delivery", "Delivery", "6 min", "Parcel · Door", 0xFFFF9F0A.toInt(), false)
            }
            "startRecording" -> {
                val recordKind = args["kind"]?.toString() ?: "audio"
                val label = when (recordKind) {
                    "screen" -> "Screen"
                    "camera" -> "Camera"
                    else -> "Voice memo"
                }
                if (recordKind == "audio") {
                    micOn = IslandMonitors.startMic(context)
                    if (micOn) IslandOverlayService.setMic(context, true)
                }
                paused = false
                heldMs = 0L
                endsAt = SystemClock.elapsedRealtime()
                show("recording", label, "0:00", if (paused) "Paused" else "Recording", 0xFFFE4345.toInt(), false)
            }
            "stopRecording" -> if (kind == "recording") clear()
            "toggleRecording" -> if (kind == "recording") togglePause()
            "startTransfer" -> {
                progress = 0f
                val send = args["direction"]?.toString() != "receive"
                show(
                    "transfer",
                    if (send) "Sending" else "Receiving",
                    "0%",
                    "biscuit-in-the-sun.jpg",
                    0xFF46F0C6.toInt(),
                    false,
                )
            }
            "showMessage" -> {
                show(
                    "message",
                    args["sender"]?.toString()?.ifBlank { null } ?: "Sam",
                    "Now",
                    args["body"]?.toString()?.ifBlank { null } ?: "The photos are in.",
                    Color.WHITE,
                    true,
                )
                postDelayed({ if (kind == "message") clear() }, 10_000)
            }
            "pinEvent" -> show("event", "Design review", "15 min", "Studio", 0xFF0A84FF.toInt(), false)
            "showBattery" -> {
                val battery = IslandMonitors.battery(context)
                val level = (battery["level"] as? Number)?.toInt() ?: 0
                val scale = (battery["scale"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 100
                val percent = (level * 100 / scale).coerceIn(0, 100)
                val charging = battery["charging"] == true
                show("battery", if (charging) "Charging" else "Battery", "$percent%", if (charging) "Plugged in" else "Battery", 0xFF53FA6D.toInt(), false)
            }
            "split" -> {
                kind = "timer"
                title = "Timer"
                detail = "5 min timer"
                accent = Color.WHITE
                bubbleLabel = "Music"
                paused = false
                heldMs = 5 * 60 * 1000L
                endsAt = SystemClock.elapsedRealtime() + heldMs
                expand(false)
            }
            "startOrders" -> show("orders", "Order 1042", "12 min", "Preparing", 0xFFFF9F0A.toInt(), false)
            "handoff" -> show("orders", "Order 1043", "On the way", "Out for delivery", 0xFFFF9F0A.toInt(), true)
            "setKeyline", "setReduced", "metrics" -> Unit
        }
        invalidate()
    }

    fun collapse() {
        if (expanded) expand(false)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            collapse()
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downAt = event.eventTime
                holding = true
                removeCallbacks(expandHold)
                postDelayed(expandHold, 220)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(expandHold)
                val tap = holding && event.eventTime - downAt < 220
                holding = false
                if (tap) onTap(event.x, event.y)
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(expandHold)
                holding = false
                invalidate()
                return true
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w < 1f || h < 1f) return
        val showBubble = bubbleLabel != null && h < dp(64)
        val gap = dp(6)
        val bubbleSize = if (showBubble) h else 0f
        val mainW = if (showBubble) (w - gap - bubbleSize).coerceAtLeast(h) else w
        val radius = min(h / 2f, dp(28f))
        val inset = if (holding) dp(1f) else 0f
        body.set(inset, inset, mainW - inset, h - inset)
        canvas.drawRoundRect(body, radius, radius, fill)
        if (showBubble) {
            bubble.set(mainW + gap, inset, w - inset, h - inset)
            canvas.drawRoundRect(bubble, radius, radius, fill)
            drawCenter(canvas, bubbleLabel ?: "", bubble, titlePaint)
        }
        clip.reset()
        clip.addRoundRect(body, radius, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        if (h < dp(64)) drawCompact(canvas, body) else drawExpanded(canvas, body)
        canvas.restore()
    }

    private fun drawCompact(canvas: Canvas, rect: RectF) {
        val liveTrail = liveTrail()
        val pad = dp(14)
        val cy = rect.centerY() - (titlePaint.ascent() + titlePaint.descent()) / 2f
        canvas.drawText(title.ifBlank { "" }, rect.left + pad, cy, titlePaint)
        val trailW = trailPaint.measureText(liveTrail)
        trailPaint.color = accent
        canvas.drawText(liveTrail, rect.right - pad - trailW, cy, trailPaint)
        trailPaint.color = Color.WHITE
    }

    private fun drawExpanded(canvas: Canvas, rect: RectF) {
        val pad = dp(18)
        canvas.drawText(title, rect.left + pad, rect.top + dp(36), bigPaint)
        canvas.drawText(detail, rect.left + pad, rect.top + dp(58), detailPaint)
        if (kind == "call" && !talking) {
            val r = dp(25)
            val cy = rect.bottom - dp(42)
            declineHit.set(rect.centerX() - dp(78) - r, cy - r, rect.centerX() - dp(78) + r, cy + r)
            acceptHit.set(rect.centerX() + dp(78) - r, cy - r, rect.centerX() + dp(78) + r, cy + r)
            fill.color = 0xFFFE4345.toInt()
            canvas.drawOval(declineHit, fill)
            fill.color = 0xFF31D259.toInt()
            canvas.drawOval(acceptHit, fill)
            fill.color = Color.BLACK
            drawCenter(canvas, "Decline", declineHit, buttonText)
            drawCenter(canvas, "Accept", acceptHit, buttonText)
        } else if (kind == "upload" || kind == "transfer") {
            bar.set(rect.left + pad, rect.bottom - dp(28), rect.right - pad, rect.bottom - dp(22))
            fill.color = 0x33FFFFFF
            canvas.drawRoundRect(bar, dp(3f), dp(3f), fill)
            bar.right = bar.left + (rect.width() - pad * 2) * progress.coerceIn(0f, 1f)
            fill.color = accent
            canvas.drawRoundRect(bar, dp(3f), dp(3f), fill)
            fill.color = Color.BLACK
        } else {
            val live = liveTrail()
            trailPaint.color = accent
            canvas.drawText(live, rect.left + pad, rect.top + dp(86), trailPaint)
            trailPaint.color = Color.WHITE
        }
    }

    private fun drawCenter(canvas: Canvas, text: String, rect: RectF, paint: Paint) {
        val x = rect.centerX() - paint.measureText(text) / 2f
        val y = rect.centerY() - (paint.ascent() + paint.descent()) / 2f
        canvas.drawText(text, x, y, paint)
    }

    private fun onTap(x: Float, y: Float) {
        if (expanded && kind == "call" && !talking) {
            if (declineHit.contains(x, y)) {
                clear()
                return
            }
            if (acceptHit.contains(x, y)) {
                accept()
                return
            }
        }
        if (expanded) {
            expand(false)
            return
        }
        when (kind) {
            "idle" -> Unit
            "timer", "music", "recording" -> togglePause()
            "message" -> clear()
            else -> expand(true)
        }
    }

    private fun accept() {
        if (kind != "call") return
        talking = true
        title = title.ifBlank { "Call" }
        detail = "On the call"
        endsAt = SystemClock.elapsedRealtime()
        heldMs = 0L
        expand(false)
    }

    private fun togglePause() {
        paused = !paused
        if (kind == "timer") {
            if (paused) heldMs = (endsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0)
            else endsAt = SystemClock.elapsedRealtime() + heldMs
        } else if (kind == "recording") {
            if (paused) heldMs += SystemClock.elapsedRealtime() - endsAt
            else endsAt = SystemClock.elapsedRealtime()
        } else if (kind == "music") {
            trail = if (paused) "Paused" else "Playing"
        }
        invalidate()
    }

    private fun show(next: String, nextTitle: String, nextTrail: String, nextDetail: String, nextAccent: Int, open: Boolean) {
        if (kind == "recording" && next != "recording") releaseMic()
        kind = next
        title = nextTitle
        trail = nextTrail
        detail = nextDetail
        accent = nextAccent
        bubbleLabel = null
        expand(open)
    }

    private fun clear() {
        releaseMic()
        kind = "idle"
        title = ""
        trail = ""
        detail = ""
        bubbleLabel = null
        progress = 0f
        talking = false
        paused = false
        expand(false)
    }

    private fun releaseMic() {
        if (!micOn) return
        micOn = false
        IslandMonitors.stopMic()
    }

    private fun expand(open: Boolean) {
        expanded = open && kind != "idle"
        val (w, h) = targetSize()
        animate(w, h)
    }

    private fun targetSize(): Pair<Int, Int> {
        if (kind == "idle") return dp(96) to dp(37)
        if (expanded) {
            val height = if (kind == "call" && !talking) dp(156) else dp(132)
            return dp(300) to height
        }
        val text = titlePaint.measureText(title) + trailPaint.measureText(liveTrail()) + dp(44f)
        var width = text.toInt().coerceIn(dp(160), dp(280))
        if (bubbleLabel != null) width += dp(8) + dp(37)
        return width to dp(37)
    }

    private fun animate(targetW: Int, targetH: Int) {
        val fromW = if (curW > 0) curW else targetW
        val fromH = if (curH > 0) curH else targetH
        animator?.cancel()
        if (fromW == targetW && fromH == targetH) {
            curW = targetW
            curH = targetH
            onLayout?.invoke(targetW, targetH, expanded)
            invalidate()
            return
        }
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 280
            interpolator = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
            addUpdateListener { value ->
                val t = value.animatedValue as Float
                curW = (fromW.toFloat() * (1f - t) + targetW.toFloat() * t).toInt().coerceAtLeast(1)
                curH = (fromH.toFloat() * (1f - t) + targetH.toFloat() * t).toInt().coerceAtLeast(1)
                onLayout?.invoke(curW, curH, expanded)
                invalidate()
            }
            start()
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            when (kind) {
                "upload", "transfer" -> {
                    if (progress < 1f) {
                        progress = (progress + 0.04f).coerceAtMost(1f)
                        trail = "${(progress * 100).toInt()}%"
                        if (progress >= 1f) {
                            trail = "Done"
                            detail = "Finished"
                            postDelayed({ if (kind == "upload" || kind == "transfer") clear() }, 1200)
                        }
                    }
                }
                "message" -> {
                    // The alert stays up long enough to read, then the pill goes idle.
                }
            }
            if (kind != "idle") invalidate()
            postDelayed(this, 200)
        }
    }

    private fun liveTrail(): String {
        return when (kind) {
            "timer" -> clock(if (paused) heldMs else (endsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            "recording" -> clock(if (paused) heldMs else heldMs + (SystemClock.elapsedRealtime() - endsAt))
            "call" -> if (talking) clock(SystemClock.elapsedRealtime() - endsAt) else trail
            "music" -> if (paused) "Paused" else trail
            else -> trail
        }
    }

    private fun clock(ms: Long): String {
        val total = (ms / 1000L).toInt()
        return "%d:%02d".format(total / 60, total % 60)
    }

    private fun dp(value: Int): Int = (value * density).toInt().coerceAtLeast(1)

    private fun dp(value: Float): Float = value * density

    private fun textPaint(sizeSp: Float, color: Int, bold: Boolean): Paint {
        return Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = sizeSp * resources.displayMetrics.scaledDensity
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }
}
