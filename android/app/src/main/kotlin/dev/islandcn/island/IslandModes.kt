package dev.islandcn.island

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Each islandcn preset, at the sizes in its activity file. Compact content is drawn
 * in the ear slots; expanded content uses that preset's measured row.
 */
internal object IslandModes {
    class Hits {
        var decline = DRect(0.0, 0.0, 0.0, 0.0)
        var accept = DRect(0.0, 0.0, 0.0, 0.0)
        var pause = DRect(0.0, 0.0, 0.0, 0.0)
        var cancel = DRect(0.0, 0.0, 0.0, 0.0)
        var stop = DRect(0.0, 0.0, 0.0, 0.0)
        var toggle = DRect(0.0, 0.0, 0.0, 0.0)
        var previous = DRect(0.0, 0.0, 0.0, 0.0)
        var next = DRect(0.0, 0.0, 0.0, 0.0)
        var like = DRect(0.0, 0.0, 0.0, 0.0)
        var slider = DRect(0.0, 0.0, 0.0, 0.0)

        fun clear() {
            decline = DRect(0.0, 0.0, 0.0, 0.0)
            accept = decline
            pause = decline
            cancel = decline
            stop = decline
            toggle = decline
            previous = decline
            next = decline
            like = decline
            slider = decline
        }
    }

    private val compact = text(15f, 600, -0.0f)
    private val title = text(17f, 600, -0.045f)
    private val titleRegular = text(17f, 400, -0.045f)
    private val subtitle = text(15f, 400, 0f)
    private val caption = text(13f, 500, 0f)
    private val value = text(34f, 700, -0.09f)
    private val small = text(11f, 400, 0f)
    private val player = text(16f, 600, 0f)
    private val playerSub = text(16f, 400, 0f)
    private val time22 = text(22f, 400, -0.11f)
    private val label14 = text(14f, 400, 0f)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val faceMask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = Color.WHITE
    }
    private val faceGlow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val faceIn = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
    private var phoneMissed: Drawable? = null
    private var chatBubble: Drawable? = null

    /** Material Icons filled: phone_missed and chat_bubble. */
    fun install(context: Context) {
        if (phoneMissed != null) return
        phoneMissed = ContextCompat.getDrawable(context, R.drawable.ic_phone_missed)?.mutate()
        chatBubble = ContextCompat.getDrawable(context, R.drawable.ic_chat_bubble)?.mutate()
    }

    fun draw(
        canvas: Canvas,
        id: String,
        titleText: String,
        detail: String,
        valueText: String,
        trail: String,
        accent: Int,
        progress: Float,
        glyph: String,
        elapsed: String,
        remaining: String,
        kind: Kind,
        pill: DRect,
        hole: DRect,
        leading: DRect?,
        trailing: DRect?,
        now: Double,
        hits: Hits,
        alpha: Int,
        art: Bitmap? = null,
        playing: Boolean = false,
        icon: Bitmap? = null,
        liked: Boolean? = null,
    ) {
        hits.clear()
        // Authenticated, lock screen still up. While the card is still tall the
        // shackle plays on the large padlock; the compact ear takes over as it shrinks.
        if (id == "lock" && progress >= 0f && pill.height > 62.0) {
            unlockingPadlock(canvas, pill, hole, progress, alpha)
            return
        }
        if (kind == Kind.EXPANDED) {
            drawExpanded(canvas, id, titleText, detail, valueText, accent, progress, glyph, elapsed, remaining, pill, hole, now, hits, alpha, art, playing, icon, liked)
        } else if (id == "live") {
            liveCompact(canvas, pill, hole, icon, now, alpha)
        } else if ((id == "battery" || id == "low-battery") && kind == Kind.COMPACT) {
            val charging = id == "battery" && (titleText == "Charging" || titleText == "Charged")
            batteryCompact(canvas, pill, hole, valueText, accent, charging, alpha)
        } else {
            drawCompact(canvas, id, titleText, detail, valueText, trail, accent, progress, leading, trailing, now, alpha, art, playing, icon)
        }
    }

    /** Expanded height for a message whose body wraps past one line. */
    fun messageBody(text: String): Double {
        val lines = wrapLines(text, subtitle, 230f, 6).size.coerceAtLeast(1)
        return (48.0 + (lines - 1) * 18.0).coerceIn(48.0, 140.0)
    }

    /**
     * The compact ear's words: a short status, title, or category. The longer body stays on the
     * expanded card, so a narrow ear never has to cut "Declining spam call" down to an ellipsis.
     */
    fun compactCaption(title: String, detail: String, value: String, trail: String): String {
        fun clean(raw: String) = raw.trim().lineSequence().firstOrNull().orEmpty().trim()
        val status = clean(value).ifEmpty { clean(trail) }
        if (status.isNotEmpty() && status.length <= 18) return status
        val head = clean(title)
        val body = clean(detail)
        if (head.isNotEmpty() && head.length <= 18 && !head.equals(body, ignoreCase = true)) return head
        if (head.isNotEmpty()) {
            val word = head.substringBefore(' ')
            if (word.isNotEmpty() && word.length <= 18) return word
        }
        return ""
    }

    /** Width of [compactCaption], plus a little so the last glyph is not clipped. */
    fun compactTextWidth(caption: String): Double {
        if (caption.isEmpty()) return 0.0
        return (compact.measureText(caption) + 4f).toDouble().coerceAtMost(72.0)
    }

    /**
     * Expanded card height: the content's bottom edge plus the same bottom padding, never a fixed
     * tall body. Two-line rows stay near the 84 minimum. Players and rides keep the room they draw.
     */
    fun expandedHeight(id: String?, body: Double): Double {
        val pad = 18.35
        val end = when (id) {
            "now-playing" -> 160.0
            "eta" -> 146.0
            "message" -> 76.0 + (body - 48.0).coerceAtLeast(0.0)
            "timer", "timer-done" -> 80.0
            "lock" -> 176.0
            "torch" -> 100.0
            "low-battery" -> 86.0
            else -> 70.0
        }
        val height = end + pad
        return if (id == "lock") height.coerceIn(84.0, 210.0) else height.coerceIn(84.0, 180.0)
    }

    fun drawBubble(canvas: Canvas, id: String, accent: Int, progress: Float, bubble: DRect, now: Double, alpha: Int, art: Bitmap? = null, mark: String = "", playing: Boolean = false, icon: Bitmap? = null, charge: String = "", charging: Boolean = false) {
        val lead = DRect(bubble.left + 6.42, bubble.centerY - 11, bubble.left + 28.42, bubble.centerY + 11)
        if (id == "call" && mark == "missed") {
            glyph(canvas, "missed", lead, 0xFFFF3B30.toInt(), alpha)
            return
        }
        if (icon != null) {
            cover(canvas, icon, lead, 6f, alpha)
            return
        }
        when (id) {
            "timer", "timer-done" -> ring(canvas, lead, progress.coerceIn(0f, 1f), accent, tint(accent, 0.25f), 2.4f, alpha)
            "now-playing" -> {
                cover(canvas, art, lead, 6f, alpha)
                meter(canvas, DRect(bubble.right - 28, bubble.centerY - 8.5, bubble.right - 7.2, bubble.centerY + 8.5), accent, 4, 2.4f, 1.9f, musicClock(now, playing), alpha, playing = true)
            }
            "upload", "transfer" -> ring(canvas, DRect(bubble.centerX - 10, bubble.centerY - 10, bubble.centerX + 10, bubble.centerY + 10), progress, accent, 0x29FFFFFF, 2.6f, alpha)
            "recording" -> meter(canvas, DRect(bubble.centerX - 11, bubble.centerY - 8, bubble.centerX + 11, bubble.centerY + 8), accent, 5, 2f, 2f, now, alpha, playing = true)
            "battery" -> batteryGlyph(canvas, bubble, charge, charging, accent, alpha)
            "low-battery" -> batteryGlyph(canvas, bubble, charge, false, accent, alpha)
            "lock" -> if (progress >= 0f) padlockIn(canvas, lead, progress, alpha) else glyph(canvas, "lock", lead, Color.WHITE, alpha)
            "torch" -> flashlightIcon(canvas, lead, progress, alpha)
            else -> glyph(canvas, bubbleGlyph(id, accent), lead, accent, alpha)
        }
    }

    private fun drawCompact(
        canvas: Canvas,
        id: String,
        titleText: String,
        detail: String,
        valueText: String,
        trail: String,
        accent: Int,
        progress: Float,
        leading: DRect?,
        trailing: DRect?,
        now: Double,
        alpha: Int,
        art: Bitmap?,
        playing: Boolean,
        icon: Bitmap? = null,
    ) {
        val lead = leading ?: return
        val trailRect = trailing
        if (id == "call" && valueText == "Missed") {
            glyph(canvas, "missed", lead, 0xFFFF3B30.toInt(), alpha)
            return
        }
        if (icon != null) {
            cover(canvas, icon, lead, 6f, alpha)
            val caption = if (id == "upload" || id == "transfer" || id == "live" || id == "now-playing") "" else compactCaption(titleText, detail, valueText, trail)
            if (trailRect != null && caption.isNotEmpty()) textIn(canvas, caption, compact, if (id == "timer" || id == "timer-done") accent else Color.WHITE, trailRect, true, alpha)
            if ((id == "upload" || id == "transfer") && trailRect != null) ring(canvas, trailRect, progress, accent, 0x29FFFFFF, 2.6f, alpha, if (id == "upload") valueText.removeSuffix("%") else null)
            return
        }
        when (id) {
            "timer", "timer-done" -> {
                glyph(canvas, "timer", lead, accent, alpha)
                textIn(canvas, trail, compact, accent, trailRect, true, alpha)
            }
            "now-playing" -> {
                cover(canvas, art, lead, 6f, alpha)
                if (trailRect != null) meter(canvas, trailRect, accent, 6, 2.4f, 1.9f, musicClock(now, playing), alpha, true)
            }
            "lock" -> if (progress >= 0f) padlockIn(canvas, lead, progress, alpha) else glyph(canvas, "lock", lead, Color.WHITE, alpha)
            "torch" -> flashlightIcon(canvas, lead, progress, alpha)
            "call" -> {
                if (valueText == "Ring") {
                    avatar(canvas, titleText, lead, alpha)
                    glyph(canvas, "phone", trailRect, 0xFF31D259.toInt(), alpha)
                } else {
                    glyph(canvas, "phone", DRect(lead.left, lead.centerY - 9, lead.left + 18, lead.centerY + 9), 0xFF76FB73.toInt(), alpha)
                    textIn(canvas, valueText, compact, 0xFF76FB73.toInt(), DRect(lead.left + 20.5, lead.top, lead.right, lead.bottom), false, alpha)
                    if (trailRect != null) meter(canvas, trailRect, 0xFF76FB73.toInt(), 8, 2.25f, 1.13f, now, alpha, true, twoTone = true)
                }
            }
            "battery" -> {
                textIn(canvas, titleText, compact, Color.WHITE, lead, false, alpha)
                if (trailRect != null) batteryRow(canvas, trailRect, valueText, accent, titleText == "Charging" || titleText == "Charged", alpha)
            }
            "low-battery" -> {
                textIn(canvas, valueText, compact, accent, lead, false, alpha)
                if (trailRect != null) batteryGlyph(canvas, trailRect, valueText, false, accent, alpha, inset = 1.0)
            }
            "eta" -> {
                glyph(canvas, if (accent == 0xFFFFD60A.toInt()) "box" else "car", lead, accent, alpha)
                textIn(canvas, trail, subtitle, accent, trailRect, true, alpha)
            }
            "recording" -> {
                if (titleText == "Voice memo") meter(canvas, lead, accent, 13, 1.4f, 1.35f, now, alpha, detail != "Paused", envelope = true)
                else dot(canvas, lead, accent, detail != "Paused", alpha)
                textIn(canvas, trail, subtitle, if (detail == "Paused") 0x9EEBEBF5.toInt() else accent, trailRect, true, alpha)
            }
            "upload" -> {
                thumb(canvas, lead, accent, alpha)
                if (trailRect != null) ring(canvas, trailRect, progress, accent, 0x29FFFFFF, 2.6f, alpha, valueText.removeSuffix("%"))
            }
            "transfer" -> {
                glyph(canvas, "phone", lead, accent, alpha)
                if (trailRect != null) ring(canvas, trailRect, progress, accent, 0x29FFFFFF, 2.6f, alpha)
            }
            "message" -> {
                avatar(canvas, titleText, lead, alpha)
                glyph(canvas, "message", trailRect, Color.WHITE, alpha)
            }
            "calendar" -> {
                glyph(canvas, "event", lead, accent, alpha)
                textIn(canvas, trail, compact, Color.WHITE, trailRect, true, alpha)
            }
            "order-a", "order-b" -> {
                glyph(canvas, "box", lead, accent, alpha)
                textIn(canvas, trail, compact, accent, trailRect, true, alpha)
            }
            else -> {
                val caption = compactCaption(titleText, detail, valueText, trail)
                textIn(canvas, caption, compact, Color.WHITE, lead, false, alpha)
                if (trail.isNotEmpty() && trail != caption) textIn(canvas, trail, compact, accent, trailRect, true, alpha)
            }
        }
    }

    private fun drawExpanded(
        canvas: Canvas,
        id: String,
        titleText: String,
        detail: String,
        valueText: String,
        accent: Int,
        progress: Float,
        glyph: String,
        elapsed: String,
        remaining: String,
        pill: DRect,
        hole: DRect,
        now: Double,
        hits: Hits,
        alpha: Int,
        art: Bitmap?,
        playing: Boolean,
        icon: Bitmap?,
        liked: Boolean?,
    ) {
        val contentTop = pill.top + 18.18
        val right = pill.right - 17.81
        when (id) {
            "timer", "timer-done" -> timerExpanded(canvas, pill, contentTop, right, titleText, valueText, accent, detail == "Paused", hits, alpha)
            "now-playing" -> playerExpanded(canvas, pill, hole, titleText, detail, accent, playing, progress, elapsed, remaining, now, hits, alpha, art, liked)
            "call" -> callExpanded(canvas, pill, titleText, detail, valueText, hits, alpha, icon)
            "battery" -> batteryExpanded(canvas, pill, titleText, detail, valueText, accent, alpha)
            "low-battery" -> lowBatteryExpanded(canvas, pill, valueText, detail, accent, alpha)
            "eta" -> etaExpanded(canvas, pill, titleText, detail, valueText, accent, alpha, icon)
            "recording" -> recordingExpanded(canvas, pill, titleText, valueText, accent, detail != "Paused", hits, now, alpha)
            "upload", "transfer" -> mediaExpanded(canvas, pill, id, glyph, titleText, detail, valueText, accent, progress, alpha, icon)
            "message" -> personExpanded(canvas, pill, titleText, detail, alpha, icon)
            "live" -> liveExpanded(canvas, pill, hole, titleText, detail, alpha, icon)
            "calendar" -> eventExpanded(canvas, pill, titleText, detail, valueText, accent, alpha, icon)
            "order-a", "order-b" -> eventExpanded(canvas, pill, titleText, detail, valueText, accent, alpha, icon)
            "lock" -> if (progress >= 0f) unlockingPadlock(canvas, pill, hole, progress, alpha) else faceIdMark(canvas, pill, hole, now, alpha)
            "torch" -> torchExpanded(canvas, pill, hole, progress, hits, alpha)
            else -> personExpanded(canvas, pill, titleText, detail, alpha, icon)
        }
    }

    private fun timerExpanded(canvas: Canvas, pill: DRect, top: Double, right: Double, label: String, time: String, accent: Int, paused: Boolean, hits: Hits, alpha: Int) {
        val cy = top + 25
        val color = if (paused) 0x9EEBEBF5.toInt() else accent
        hits.pause = DRect(pill.left + 18.5, cy - 24, pill.left + 66.5, cy + 24)
        hits.cancel = DRect(hits.pause.right + 10, cy - 24, hits.pause.right + 58, cy + 24)
        circle(canvas, hits.pause, tint(accent, 0.3f), alpha)
        glyph(canvas, if (paused) "play" else "pause", hits.pause, accent, alpha)
        circle(canvas, hits.cancel, 0x24FFFFFF, alpha)
        glyph(canvas, "close", hits.cancel, Color.WHITE, alpha)
        caption.color = color
        caption.alpha = alpha
        val cap = if (paused) "Paused" else label
        canvas.drawText(cap, (right - 8 - caption.measureText(cap)).toFloat(), (top + 16).toFloat(), caption)
        value.color = color
        value.alpha = alpha
        canvas.drawText(time, (right - value.measureText(time)).toFloat(), (top + 18 + 30).toFloat(), value)
    }

    private fun playerExpanded(canvas: Canvas, pill: DRect, hole: DRect, titleText: String, artist: String, accent: Int, playing: Boolean, fraction: Float, elapsed: String, remaining: String, now: Double, hits: Hits, alpha: Int, art: Bitmap?, liked: Boolean?) {
        var artBox = DRect(pill.left + 21.7, pill.top + 21.9, pill.left + 21.7 + 59.5, pill.top + 21.9 + 59.5)
        if (artBox.overlaps(hole)) {
            val nudge = hole.left - 8.0 - artBox.right
            if (artBox.left + nudge >= pill.left + 8.0) {
                artBox = DRect(artBox.left + nudge, artBox.top, artBox.right + nudge, artBox.bottom)
            }
        }
        cover(canvas, art, artBox, 14f, alpha)
        // Below the punch hole the title and artist span the island. The cutout only
        // masks glyphs whose ink actually crosses it.
        val textLeft = pill.left + 98.0
        val textRight = pill.right - 17.81
        val meter = DRect(pill.right - 26 - 24, pill.top + 26.3, pill.right - 26, pill.top + 46.3)
        player.color = Color.WHITE
        player.alpha = alpha
        playerSub.color = 0xFF898989.toInt()
        playerSub.alpha = alpha
        trackLine(canvas, titleText, player, textLeft, textRight, pill.top + 47.3, meter)
        trackLine(canvas, artist, playerSub, textLeft, textRight, pill.top + 64.4, meter)
        meter(canvas, meter, accent, 6, 2.4f, 1.9f, musicClock(now, playing), alpha, true)
        val barTop = pill.top + 102.7
        small.color = 0xFF898989.toInt()
        small.alpha = alpha
        val elapsedLabel = elapsed.ifEmpty { "0:00" }
        val remainingLabel = remaining.ifEmpty { "−0:00" }
        canvas.drawText(elapsedLabel, (pill.left + 22.4).toFloat(), (barTop + 4).toFloat(), small)
        val bar = RectF((pill.left + 55.4).toFloat(), (barTop - 3).toFloat(), (pill.right - 60).toFloat(), (barTop + 3).toFloat())
        fill.color = 0x33FFFFFF
        fill.alpha = alpha
        canvas.drawRect(bar, fill)
        bar.right = bar.left + bar.width() * fraction.coerceIn(0f, 1f)
        fill.color = Color.WHITE
        canvas.drawRect(bar, fill)
        canvas.drawText(remainingLabel, (pill.right - 24 - small.measureText(remainingLabel)).toFloat(), (barTop + 4).toFloat(), small)
        val buttonY = pill.top + 142
        hits.toggle = DRect(pill.centerX - 18, buttonY - 18, pill.centerX + 18, buttonY + 18)
        hits.previous = DRect(pill.centerX - 73.5 - 20, buttonY - 20, pill.centerX - 73.5 + 20, buttonY + 20)
        hits.next = DRect(pill.centerX + 73.5 - 20, buttonY - 20, pill.centerX + 73.5 + 20, buttonY + 20)
        musicGlyph(canvas, if (playing) "pause" else "play", pill.centerX, buttonY, 30.0, Color.WHITE, alpha)
        musicGlyph(canvas, "back", pill.centerX - 73.5, buttonY, 34.0, Color.WHITE, alpha)
        musicGlyph(canvas, "forward", pill.centerX + 73.5, buttonY, 34.0, Color.WHITE, alpha)
        if (liked != null) {
            val heartX = pill.right - 34.0
            hits.like = DRect(heartX - 18, buttonY - 18, heartX + 18, buttonY + 18)
            heart(canvas, heartX, buttonY, 22.0, liked, alpha)
        }
    }

    /** Filled when the track is liked (the action is Unlike), outline when the action is Like. */
    private fun heart(canvas: Canvas, cx: Double, cy: Double, size: Double, filled: Boolean, alpha: Int) {
        val s = size.toFloat()
        val x = cx.toFloat()
        val y = cy.toFloat()
        val path = android.graphics.Path()
        path.moveTo(x, y + s * 0.36f)
        path.cubicTo(x - s * 0.64f, y + s * 0.02f, x - s * 0.4f, y - s * 0.46f, x, y - s * 0.14f)
        path.cubicTo(x + s * 0.4f, y - s * 0.46f, x + s * 0.64f, y + s * 0.02f, x, y + s * 0.36f)
        path.close()
        if (filled) {
            fill.color = Color.WHITE
            fill.alpha = alpha
            canvas.drawPath(path, fill)
            return
        }
        stroke.color = Color.WHITE
        stroke.alpha = alpha
        stroke.strokeWidth = (s * 0.09f).coerceAtLeast(1.4f)
        canvas.drawPath(path, stroke)
    }

    /** The line uses the island width. It steps aside for [avoid] only when the glyphs run into it. */
    private fun trackLine(canvas: Canvas, text: String, paint: Paint, left: Double, right: Double, baseline: Double, avoid: DRect) {
        val top = baseline + paint.ascent()
        val bottom = baseline + paint.descent()
        val span = DRect(left, top.toDouble(), right, bottom.toDouble())
        val limit = if (span.overlaps(avoid)) min(right, avoid.left - 6.0) else right
        val shown = ellipsize(text, paint, limit - left)
        if (shown.isEmpty()) return
        canvas.drawText(shown, left.toFloat(), baseline.toFloat(), paint)
    }

    /** Play and pause at 30pt, previous and next at 34pt. Even filled shapes, not stroked doodles. */
    private fun musicGlyph(canvas: Canvas, name: String, cx: Double, cy: Double, box: Double, color: Int, alpha: Int) {
        fill.color = color
        fill.alpha = alpha
        val x = cx.toFloat()
        val y = cy.toFloat()
        val scale = box.toFloat()
        when (name) {
            "pause" -> {
                val h = scale * 27.6f / 30f
                val w = scale * 5.2f / 30f
                val gap = scale * 4.4f / 30f
                val top = y - h / 2f
                canvas.drawRoundRect(RectF(x - gap / 2f - w, top, x - gap / 2f, top + h), w / 2f, w / 2f, fill)
                canvas.drawRoundRect(RectF(x + gap / 2f, top, x + gap / 2f + w, top + h), w / 2f, w / 2f, fill)
            }
            "play" -> {
                val h = scale * 27.6f / 30f
                val w = scale * 22f / 30f
                val left = x - w / 3f
                val path = android.graphics.Path()
                path.moveTo(left, y - h / 2f)
                path.lineTo(left + w, y)
                path.lineTo(left, y + h / 2f)
                path.close()
                canvas.drawPath(path, fill)
            }
            else -> {
                val pointingRight = name != "back"
                val h = scale * 20f / 34f
                val w = scale * 15.75f / 34f
                val origin = x - scale * 31.5f / 34f / 2f
                fun triangle(edge: Float) {
                    val path = android.graphics.Path()
                    if (pointingRight) {
                        path.moveTo(edge, y - h / 2f)
                        path.lineTo(edge + w, y)
                        path.lineTo(edge, y + h / 2f)
                    } else {
                        path.moveTo(edge + w, y - h / 2f)
                        path.lineTo(edge, y)
                        path.lineTo(edge + w, y + h / 2f)
                    }
                    path.close()
                    canvas.drawPath(path, fill)
                }
                triangle(origin)
                triangle(origin + w)
            }
        }
    }

    private fun callExpanded(canvas: Canvas, pill: DRect, caller: String, detail: String, valueText: String, hits: Hits, alpha: Int, icon: Bitmap?) {
        val name = caller.ifBlank { "Incoming call" }
        val avatar = DRect(pill.left + 16.3, pill.top + 16.8, pill.left + 66.3, pill.top + 66.8)
        if (icon != null) cover(canvas, icon, avatar, 14f, alpha) else avatar(canvas, name, avatar, alpha)
        val cy = pill.top + 41.8
        val edge = 16.1
        val button = 50.0
        if (valueText == "Ring") {
            hits.accept = DRect(pill.right - edge - button, cy - 25, pill.right - edge, cy + 25)
            hits.decline = DRect(hits.accept.left - 10.5 - button, cy - 25, hits.accept.left - 10.5, cy + 25)
            circle(canvas, hits.decline, 0xFFFE4345.toInt(), alpha)
            circle(canvas, hits.accept, 0xFF31D259.toInt(), alpha)
            glyph(canvas, "hangup", hits.decline, Color.WHITE, alpha)
            glyph(canvas, "phone", hits.accept, Color.WHITE, alpha)
        } else {
            hits.decline = DRect(pill.right - edge - button, cy - 25, pill.right - edge, cy + 25)
            circle(canvas, hits.decline, 0xFFFE4345.toInt(), alpha)
            glyph(canvas, "hangup", hits.decline, Color.WHITE, alpha)
            meter(canvas, DRect(hits.decline.left - 48, cy - 9, hits.decline.left - 8, cy + 9), 0xFF76FB73.toInt(), 8, 2.25f, 1.13f, 0.0, alpha, true, twoTone = true)
        }
        val textLeft = pill.left + 76.5
        val block = if (valueText == "Ring") hits.decline.left else hits.decline.left - 48.0
        val textLimit = max(block - 12.0, textLeft + 72.0)
        label14.color = if (valueText == "Ring") 0x9EEBEBF5.toInt() else 0xFF76FB73.toInt()
        label14.alpha = alpha
        val line = if (valueText == "Ring") detail.ifBlank { "Audio call" } else valueText
        canvas.drawText(ellipsize(line, label14, textLimit - textLeft), textLeft.toFloat(), (pill.top + 47.7).toFloat(), label14)
        title.color = Color.WHITE
        title.alpha = alpha
        canvas.drawText(ellipsize(name, title, textLimit - textLeft), textLeft.toFloat(), (pill.top + 66.2).toFloat(), title)
    }

    private fun batteryExpanded(canvas: Canvas, pill: DRect, label: String, detail: String, percent: String, accent: Int, alpha: Int) {
        val mid = pill.centerY
        compact.color = Color.WHITE
        compact.alpha = alpha
        canvas.drawText(label, (pill.left + 18.5).toFloat(), (mid - 8).toFloat(), compact)
        if (detail.isNotEmpty()) {
            caption.color = 0x9EEBEBF5.toInt()
            caption.alpha = alpha
            canvas.drawText(detail, (pill.left + 18.5).toFloat(), (mid + 12).toFloat(), caption)
        }
        val charging = label == "Charging" || label == "Charged"
        val color = if (charging) 0xFF53FA6D.toInt() else accent
        compact.color = color
        canvas.drawText(percent, (pill.right - 18.5 - 35.5 - 5.7 - compact.measureText(percent)).toFloat(), (mid + 5).toFloat(), compact)
        batteryIcon(canvas, pill.right - 18.5 - 35.5, mid - 8.3, 35.5, percent, if (charging) 0xFF63FF39.toInt() else accent, charging, alpha)
    }

    /** Low battery: the charging glyph filled to the percent left, plus the saver message. */
    private fun lowBatteryExpanded(canvas: Canvas, pill: DRect, percent: String, message: String, accent: Int, alpha: Int) {
        val glyph = DRect(pill.left + 20.0, pill.top + 16.0, pill.left + 64.0, pill.top + 48.0)
        batteryGlyph(canvas, glyph, percent, false, accent, alpha, inset = 0.0)
        val textLeft = glyph.right + 12.0
        title.color = accent
        title.alpha = alpha
        canvas.drawText(percent, textLeft.toFloat(), (pill.top + 40.0).toFloat(), title)
        caption.color = 0x9EEBEBF5.toInt()
        caption.alpha = alpha
        val width = (pill.right - 20.0 - (pill.left + 20.0)).toFloat()
        val lines = wrapLines(message.ifBlank { "Battery is low" }, caption, width, 2)
        var y = pill.top + 66.0
        for (line in lines) {
            canvas.drawText(line, (pill.left + 20.0).toFloat(), y.toFloat(), caption)
            y += 16.0
        }
    }

    private fun etaExpanded(canvas: Canvas, pill: DRect, name: String, plate: String, minutes: String, accent: Int, alpha: Int, icon: Bitmap?) {
        val glyphBox = DRect(pill.left + 21.5, pill.top + 22, pill.left + 65, pill.top + 64)
        if (icon != null) cover(canvas, icon, glyphBox, 12f, alpha) else glyph(canvas, if (accent == 0xFFFFD60A.toInt()) "box" else "car", glyphBox, accent, alpha)
        val right = pill.right - 25.5
        titleRegular.color = Color.WHITE
        titleRegular.alpha = alpha
        val nameW = titleRegular.measureText(name)
        canvas.drawText(name, (right - nameW).toFloat(), (pill.top + 40).toFloat(), titleRegular)
        val vehicle = if (plate.contains("·")) plate.substringBefore("·").trim() else plate
        val plateOnly = if (plate.contains("·")) plate.substringAfter("·").trim() else ""
        if (plateOnly.isNotEmpty()) {
            subtitle.color = 0x9EEBEBF5.toInt()
            subtitle.alpha = alpha
            canvas.drawText(plateOnly, (right - subtitle.measureText(plateOnly)).toFloat(), (pill.top + 60).toFloat(), subtitle)
        }
        val rowY = pill.top + 86
        titleRegular.color = Color.WHITE
        canvas.drawText(vehicle, (pill.left + 21.5).toFloat(), rowY.toFloat(), titleRegular)
        val arrival = "Arriving in $minutes"
        canvas.drawText(arrival, (right - titleRegular.measureText(arrival)).toFloat(), rowY.toFloat(), titleRegular)
        val cap = DRect(pill.left + 20.8, pill.bottom - 20.8 - 44, pill.right - 20.8, pill.bottom - 20.8)
        fill.color = tint(accent, 0.27f)
        fill.alpha = alpha
        canvas.drawRoundRect(cap.toRect(), 22f, 22f, fill)
        val words = "Message ${name.substringBefore(' ')}"
        title.color = accent
        title.alpha = alpha
        canvas.drawText(words, (cap.centerX - title.measureText(words) / 2).toFloat(), (cap.centerY - (title.ascent() + title.descent()) / 2).toFloat(), title)
    }

    private fun recordingExpanded(canvas: Canvas, pill: DRect, name: String, time: String, accent: Int, live: Boolean, hits: Hits, now: Double, alpha: Int) {
        val color = if (live) accent else 0x9EEBEBF5.toInt()
        val wave = DRect(pill.left + 18.5, pill.centerY - 20, pill.left + 18.5 + 137, pill.centerY + 20)
        if (name == "Voice memo") meter(canvas, wave, color, 15, 2.9f, 2.4f, now, alpha, live, envelope = true)
        else {
            glyph(canvas, if (name == "Camera") "camera" else "screen", DRect(wave.left, wave.centerY - 11, wave.left + 22, wave.centerY + 11), accent, alpha)
            compact.color = Color.WHITE
            compact.alpha = alpha
            canvas.drawText(name, (wave.left + 29).toFloat(), (wave.centerY - (compact.ascent() + compact.descent()) / 2).toFloat(), compact)
        }
        time22.color = color
        time22.alpha = alpha
        val shown = recorder(time)
        val timeLeft = pill.right - 17.5 - 49 - 10 - time22.measureText(shown)
        canvas.drawText(shown, timeLeft.toFloat(), (pill.centerY - (time22.ascent() + time22.descent()) / 2).toFloat(), time22)
        hits.stop = DRect(pill.right - 17.5 - 49, pill.centerY - 24.5, pill.right - 17.5, pill.centerY + 24.5)
        stroke.color = Color.WHITE
        stroke.strokeWidth = 3f
        stroke.alpha = alpha
        canvas.drawCircle(hits.stop.centerX.toFloat(), hits.stop.centerY.toFloat(), 23f, stroke)
        fill.color = accent
        fill.alpha = alpha
        canvas.drawRoundRect(RectF((hits.stop.centerX - 12.5).toFloat(), (hits.stop.centerY - 12.5).toFloat(), (hits.stop.centerX + 12.5).toFloat(), (hits.stop.centerY + 12.5).toFloat()), 6.5f, 6.5f, fill)
    }

    private fun mediaExpanded(canvas: Canvas, pill: DRect, id: String, glyph: String, titleText: String, detail: String, valueText: String, accent: Int, progress: Float, alpha: Int, icon: Bitmap?) {
        val picture = DRect(pill.left + 16.3, pill.top + 16.8, pill.left + 66.3, pill.top + 66.8)
        if (icon != null) cover(canvas, icon, picture, 12f, alpha)
        else if (id == "upload") thumb(canvas, DRect(picture.centerX - 22, picture.centerY - 22, picture.centerX + 22, picture.centerY + 22), accent, alpha)
        else glyph(canvas, "phone", picture, accent, alpha)
        val textLeft = pill.left + 76.5
        val limit = pill.right - 80
        label14.color = 0x9EEBEBF5.toInt()
        label14.alpha = alpha
        val grey = when {
            id == "upload" -> detail
            glyph == "receive" -> "Receiving $detail"
            else -> "Sending $detail"
        }
        canvas.drawText(ellipsize(grey, label14, limit - textLeft), textLeft.toFloat(), (pill.top + 47.7).toFloat(), label14)
        title.color = Color.WHITE
        title.alpha = alpha
        val white = when {
            id == "upload" && progress >= 1f -> if (titleText.isNotBlank() && titleText != "Uploading") titleText else "Uploaded"
            id == "upload" -> titleText.ifBlank { "Uploading" }
            else -> "To $titleText"
        }
        canvas.drawText(ellipsize(white, title, limit - textLeft), textLeft.toFloat(), (pill.top + 66.2).toFloat(), title)
        val ringBox = DRect(pill.right - 17.1 - 48, pill.top + 17.8, pill.right - 17.1, pill.top + 65.8)
        ring(canvas, ringBox, progress, accent, 0x29FFFFFF, 4f, alpha, if (id == "upload") valueText.removeSuffix("%") else null)
    }

    /** Icon in one ear, a pulse in the other. No title. The pill stays the sensor capsule. */
    private fun liveCompact(canvas: Canvas, pill: DRect, hole: DRect, icon: Bitmap?, now: Double, alpha: Int) {
        val size = 18.0
        var iconBox = DRect(pill.left + 7.2, pill.centerY - size / 2, pill.left + 7.2 + size, pill.centerY + size / 2)
        if (iconBox.right > hole.left - 1.5) {
            val shift = min(0.0, hole.left - 1.5 - iconBox.right)
            iconBox = DRect(iconBox.left + shift, iconBox.top, iconBox.right + shift, iconBox.bottom)
        }
        if (icon != null) cover(canvas, icon, iconBox, 5f, alpha) else glyph(canvas, "screen", iconBox, Color.WHITE, alpha)
        val pulse = 16.0
        var pulseBox = DRect(pill.right - 7.2 - pulse, pill.centerY - pulse / 2, pill.right - 7.2, pill.centerY + pulse / 2)
        if (pulseBox.left < hole.right + 1.5) {
            val shift = max(0.0, hole.right + 1.5 - pulseBox.left)
            pulseBox = DRect(pulseBox.left + shift, pulseBox.top, pulseBox.right + shift, pulseBox.bottom)
        }
        livePulse(canvas, pulseBox, now, alpha)
    }

    private fun livePulse(canvas: Canvas, rect: DRect, now: Double, alpha: Int) {
        val cx = rect.centerX.toFloat()
        val cy = rect.centerY.toFloat()
        val period = 1.2
        val phase = ((now % period) / period).toFloat()
        fill.color = Color.WHITE
        fill.alpha = alpha
        canvas.drawCircle(cx, cy, 2.5f, fill)
        stroke.color = Color.WHITE
        stroke.strokeWidth = 1.35f
        for (offset in floatArrayOf(0f, 0.5f)) {
            val t = (phase + offset) % 1f
            stroke.alpha = (alpha * (1f - t) * 0.8f).toInt()
            canvas.drawCircle(cx, cy, 3.1f + t * 6.2f, stroke)
        }
    }

    /**
     * The call's content row: grey line at 47.7, name at 66.2, both from the island top.
     * Live text uses that row, and only drops far enough to clear the camera hole.
     */
    private fun liveExpanded(canvas: Canvas, pill: DRect, hole: DRect, label: String, detail: String, alpha: Int, icon: Bitmap?) {
        val circle = DRect(pill.left + 16.3, pill.top + 16.8, pill.left + 66.3, pill.top + 66.8)
        iconCircle(canvas, circle, icon, alpha)
        title.color = Color.WHITE
        title.alpha = alpha
        val shift = max(0.0, hole.bottom + 4.0 - (pill.top + 47.7 + title.ascent()))
        val titleBaseline = pill.top + 47.7 + shift
        val subtitleBaseline = pill.top + 66.2 + shift
        val edge = pill.left + 18.0
        val right = pill.right - 18.0
        val labelLeft = beside(edge, circle, titleBaseline, title)
        canvas.drawText(ellipsize(label, title, right - labelLeft), labelLeft.toFloat(), titleBaseline.toFloat(), title)
        if (detail.isNotEmpty()) {
            subtitle.color = 0x9EEBEBF5.toInt()
            subtitle.alpha = alpha
            val detailLeft = beside(edge, circle, subtitleBaseline, subtitle)
            canvas.drawText(ellipsize(detail, subtitle, right - detailLeft), detailLeft.toFloat(), subtitleBaseline.toFloat(), subtitle)
        }
    }

    /** Full island width. A line that meets the circle starts just past it. */
    private fun beside(edge: Double, circle: DRect, baseline: Double, paint: Paint): Double {
        val top = baseline + paint.ascent()
        val bottom = baseline + paint.descent()
        return if (bottom > circle.top && top < circle.bottom) max(edge, circle.right + 10.0) else edge
    }

    /** Filled disc at the call contact's center. The glyph sits inside with padding. */
    private fun iconCircle(canvas: Canvas, rect: DRect, icon: Bitmap?, alpha: Int) {
        fill.color = 0xFF3A3A44.toInt()
        fill.alpha = alpha
        canvas.drawOval(rect.toRect(), fill)
        if (icon == null || icon.isRecycled) return
        val pad = min(rect.width, rect.height) * 0.24
        val box = DRect(rect.left + pad, rect.top + pad, rect.right - pad, rect.bottom - pad)
        val bw = icon.width.toFloat()
        val bh = icon.height.toFloat()
        if (bw < 1f || bh < 1f || box.width < 1.0) return
        val scale = min(box.width.toFloat() / bw, box.height.toFloat() / bh)
        val dw = bw * scale
        val dh = bh * scale
        val left = box.centerX.toFloat() - dw / 2f
        val top = box.centerY.toFloat() - dh / 2f
        coverPaint.alpha = alpha
        canvas.drawBitmap(icon, null, RectF(left, top, left + dw, top + dh), coverPaint)
    }

    private fun personExpanded(canvas: Canvas, pill: DRect, name: String, body: String, alpha: Int, icon: Bitmap?) {
        val avatar = DRect(pill.left + 16.3, pill.top + 16.8, pill.left + 66.3, pill.top + 66.8)
        if (icon != null) cover(canvas, icon, avatar, 14f, alpha) else avatar(canvas, name, avatar, alpha)
        val textLeft = pill.left + 76.5
        val limit = pill.right - 18.5
        title.color = Color.WHITE
        title.alpha = alpha
        canvas.drawText(ellipsize(name, title, limit - textLeft), textLeft.toFloat(), (pill.top + 48).toFloat(), title)
        subtitle.color = 0x9EEBEBF5.toInt()
        subtitle.alpha = alpha
        val lines = wrapLines(body, subtitle, (limit - textLeft).toFloat(), 6)
        var y = pill.top + 70.0
        for (line in lines) {
            if (y > pill.bottom - 10.0) break
            canvas.drawText(line, textLeft.toFloat(), y.toFloat(), subtitle)
            y += 18.0
        }
    }

    private fun eventExpanded(canvas: Canvas, pill: DRect, name: String, detail: String, valueText: String, accent: Int, alpha: Int, icon: Bitmap?) {
        val tile = DRect(pill.left + 18.5, pill.top + 18.18, pill.left + 70.5, pill.top + 70.18)
        fill.color = tint(accent, 0.2f)
        fill.alpha = alpha
        canvas.drawRoundRect(tile.toRect(), 12f, 12f, fill)
        if (icon != null) cover(canvas, icon, tile, 12f, alpha) else glyph(canvas, if (name.startsWith("Order")) "box" else "event", tile, accent, alpha)
        val textLeft = tile.right + 12
        val limit = pill.right - 90
        title.color = Color.WHITE
        title.alpha = alpha
        if (limit > textLeft) canvas.drawText(ellipsize(name, title, limit - textLeft), textLeft.toFloat(), (tile.top + 22).toFloat(), title)
        subtitle.color = 0x9EEBEBF5.toInt()
        subtitle.alpha = alpha
        if (limit > textLeft) canvas.drawText(ellipsize(detail, subtitle, limit - textLeft), textLeft.toFloat(), (tile.top + 44).toFloat(), subtitle)
        value.color = accent
        value.alpha = alpha
        value.textSize = 22f
        canvas.drawText(valueText, (pill.right - 17.81 - value.measureText(valueText)).toFloat(), (tile.top + 28).toFloat(), value)
        value.textSize = 34f
    }

    private fun bubbleGlyph(id: String, accent: Int) = when (id) {
        "call" -> "phone"
        "eta" -> if (accent == 0xFFFFD60A.toInt()) "box" else "car"
        "message" -> "message"
        "calendar" -> "event"
        "order-a", "order-b", "upload" -> "box"
        else -> "screen"
    }

    private fun textIn(canvas: Canvas, text: String, paint: Paint, color: Int, rect: DRect?, end: Boolean, alpha: Int) {
        if (rect == null || text.isEmpty() || rect.width < 2) return
        paint.color = color
        paint.alpha = alpha
        val shown = ellipsize(text, paint, rect.width)
        val x = if (end) rect.right - paint.measureText(shown) else rect.left
        val y = rect.centerY - (paint.ascent() + paint.descent()) / 2.0
        canvas.drawText(shown, x.toFloat(), y.toFloat(), paint)
    }

    private fun ring(canvas: Canvas, rect: DRect, progress: Float, color: Int, track: Int, thickness: Float, alpha: Int, figure: String? = null) {
        val size = min(rect.width, rect.height).toFloat()
        val cx = rect.centerX.toFloat()
        val cy = rect.centerY.toFloat()
        val r = size / 2f - thickness / 2f
        stroke.strokeWidth = thickness
        stroke.color = track
        stroke.alpha = alpha
        canvas.drawCircle(cx, cy, r, stroke)
        if (progress > 0f) {
            stroke.color = color
            val oval = RectF(cx - r, cy - r, cx + r, cy + r)
            canvas.drawArc(oval, -90f, 360f * progress.coerceIn(0f, 1f), false, stroke)
        }
        if (figure == "Done") {
            glyph(canvas, "check", rect, color, alpha)
        } else if (!figure.isNullOrEmpty()) {
            val paint = if (size > 30) title else small
            paint.color = Color.WHITE
            paint.alpha = alpha
            canvas.drawText(figure, cx - paint.measureText(figure) / 2f, cy - (paint.ascent() + paint.descent()) / 2f, paint)
        }
    }

    private var musicPhase = 0.0
    private var musicStamp = Double.NaN

    /** PlaybackState drives the meter. Paused holds the last bar pose. */
    private fun musicClock(now: Double, playing: Boolean): Double {
        if (!playing) {
            musicStamp = Double.NaN
            return musicPhase
        }
        if (!musicStamp.isNaN() && now >= musicStamp) musicPhase += now - musicStamp
        musicStamp = now
        return musicPhase
    }

    private fun wrapLines(text: String, paint: Paint, width: Float, max: Int): List<String> {
        if (width < 8f || max <= 0 || text.isEmpty()) return emptyList()
        val out = ArrayList<String>(max)
        for (raw in text.split('\n')) {
            var rest = raw.trim()
            if (rest.isEmpty()) continue
            while (rest.isNotEmpty() && out.size < max) {
                if (paint.measureText(rest) <= width) {
                    out.add(rest)
                    break
                }
                var cut = rest.length
                while (cut > 1 && paint.measureText(rest.substring(0, cut)) > width) cut--
                val space = rest.lastIndexOf(' ', (cut - 1).coerceAtLeast(0))
                val end = if (space > 0) space else cut
                out.add(rest.substring(0, end).trimEnd())
                rest = rest.substring(end).trimStart()
            }
            if (out.size >= max) break
        }
        return out
    }

    private fun meter(canvas: Canvas, rect: DRect, color: Int, bars: Int, bar: Float, gap: Float, now: Double, alpha: Int, playing: Boolean, twoTone: Boolean = false, envelope: Boolean = false) {
        val total = bars * bar + (bars - 1) * gap
        var x = (rect.right - total).toFloat()
        if (rect.width > total + 4) x = rect.left.toFloat()
        val peaks = floatArrayOf(0.35f, 0.7f, 1f, 0.55f, 0.8f, 0.4f, 0.9f, 0.6f, 0.45f, 0.75f, 1f, 0.5f, 0.3f, 0.65f)
        for (i in 0 until bars) {
            val peak = peaks[i % peaks.size] * if (envelope) 1f else 0.92f
            val swing = if (playing) 0.55f + 0.45f * ((sin(now * 6 + i) + 1) / 2).toFloat() else 0.35f
            val h = (rect.height * peak * swing).toFloat().coerceAtLeast(2f)
            fill.color = if (twoTone && i >= bars / 2) 0xFFFF9F0A.toInt() else color
            fill.alpha = alpha
            val top = (rect.centerY - h / 2).toFloat()
            canvas.drawRoundRect(RectF(x, top, x + bar, top + h), bar / 2, bar / 2, fill)
            x += bar + gap
        }
    }

    /** Width of the charging percentage in the compact paint, so the pill and the text agree. */
    fun percentWidth(percent: String): Double = compact.measureText(percent).toDouble()

    /**
     * Solo charging pill. The floor is the camera capsule. It grows, evenly, only
     * when the percentage no longer fits in the ear beside the lens.
     */
    fun chargingPillWidth(textW: Double, sensorW: Double, holeW: Double): Double {
        val sensor = sensorW.coerceAtLeast(1.0)
        val lens = if (holeW >= 1.0) holeW else 28.0
        val lensHalf = lens / 2.0
        val ear = sensor / 2.0 - lensHalf - chargeClear - chargeEdge
        if (textW <= ear + 0.5) return sensor
        val needLeft = textW + lensHalf + chargeClear + chargeEdge
        val needRight = chargeGlyph + lensHalf + chargeClear + chargeEdge
        return max(sensor, max(needLeft, needRight) * 2.0)
    }

    /** Percent in one ear, BatteryGlyph in the other. The word "Charging" stays on the expanded card. */
    private fun batteryCompact(canvas: Canvas, pill: DRect, hole: DRect, percent: String, accent: Int, charging: Boolean, alpha: Int) {
        val lens = if (hole.width >= 1.0 && hole.centerX >= pill.left && hole.centerX <= pill.right) hole else {
            val diameter = 28.0
            DRect(pill.centerX - diameter / 2, pill.centerY - diameter / 2, pill.centerX + diameter / 2, pill.centerY + diameter / 2)
        }
        val rightRoom = pill.right - chargeEdge - (lens.right + chargeClear)
        val glyphW = min(chargeGlyph, max(16.0, rightRoom))
        val glyphLeft = if (rightRoom > chargeGlyph) lens.right + chargeClear else pill.right - chargeEdge - glyphW
        batteryGlyph(
            canvas,
            DRect(glyphLeft, pill.top, glyphLeft + glyphW, pill.bottom),
            percent,
            charging,
            accent,
            alpha,
            inset = 0.0,
        )
        val color = if (charging) 0xFF53FA6D.toInt() else accent
        val textRight = lens.left - chargeClear
        val textLeft = pill.left + chargeEdge
        if (textRight - textLeft > 8.0) {
            textIn(canvas, percent, compact, color, DRect(textLeft, pill.top, textRight, pill.bottom), true, alpha)
        }
    }

    /**
     * islandcn `BatteryGlyph`: a solid rounded body, charge from the left, no outline and no bolt.
     * Charging fills mint to lime over a deep green empty body. Otherwise the fill is the ink
     * colour and the empty body and nub are that colour at 42%.
     */
    private fun batteryGlyph(canvas: Canvas, bubble: DRect, percent: String, charging: Boolean, color: Int, alpha: Int, inset: Double = 6.0) {
        val level = (percent.removeSuffix("%").toFloatOrNull()?.div(100f) ?: 0f).coerceIn(0f, 1f)
        val width = min(27.0, bubble.width - inset).toFloat().coerceAtLeast(16f)
        val scale = width / 36.1f
        val nubW = 1.9f * scale
        val nubH = 5.8f * scale
        val gap = 1.2f * scale
        val bodyW = width - nubW - gap
        val bodyH = 17f * scale
        val radius = 5f * scale
        val left = bubble.centerX.toFloat() - width / 2f
        val top = bubble.centerY.toFloat() - bodyH / 2f
        val empty = if (charging) 0xFF2E7124.toInt() else color
        val emptyAlpha = if (charging) alpha else (alpha * 0.42f).toInt()
        fill.shader = null
        fill.color = empty
        fill.alpha = emptyAlpha
        canvas.drawRoundRect(RectF(left, top, left + bodyW, top + bodyH), radius, radius, fill)
        val fillScale = level.coerceAtLeast(0.06f)
        val body = android.graphics.Path()
        body.addRoundRect(RectF(left, top, left + bodyW, top + bodyH), radius, radius, android.graphics.Path.Direction.CW)
        canvas.save()
        canvas.clipPath(body)
        canvas.translate(left, top)
        canvas.scale(fillScale, 1f)
        if (charging) {
            fill.shader = LinearGradient(
                0f, 0f, bodyW, 0f,
                intArrayOf(0xFF5AFEB6.toInt(), 0xFF62FD6B.toInt(), 0xFF63FF39.toInt()),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
            fill.alpha = alpha
        } else {
            fill.shader = null
            fill.color = color
            fill.alpha = alpha
        }
        canvas.drawRoundRect(RectF(0f, 0f, bodyW, bodyH), radius, radius, fill)
        fill.shader = null
        canvas.restore()
        fill.color = empty
        fill.alpha = emptyAlpha
        canvas.drawRoundRect(
            RectF(left + bodyW + gap, top + (bodyH - nubH) / 2f, left + bodyW + gap + nubW, top + (bodyH + nubH) / 2f),
            nubW / 2f,
            nubW / 2f,
            fill,
        )
    }

    private const val chargeEdge = 7.2
    private const val chargeClear = 1.5
    private const val chargeGlyph = 27.0

    private fun batteryRow(canvas: Canvas, rect: DRect, percent: String, accent: Int, charging: Boolean, alpha: Int) {
        val color = if (charging) 0xFF53FA6D.toInt() else accent
        compact.color = color
        compact.alpha = alpha
        val textW = compact.measureText(percent)
        canvas.drawText(percent, (rect.right - 2 - 28 - 4 - textW).toFloat(), (rect.centerY - (compact.ascent() + compact.descent()) / 2).toFloat(), compact)
        batteryIcon(canvas, rect.right - 2 - 28, rect.centerY - 6.6, 28.0, percent, color, charging, alpha)
    }

    private fun batteryIcon(canvas: Canvas, x: Double, y: Double, width: Double, percent: String, color: Int, charging: Boolean, alpha: Int) {
        val bodyW = width * 33 / 36.1
        val h = width * 17 / 36.1
        val level = percent.removeSuffix("%").toFloatOrNull()?.div(100f) ?: 0.5f
        fill.color = if (charging) 0xFF2E7124.toInt() else tint(color, 0.42f)
        fill.alpha = alpha
        canvas.drawRoundRect(RectF(x.toFloat(), y.toFloat(), (x + bodyW).toFloat(), (y + h).toFloat()), 4f, 4f, fill)
        fill.color = color
        canvas.drawRoundRect(RectF(x.toFloat(), y.toFloat(), (x + bodyW * level.coerceIn(0.06f, 1f)).toFloat(), (y + h).toFloat()), 4f, 4f, fill)
        val nubW = width * 1.9 / 36.1
        val nubH = h * 0.34
        canvas.drawRoundRect(RectF((x + bodyW + 1).toFloat(), (y + (h - nubH) / 2).toFloat(), (x + bodyW + 1 + nubW).toFloat(), (y + (h + nubH) / 2).toFloat()), 1f, 1f, fill)
        if (!charging) return
        val cx = (x + bodyW / 2).toFloat()
        val cy = (y + h / 2).toFloat()
        val s = h.toFloat()
        val bolt = android.graphics.Path()
        bolt.moveTo(cx + s * 0.06f, cy - s * 0.38f)
        bolt.lineTo(cx - s * 0.20f, cy + s * 0.02f)
        bolt.lineTo(cx - s * 0.02f, cy + s * 0.02f)
        bolt.lineTo(cx - s * 0.10f, cy + s * 0.38f)
        bolt.lineTo(cx + s * 0.22f, cy - s * 0.04f)
        bolt.lineTo(cx + s * 0.02f, cy - s * 0.04f)
        bolt.close()
        fill.color = 0xFF10210F.toInt()
        fill.alpha = alpha
        canvas.drawPath(bolt, fill)
    }

    private val coverPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    private fun cover(canvas: Canvas, bitmap: Bitmap?, rect: DRect, radius: Float, alpha: Int) {
        if (bitmap == null || bitmap.isRecycled || rect.width < 1.0 || rect.height < 1.0) return
        val corner = radius.toDouble().coerceAtMost(min(rect.width, rect.height) / 2.0)
        canvas.save()
        canvas.clipPath(continuousRect(rect, corner, 0.72))
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()
        val scale = max(rect.width.toFloat() / bw, rect.height.toFloat() / bh)
        val dw = bw * scale
        val dh = bh * scale
        val left = rect.centerX.toFloat() - dw / 2f
        val top = rect.centerY.toFloat() - dh / 2f
        coverPaint.alpha = alpha
        canvas.drawBitmap(bitmap, null, RectF(left, top, left + dw, top + dh), coverPaint)
        canvas.restore()
    }

    private fun thumb(canvas: Canvas, rect: DRect, accent: Int, alpha: Int) {
        fill.color = tint(accent, 0.22f)
        fill.alpha = alpha
        val radius = if (rect.width > 30) 16f else 5.5f
        canvas.drawRoundRect(rect.toRect(), radius, radius, fill)
        glyph(canvas, "upload", rect, accent, alpha)
    }

    private fun avatar(canvas: Canvas, name: String, rect: DRect?, alpha: Int) {
        if (rect == null) return
        fill.color = 0xFF3A3A44.toInt()
        fill.alpha = alpha
        canvas.drawOval(rect.toRect(), fill)
        val letter = name.firstOrNull()?.uppercase() ?: ""
        compact.color = Color.WHITE
        compact.alpha = alpha
        canvas.drawText(letter, (rect.centerX - compact.measureText(letter) / 2).toFloat(), (rect.centerY - (compact.ascent() + compact.descent()) / 2).toFloat(), compact)
    }

    private fun dot(canvas: Canvas, rect: DRect, color: Int, live: Boolean, alpha: Int) {
        fill.color = if (live) color else 0x9EEBEBF5.toInt()
        fill.alpha = alpha
        val r = (min(rect.width, rect.height) * 0.25).toFloat()
        canvas.drawCircle(rect.centerX.toFloat(), rect.centerY.toFloat(), r, fill)
    }

    private fun circle(canvas: Canvas, rect: DRect, color: Int, alpha: Int) {
        fill.color = color
        fill.alpha = alpha
        canvas.drawOval(rect.toRect(), fill)
    }

    private fun symbol(canvas: Canvas, drawable: Drawable?, rect: DRect, color: Int, alpha: Int) {
        val icon = drawable ?: return
        val size = min(rect.width, rect.height).toFloat().coerceAtLeast(1f)
        canvas.save()
        canvas.translate(rect.centerX.toFloat(), rect.centerY.toFloat())
        canvas.scale(size / 24f, size / 24f)
        icon.setTint(color)
        icon.alpha = alpha
        icon.setBounds(-12, -12, 12, 12)
        icon.draw(canvas)
        canvas.restore()
    }

    /**
     * Closed at 0, open at 1. Same white body and stroke as the locked glyph.
     * The left leg stays in the body. The right leg lifts straight up and stops
     * above the body, still on the right, with a gap under the free end.
     */
    private fun padlockIn(canvas: Canvas, rect: DRect, open: Float, alpha: Int) {
        val s = min(rect.width, rect.height).toFloat() * 0.34f
        padlock(canvas, rect.centerX.toFloat(), rect.centerY.toFloat(), s, open, alpha)
    }

    private fun padlock(canvas: Canvas, cx: Float, cy: Float, s: Float, open: Float, alpha: Int) {
        fill.color = Color.WHITE
        fill.alpha = alpha
        stroke.color = Color.WHITE
        stroke.alpha = alpha
        stroke.strokeWidth = (s * 0.16f).coerceAtLeast(1.4f)
        stroke.strokeCap = Paint.Cap.ROUND
        stroke.strokeJoin = Paint.Join.ROUND
        val lift = smoothStep(open.coerceIn(0f, 1f))
        val bodyTop = cy - s * 0.15f
        val leftX = cx - s * 0.46f
        val rightX = cx + s * 0.46f
        // The arch grows taller as the right leg rises, so the open gap is obvious.
        // The left foot stays buried. The free end stays on the right.
        val shoulder = cy - s * (0.62f + 0.36f * lift)
        val radius = s * 0.46f
        val buried = cy + s * 0.18f
        val openFoot = min(bodyTop - s * 0.84f, shoulder - s * 0.04f)
        val rightFoot = buried + (openFoot - buried) * lift
        val shackle = Path()
        shackle.moveTo(leftX, buried)
        shackle.lineTo(leftX, shoulder)
        shackle.arcTo(RectF(leftX, shoulder - radius, rightX, shoulder + radius), 180f, 180f, false)
        shackle.lineTo(rightX, rightFoot)
        canvas.drawPath(shackle, stroke)
        canvas.drawRoundRect(RectF(cx - s * 0.72f, bodyTop, cx + s * 0.72f, cy + s * 0.95f), s * 0.22f, s * 0.22f, fill)
    }

    private fun smoothStep(amount: Float): Float {
        val u = amount.coerceIn(0f, 1f)
        return u * u * (3f - 2f * u)
    }

    /** Expanded stand-in for Face ID once the device is unlocked but the lock screen is still up. */
    private fun unlockingPadlock(canvas: Canvas, pill: DRect, hole: DRect, open: Float, alpha: Int) {
        val top = hole.bottom + 8.0
        val bottom = pill.bottom - 12.0
        val room = (bottom - top).coerceAtLeast(28.0)
        val s = (room / 2.15).toFloat().coerceIn(14f, 32f)
        padlock(canvas, pill.centerX.toFloat(), ((top + bottom) / 2.0).toFloat(), s, open, alpha)
    }

    /**
     * Expanded lock while the device still needs authentication. Geometry is measured from the reference glyph:
     * an outer ring of four arcs centered on the sides, a full inner circle, vertical eyes,
     * a J nose, and a shallow smile. Strokes mask one radial gradient that sweeps
     * out from the center while the whole mark breathes.
     */
    private fun faceIdMark(canvas: Canvas, pill: DRect, hole: DRect, now: Double, alpha: Int) {
        if (alpha <= 0) return
        val top = hole.bottom + 18.0
        val bottom = pill.bottom - 18.0
        val room = (bottom - top).coerceAtLeast(32.0)
        val cx = pill.centerX.toFloat()
        val cy = ((top + bottom) / 2.0).toFloat()
        // Inner-ring centerline is 1. Outer centerline 1.281, stroke 0.147.
        val outerEdge = 1.281f + 0.147f / 2f
        val fit = min(room, (pill.width - 64.0).coerceAtLeast(32.0))
        val radius = (fit / (2.0 * outerEdge)).toFloat()
        val strokeW = radius * 0.147f
        val breath = (0.5 + 0.5 * sin(now * 2.0 * PI / 3.2)).toFloat()
        val scale = 0.88f + 0.12f * breath
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(scale, scale)
        canvas.translate(-cx, -cy)
        val edge = radius * outerEdge + 2f
        val layer = canvas.saveLayer(cx - edge, cy - edge, cx + edge, cy + edge, null)
        faceMask.strokeWidth = strokeW
        faceMask.alpha = 255
        faceMask.xfermode = null
        val outer = radius * 1.281f
        val outerOval = RectF(cx - outer, cy - outer, cx + outer, cy + outer)
        val arc = 70f
        for (mid in floatArrayOf(0f, 90f, 180f, 270f)) {
            canvas.drawArc(outerOval, mid - arc / 2f, arc, false, faceMask)
        }
        canvas.drawCircle(cx, cy, radius, faceMask)
        val eyeDx = radius * 0.413f
        val eyeDy = radius * -0.244f
        val eyeHalf = radius * 0.100f
        canvas.drawLine(cx - eyeDx, cy + eyeDy - eyeHalf, cx - eyeDx, cy + eyeDy + eyeHalf, faceMask)
        canvas.drawLine(cx + eyeDx, cy + eyeDy - eyeHalf, cx + eyeDx, cy + eyeDy + eyeHalf, faceMask)
        val stemTop = cy + radius * -0.344f
        val hook = radius * 0.122f
        val nose = Path()
        nose.moveTo(cx, stemTop)
        nose.lineTo(cx, cy)
        nose.arcTo(RectF(cx - hook * 2f, cy - hook, cx, cy + hook), 0f, 90f, false)
        canvas.drawPath(nose, faceMask)
        val smileR = radius * 0.431f
        val smileCy = cy + radius * 0.039f
        canvas.drawArc(RectF(cx - smileR, smileCy - smileR, cx + smileR, smileCy + smileR), 45f, 90f, false, faceMask)
        faceGlow.shader = faceSweep(cx, cy, radius * outerEdge, now)
        faceGlow.xfermode = faceIn
        faceGlow.alpha = alpha
        canvas.drawRect(cx - edge, cy - edge, cx + edge, cy + edge, faceGlow)
        faceGlow.xfermode = null
        faceGlow.alpha = 255
        canvas.restoreToCount(layer)
        canvas.restore()
    }

    /** Vibrant cyan expanding from the center, dim blue ahead of it and behind it. */
    private fun faceSweep(cx: Float, cy: Float, outer: Float, now: Double): RadialGradient {
        val reach = outer.coerceAtLeast(1f)
        val peak = ((now / 2.4) % 1.0).toFloat() * reach
        val band = reach * 0.36f
        val vibrant = Color.rgb(105, 223, 251)
        val dim = Color.rgb(46, 104, 156)
        val count = 28
        val colors = IntArray(count)
        val stops = FloatArray(count)
        for (i in 0 until count) {
            val t = i / (count - 1f)
            val rad = t * reach
            val glow = max(faceGlowAt(rad - peak, band), max(faceGlowAt(rad - peak + reach, band), faceGlowAt(rad - peak - reach, band)))
            colors[i] = mixColor(dim, vibrant, glow)
            stops[i] = t
        }
        return RadialGradient(cx, cy, reach, colors, stops, Shader.TileMode.CLAMP)
    }

    private fun faceGlowAt(distance: Float, band: Float): Float {
        val d = abs(distance) / band
        if (d >= 1f) return 0f
        val u = 1f - d
        return u * u * (3f - 2f * u)
    }

    private fun mixColor(from: Int, to: Int, amount: Float): Int {
        val u = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(from) + (Color.red(to) - Color.red(from)) * u).toInt(),
            (Color.green(from) + (Color.green(to) - Color.green(from)) * u).toInt(),
            (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * u).toInt(),
        )
    }

    /** Flashlight whose beam grows with [level], the way the iOS island icon does. */
    private fun flashlightIcon(canvas: Canvas, rect: DRect, level: Float, alpha: Int) {
        val t = level.coerceIn(0f, 1f)
        val cx = rect.centerX.toFloat()
        val cy = rect.centerY.toFloat()
        val body = min(rect.width, rect.height).toFloat().coerceAtLeast(8f) * 0.34f
        val beam = body * (0.35f + 1.15f * t)
        fill.color = 0xFFFFD60A.toInt()
        fill.alpha = (alpha * (0.18f + 0.82f * t)).toInt()
        val beamPath = android.graphics.Path()
        val lamp = cy - body * 0.55f
        beamPath.moveTo(cx - body * 0.42f, lamp)
        beamPath.lineTo(cx + body * 0.42f, lamp)
        beamPath.lineTo(cx + body * (0.7f + 0.85f * t), lamp - beam)
        beamPath.lineTo(cx - body * (0.7f + 0.85f * t), lamp - beam)
        beamPath.close()
        canvas.drawPath(beamPath, fill)
        fill.color = Color.WHITE
        fill.alpha = alpha
        canvas.drawRoundRect(RectF(cx - body * 0.55f, cy - body * 0.15f, cx + body * 0.55f, cy + body * 1.15f), body * 0.2f, body * 0.2f, fill)
        fill.color = 0xFFFFD60A.toInt()
        fill.alpha = (alpha * (0.4f + 0.6f * t)).toInt()
        canvas.drawRoundRect(RectF(cx - body * 0.72f, lamp, cx + body * 0.72f, lamp + body * 0.42f), body * 0.12f, body * 0.12f, fill)
    }

    /** Expanded torch: the icon tracks the level, and the track is the brightness slider. */
    private fun torchExpanded(canvas: Canvas, pill: DRect, hole: DRect, level: Float, hits: Hits, alpha: Int) {
        val t = level.coerceIn(0f, 1f)
        val row = max(pill.centerY, hole.bottom + 22.0)
        val icon = DRect(pill.left + 22.0, row - 18.0, pill.left + 58.0, row + 18.0)
        flashlightIcon(canvas, icon, t, alpha)
        val track = DRect(icon.right + 16.0, row - 14.0, pill.right - 22.0, row + 14.0)
        hits.slider = track.inflate(10.0)
        fill.color = 0x33FFFFFF
        fill.alpha = alpha
        canvas.drawRoundRect(track.toRect(), 14f, 14f, fill)
        val fillRight = track.left + track.width * t
        if (fillRight - track.left > 1.0) {
            fill.color = 0xFFFFD60A.toInt()
            fill.alpha = alpha
            canvas.drawRoundRect(RectF(track.left.toFloat(), track.top.toFloat(), fillRight.toFloat(), track.bottom.toFloat()), 14f, 14f, fill)
        }
        fill.color = Color.WHITE
        fill.alpha = alpha
        val knob = 11f
        val kx = fillRight.toFloat().coerceIn((track.left + knob).toFloat(), (track.right - knob).toFloat())
        canvas.drawCircle(kx, row.toFloat(), knob, fill)
    }

    private fun glyph(canvas: Canvas, name: String, rect: DRect?, color: Int, alpha: Int) {
        if (rect == null) return
        val cx = rect.centerX.toFloat()
        val cy = rect.centerY.toFloat()
        val s = min(rect.width, rect.height).toFloat() * 0.34f
        stroke.color = color
        stroke.alpha = alpha
        stroke.strokeWidth = (s * 0.16f).coerceAtLeast(1.4f)
        fill.color = color
        fill.alpha = alpha
        when (name) {
            "timer" -> {
                canvas.drawCircle(cx, cy, s, stroke)
                canvas.drawLine(cx, cy, cx, cy - s * 0.65f, stroke)
                canvas.drawLine(cx, cy, cx + s * 0.45f, cy, stroke)
            }
            "phone" -> {
                canvas.drawRoundRect(RectF(cx - s * 0.55f, cy - s * 0.9f, cx + s * 0.15f, cy + s * 0.2f), s * 0.3f, s * 0.3f, stroke)
                canvas.drawLine(cx - s * 0.1f, cy + s * 0.15f, cx + s * 0.7f, cy + s * 0.7f, stroke)
            }
            "lock" -> {
                val body = RectF(cx - s * 0.72f, cy - s * 0.15f, cx + s * 0.72f, cy + s * 0.95f)
                canvas.drawRoundRect(body, s * 0.22f, s * 0.22f, fill)
                canvas.drawArc(RectF(cx - s * 0.48f, cy - s * 1.05f, cx + s * 0.48f, cy + s * 0.15f), 180f, 180f, false, stroke)
            }
            "missed" -> {
                symbol(canvas, phoneMissed, rect, color, alpha)
                return
            }
            "hangup" -> canvas.drawLine(cx - s, cy, cx + s, cy, stroke.apply { strokeWidth = s * 0.28f })
            "pause" -> {
                canvas.drawRoundRect(RectF(cx - s * 0.55f, cy - s, cx - s * 0.15f, cy + s), 2f, 2f, fill)
                canvas.drawRoundRect(RectF(cx + s * 0.15f, cy - s, cx + s * 0.55f, cy + s), 2f, 2f, fill)
            }
            "play" -> {
                val path = android.graphics.Path()
                path.moveTo(cx - s * 0.4f, cy - s)
                path.lineTo(cx + s * 0.7f, cy)
                path.lineTo(cx - s * 0.4f, cy + s)
                path.close()
                canvas.drawPath(path, fill)
            }
            "check" -> {
                canvas.drawLine(cx - s * 0.55f, cy, cx - s * 0.15f, cy + s * 0.45f, stroke)
                canvas.drawLine(cx - s * 0.15f, cy + s * 0.45f, cx + s * 0.6f, cy - s * 0.5f, stroke)
            }
            "close" -> {
                canvas.drawLine(cx - s * 0.6f, cy - s * 0.6f, cx + s * 0.6f, cy + s * 0.6f, stroke)
                canvas.drawLine(cx + s * 0.6f, cy - s * 0.6f, cx - s * 0.6f, cy + s * 0.6f, stroke)
            }
            "car" -> {
                canvas.drawRoundRect(RectF(cx - s, cy - s * 0.2f, cx + s, cy + s * 0.45f), 4f, 4f, fill)
                canvas.drawCircle(cx - s * 0.5f, cy + s * 0.55f, s * 0.22f, fill)
                canvas.drawCircle(cx + s * 0.5f, cy + s * 0.55f, s * 0.22f, fill)
            }
            "box", "upload" -> canvas.drawRoundRect(RectF(cx - s * 0.7f, cy - s * 0.55f, cx + s * 0.7f, cy + s * 0.7f), 3f, 3f, stroke)
            "message" -> {
                symbol(canvas, chatBubble, rect, color, alpha)
                return
            }
            "event" -> canvas.drawRoundRect(RectF(cx - s * 0.7f, cy - s * 0.8f, cx + s * 0.7f, cy + s * 0.8f), 3f, 3f, stroke)
            "back", "forward" -> {
                val dir = if (name == "back") -1f else 1f
                canvas.drawLine(cx - dir * s * 0.4f, cy - s * 0.45f, cx + dir * s * 0.2f, cy, stroke)
                canvas.drawLine(cx + dir * s * 0.2f, cy, cx - dir * s * 0.4f, cy + s * 0.45f, stroke)
            }
            "screen", "camera" -> canvas.drawCircle(cx, cy, s * 0.7f, stroke)
            else -> canvas.drawCircle(cx, cy, s * 0.7f, stroke)
        }
    }

    private fun recorder(time: String): String = if (time.length == 4 && time[1] == ':') "0$time" else time

    private fun ellipsize(text: String, paint: Paint, max: Double): String {
        if (max <= 8) return ""
        if (paint.measureText(text) <= max) return text
        var end = text.length
        while (end > 0 && paint.measureText(text.substring(0, end) + "…") > max) end--
        return if (end == 0) "…" else text.substring(0, end) + "…"
    }

    private fun tint(color: Int, alpha: Float): Int {
        val a = (alpha * 255).toInt().coerceIn(0, 255)
        return (color and 0x00FFFFFF) or (a shl 24)
    }

    private fun text(size: Float, weight: Int, tracking: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        typeface = Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), weight, false)
        letterSpacing = if (size == 0f) 0f else tracking / size
        fontFeatureSettings = "tnum"
        color = Color.WHITE
    }

    fun coverAccent(seed: String): Int = accents[seed.fold(0) { acc, c -> acc * 31 + c.code } and 7]

    private val accents = intArrayOf(
        0xFF5FE0C4.toInt(), 0xFFFF7A5C.toInt(), 0xFFD68CFF.toInt(), 0xFFA9E05A.toInt(),
        0xFF5CB8FF.toInt(), 0xFFFFB547.toInt(), 0xFFFF8FB6.toInt(), 0xFFFF7D52.toInt(),
    )

    private fun DRect.toRect() = RectF(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat())
}
