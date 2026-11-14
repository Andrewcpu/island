package dev.islandcn.island

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.provider.Settings
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Native island. Shape, springs, and ear layout come from islandcn; this view
 * only draws them and turns Try it commands into activities.
 */
class IslandPillView(context: Context) : View(context) {
    var onWindow: ((left: Int, top: Int, width: Int, height: Int) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private val rig = IslandRig()
    private val faces = linkedMapOf<String, Face>()
    private var sample: String? = null
    private var originX = 0
    private var originY = 0
    private var appliedL = Int.MIN_VALUE
    private var appliedT = Int.MIN_VALUE
    private var appliedW = Int.MIN_VALUE
    private var appliedH = Int.MIN_VALUE
    private var posted = false
    private var clock0 = 0L
    private var pressedButton: String? = null
    private val hits = IslandModes.Hits()
    private var mediaPosition = 0L
    private var mediaDuration = 0L
    private var mediaTrack: IslandMedia.Track? = null
    private var liveMusic = false
    private var liveCall = false
    private var liveMessage = false
    private var liveTimer = false
    private var callerLabel = ""
    private var timerSpan = 5 * 60 * 1000L
    private var timerEnds = 0L
    private var timerLeft = 0L
    private var timerPaused = false
    private var timerDoneAt = 0.0
    private var recordEnds = 0L
    private var recordHeld = 0L
    private var recordPaused = false
    private var callFrom = 0L
    private var uploadStarted = 0L
    private var transferStarted = 0L
    private var micOn = false
    private var mediaAt = 0L
    private val choreographer = Choreographer.getInstance()

    private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
    private val keyline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        color = 0x29FFFFFF
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x59000000.toInt() }
    private val compactPaint = text(15f, Color.WHITE, 600)

    private val frame = Choreographer.FrameCallback {
        posted = false
        if (clock0 == 0L) clock0 = SystemClock.uptimeMillis()
        val t = (SystemClock.uptimeMillis() - clock0) / 1000.0
        advance()
        rig.sync(t)
        publish()
        invalidate()
        if (rig.animating || ticking()) kick()
    }

    init {
        isClickable = true
        isFocusable = false
        isFocusableInTouchMode = false
        rig.onPressed = { id -> onTap(id) }
    }

    fun windowFrame(): IntArray {
        publish()
        return intArrayOf(appliedL, appliedT, appliedW.coerceAtLeast(1), appliedH.coerceAtLeast(1))
    }

    fun bindCutout() {
        val window = context.getSystemService(WindowManager::class.java) ?: return
        val metrics = window.currentWindowMetrics
        val bounds = metrics.bounds
        val cutouts = metrics.windowInsets.displayCutout?.boundingRects.orEmpty().map { rect ->
            DRect(rect.left / density.toDouble(), rect.top / density.toDouble(), rect.right / density.toDouble(), rect.bottom / density.toDouble())
        }
        rig.bindHole(bounds.width() / density.toDouble(), bounds.height() / density.toDouble(), cutouts)
        publish()
        kick()
    }

    fun onMedia(track: IslandMedia.Track?) {
        if (track == null) {
            liveMusic = false
            mediaTrack = null
            if (sample == "music") sample = null
            if (faces.remove("now-playing") != null) {
                rig.end("now-playing")
                kick()
            }
            return
        }
        liveMusic = true
        if (sample == "music") sample = null
        showTrack(track)
    }

    fun onLiveCall(state: String, label: String) {
        when (state) {
            "ringing" -> showLiveCall(ringing = true, label = label)
            "offhook" -> showLiveCall(ringing = false, label = label)
            else -> {
                liveCall = false
                callerLabel = ""
                callFrom = 0L
                if (sample != "call" && faces.remove("call") != null) {
                    rig.end("call")
                    kick()
                }
            }
        }
    }

    fun onLiveMessage(sender: String, body: String) {
        val title = sender.trim()
        val text = body.trim()
        if (title.isEmpty() && text.isEmpty()) return
        liveMessage = true
        if (sample == "message") sample = null
        val existing = faces["message"]
        if (existing != null && existing.title == title && existing.detail == text) return
        present(face("message", "message", "", "", title, text, "", Color.WHITE, -1f, 48.0, 80), alert = existing == null)
    }

    fun onLiveMessageCleared() {
        liveMessage = false
        if (sample == "message") return
        if (faces.remove("message") != null) {
            rig.end("message")
            kick()
        }
    }

    fun onLiveTimer(remainingMs: Long, paused: Boolean, label: String) {
        val left = remainingMs.coerceAtLeast(0L)
        val caption = label.trim().ifBlank { "Timer" }
        if (!liveTimer || left > timerLeft + 2000L) timerSpan = left.coerceAtLeast(1L)
        liveTimer = true
        if (sample == "timer") sample = null
        timerPaused = paused
        timerLeft = left
        timerEnds = SystemClock.elapsedRealtime() + left
        timerDoneAt = 0.0
        val text = clock(left)
        val existing = faces["timer"]
        if (existing != null) {
            existing.trail = text
            existing.value = text
            existing.title = caption
            existing.detail = if (paused) "Paused" else caption
            existing.progress = left.toFloat() / timerSpan.coerceAtLeast(1L)
            kick()
            return
        }
        present(face("timer", "timer", "", text, caption, if (paused) "Paused" else caption, text, 0xFFFF9F0A.toInt(), left.toFloat() / timerSpan.coerceAtLeast(1L), 50.0, 50))
    }

    fun onLiveTimerCleared() {
        liveTimer = false
        if (sample == "timer") return
        timerDoneAt = 0.0
        if (faces.remove("timer") != null) {
            rig.end("timer")
            kick()
        }
    }

    private fun showLiveCall(ringing: Boolean, label: String) {
        liveCall = true
        if (sample == "call") sample = null
        val incoming = label.trim()
        if (incoming.isNotEmpty()) callerLabel = incoming
        val title = callerLabel
        val existing = faces["call"]
        if (ringing) {
            callFrom = 0L
            if (existing != null && existing.value == "Ring" && existing.title == title) return
            if (existing != null && existing.value == "Ring") {
                existing.title = title
                kick()
                return
            }
            present(face("call", "phone", "", "Ring", title, "Audio call", "Ring", 0xFF31D259.toInt(), -1f, 48.0, 100), alert = true)
            return
        }
        if (existing != null && existing.value != "Ring") {
            if (incoming.isNotEmpty()) existing.title = title
            kick()
            return
        }
        if (callFrom == 0L) callFrom = SystemClock.elapsedRealtime()
        val elapsed = clock(SystemClock.elapsedRealtime() - callFrom)
        present(face("call", "phone", "", elapsed, title, "On the call", elapsed, 0xFF76FB73.toInt(), -1f, 48.0, 100))
    }

    fun command(name: String, args: Map<String, Any?>) {
        when (name) {
            "clear" -> dismissPreview()
            "outside" -> {
                rig.collapse()
                kick()
            }
            "setKeyline" -> {
                rig.setKeyline(args["value"] != false && args["value"] != "false")
                invalidate()
            }
            "setReduced" -> {
                val mode = args["value"]?.toString() ?: "system"
                val system = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
                rig.setReduced(mode == "always" || (mode == "system" && system))
                kick()
            }
            "startTimer" -> toggle("timer") { armTimer() }
            "playSample" -> {
                if (sample == "music") {
                    dismissPreview()
                    return
                }
                if (liveMusic) return
                val track = IslandMedia.current(context) ?: return
                dismissPreview()
                sample = "music"
                showTrack(track)
            }
            "incomingCall" -> toggle("call") {
                val caller = args["caller"]?.toString()?.ifBlank { null } ?: "Mochi"
                callFrom = 0L
                present(face("call", "phone", "", "Ring", caller, "Audio call", "Ring", 0xFF31D259.toInt(), -1f, 48.0, 100), alert = true)
            }
            "acceptCall" -> if (faces.containsKey("call")) accept()
            "endCall" -> if (sample == "call") dismissPreview() else if (liveCall) IslandMonitors.endCall(context)
            "startUpload" -> toggle("upload") {
                uploadStarted = SystemClock.elapsedRealtime()
                present(face("upload", "upload", "", "0%", "Uploading", "biscuit-in-the-sun.jpg", "0%", 0xFF0A84FF.toInt(), 0f, 48.0, 30))
            }
            "startEta" -> {
                val delivery = args["kind"]?.toString() == "delivery"
                val key = if (delivery) "delivery" else "ride"
                toggle(key) {
                    if (delivery) present(face("eta", "box", "", "6 mins", "Delivery", "Parcel · Door", "6 minutes", 0xFFFFD60A.toInt(), -1f, 132.0, 25))
                    else present(face("eta", "car", "", "6 mins", "Alex Kim", "Blue hatchback · 8YQ 214", "6 minutes", 0xFF32D74B.toInt(), -1f, 132.0, 25))
                }
            }
            "startRecording" -> {
                val kind = args["kind"]?.toString() ?: "audio"
                toggle("record-$kind") {
                    if (kind == "audio") micOn = IslandMonitors.startMic(context)
                    recordPaused = false
                    recordHeld = 0L
                    recordEnds = SystemClock.elapsedRealtime()
                    val title = when (kind) {
                        "screen" -> "Screen recording"
                        "camera" -> "Video"
                        else -> "Voice memo"
                    }
                    present(face("recording", "mic", "", "0:00", title, "Recording", "0:00", 0xFFFF453A.toInt(), -1f, 48.0, 70))
                }
            }
            "stopRecording" -> if (sample?.startsWith("record") == true) dismissPreview()
            "toggleRecording" -> if (faces.containsKey("recording")) toggleRecord()
            "startTransfer" -> {
                val send = args["direction"]?.toString() != "receive"
                toggle(if (send) "send" else "receive") {
                    transferStarted = SystemClock.elapsedRealtime()
                    present(face("transfer", if (send) "send" else "receive", "", "0%", "Maya's Pixel", "biscuit-in-the-sun.jpg", "0%", 0xFF0A84FF.toInt(), 0f, 48.0, 30))
                }
            }
            "showMessage" -> toggle("message") {
                val sender = args["sender"]?.toString()?.ifBlank { null } ?: "Sam"
                val body = args["body"]?.toString()?.ifBlank { null } ?: "The photos are in."
                present(face("message", "message", "", "", sender, body, "", Color.WHITE, -1f, 48.0, 80), alert = true)
            }
            "pinEvent" -> toggle("event") {
                present(face("calendar", "event", "", "15 min", "Design review", "Studio", "15 min", 0xFF0A84FF.toInt(), -1f, 48.0, 20))
            }
            "showBattery" -> toggle("battery") { showBattery() }
            "split" -> toggle("split") {
                armTimer()
                val track = IslandMedia.current(context)
                if (track != null) showTrack(track)
            }
            "startOrders" -> toggle("orders") {
                present(face("order-a", "box", "", "12 min", "Order 1042", "Preparing", "12 min", 0xFFFF9F0A.toInt(), -1f, 48.0, 22, "orders"))
            }
            "handoff" -> {
                if (sample == "handoff") {
                    dismissPreview()
                    return
                }
                if (sample != "orders" && sample != "handoff") dismissPreview()
                sample = "handoff"
                faces.remove("order-a")
                rig.end("order-a")
                present(face("order-b", "box", "", "On way", "Order 1043", "Out for delivery", "On way", 0xFFFF9F0A.toInt(), -1f, 48.0, 22, "orders"), alert = true)
            }
            else -> Unit
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        kick()
    }

    override fun onDetachedFromWindow() {
        choreographer.removeFrameCallback(frame)
        posted = false
        super.onDetachedFromWindow()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            rig.collapse()
            kick()
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = (event.x + originX) / density.toDouble()
        val y = (event.y + originY) / density.toDouble()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val bubble = rig.bubble
                val target = if (bubble != null && contains(bubble, x, y)) "bubble" else "pill"
                rig.pointerDown(target)
                kick()
                return true
            }
            MotionEvent.ACTION_UP -> {
                pressedButton = when {
                    !rig.expanded -> null
                    contains(hits.decline, x, y) -> "decline"
                    contains(hits.accept, x, y) -> "accept"
                    contains(hits.pause, x, y) -> "pause"
                    contains(hits.cancel, x, y) -> "cancel"
                    contains(hits.stop, x, y) -> "stop"
                    contains(hits.toggle, x, y) -> "toggle"
                    else -> null
                }
                rig.pointerUp()
                kick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                rig.pointerUp()
                kick()
                return true
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val pill = rig.pill
        if (pill.width < 1 || pill.height < 1) return
        canvas.save()
        canvas.translate(-originX.toFloat(), -originY.toFloat())
        canvas.scale(density, density)
        val scale = rig.scale.pos.toFloat()
        val cx = pill.centerX.toFloat()
        val cy = pill.centerY.toFloat()
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(scale, scale)
        canvas.translate(-cx, -cy)
        val expand = ((pill.height - 36.85) / (84.0 - 36.85)).coerceIn(0.0, 1.0)
        if (expand > 0.02) {
            shadow.alpha = (90 * expand).toInt()
            canvas.save()
            canvas.translate(0f, (8.0 * expand).toFloat())
            canvas.drawPath(continuousRect(pill, min(rig.radius.pos, pill.height / 2), 0.72), shadow)
            canvas.restore()
        }
        val sensor = continuousRect(rig.sensor, rig.sensor.height / 2, 0.72)
        val shell = continuousRect(pill, min(rig.radius.pos, min(pill.width, pill.height) / 2), 0.72)
        canvas.drawPath(sensor, black)
        canvas.drawPath(shell, black)
        val bubble = rig.bubble
        val bubblePath = bubble?.let { continuousRect(it, it.height / 2, 0.72) }
        if (bubblePath != null) canvas.drawPath(bubblePath, black)
        if (bubble != null) canvas.drawPath(bridgeNeck(pill, bubble), black)
        if (rig.showKeyline && rig.kind != Kind.IDLE) {
            val extra = ((pill.width - rig.sensor.width) / 6.0).coerceIn(0.0, 1.0)
            keyline.alpha = (0.16 * extra * (if (rig.kind == Kind.EXPANDED) 0.93 else 1.0) * 255).toInt()
            if (keyline.alpha > 2) {
                canvas.drawPath(shell, keyline)
                if (bubblePath != null) canvas.drawPath(bubblePath, keyline)
            }
        }
        canvas.restore()
        drawContent(canvas, pill, bubble)
        canvas.restore()
    }

    private fun drawContent(canvas: Canvas, pill: DRect, bubble: DRect?) {
        val face = faces[rig.primary?.id] ?: return
        if (rig.kind == Kind.IDLE) return
        val reveal = reveal()
        if (reveal <= 0f) return
        val slots = rig.slotRects()
        val clip = continuousRect(pill, min(rig.radius.pos, min(pill.width, pill.height) / 2), 0.72)
        val hole = Path()
        val opened = rig.hole.inflate(1.0)
        hole.addOval(RectF(opened.left.toFloat(), opened.top.toFloat(), opened.right.toFloat(), opened.bottom.toFloat()), Path.Direction.CW)
        clip.op(hole, Path.Op.DIFFERENCE)
        canvas.save()
        canvas.clipPath(clip)
        val alpha = (reveal * 255).toInt()
        val track = if (face.id == "now-playing") mediaTrack else null
        val live = if (track != null) IslandMedia.livePosition(track) else mediaPosition
        val duration = track?.duration ?: mediaDuration
        val elapsed = if (track != null) clock(live) else ""
        val remaining = if (track != null && duration > 0L) "−${clock((duration - live).coerceAtLeast(0))}" else ""
        val progress = if (track != null && duration > 0L) (live.toFloat() / duration).coerceIn(0f, 1f) else face.progress
        IslandModes.draw(
            canvas, face.id, face.title, face.detail, face.value, face.trail, face.accent, progress,
            face.glyph, elapsed, remaining, rig.kind, pill, rig.hole, slots.first, slots.second, rig.now, hits, alpha,
            art = track?.art,
        )
        canvas.restore()
        val second = faces[rig.secondary?.id]
        if (second != null && bubble != null && rig.kind == Kind.MINIMAL) {
            val bubbleClip = continuousRect(bubble, bubble.height / 2, 0.72)
            canvas.save()
            canvas.clipPath(bubbleClip)
            IslandModes.drawBubble(
                canvas, second.id, second.accent, second.progress, bubble, rig.now, alpha,
                art = if (second.id == "now-playing") mediaTrack?.art else null,
            )
            canvas.restore()
        }
    }

    private fun onTap(id: String) {
        when (pressedButton) {
            "decline" -> {
                if (liveCall) IslandMonitors.endCall(context) else dismissPreview()
                return
            }
            "cancel" -> {
                if (!liveTimer) dismissPreview()
                return
            }
            "stop" -> {
                dismissPreview()
                return
            }
            "accept" -> {
                if (liveCall) IslandMonitors.acceptCall(context) else accept()
                return
            }
            "pause" -> {
                if (liveTimer) return
                timerPaused = !timerPaused
                if (timerPaused) timerLeft = (timerEnds - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                else timerEnds = SystemClock.elapsedRealtime() + timerLeft
                refreshTimer()
                return
            }
            "toggle" -> {
                val face = faces["now-playing"] ?: return
                IslandMonitors.mediaAction(if (face.value == "Playing") "pause" else "play")
                return
            }
        }
        if (rig.expanded) return
        when (id) {
            "timer" -> {
                if (liveTimer) return
                timerPaused = !timerPaused
                if (timerPaused) timerLeft = (timerEnds - SystemClock.elapsedRealtime()).coerceAtLeast(0)
                else timerEnds = SystemClock.elapsedRealtime() + timerLeft
                refreshTimer()
            }
            "now-playing" -> {
                val face = faces["now-playing"] ?: return
                IslandMonitors.mediaAction(if (face.value == "Playing") "pause" else "play")
            }
            "recording" -> toggleRecord()
        }
    }

    private fun accept() {
        val face = faces["call"] ?: return
        callFrom = SystemClock.elapsedRealtime()
        face.value = "0:00"
        face.trail = "0:00"
        face.detail = "On the call"
        rig.collapse()
        rig.update(specOf(face))
        kick()
    }

    private fun toggleRecord() {
        recordPaused = !recordPaused
        if (recordPaused) recordHeld += SystemClock.elapsedRealtime() - recordEnds
        else recordEnds = SystemClock.elapsedRealtime()
        val face = faces["recording"] ?: return
        face.detail = if (recordPaused) "Paused" else "Recording"
        kick()
    }

    private fun advance() {
        val nowMs = SystemClock.elapsedRealtime()
        val timer = faces["timer"]
        if (timer != null) {
            val left = if (timerPaused) timerLeft else (timerEnds - nowMs).coerceAtLeast(0)
            if (left <= 0L && !timerPaused) {
                if (liveTimer) {
                    timer.trail = "0:00"
                    timer.value = "0:00"
                    timer.progress = 0f
                } else {
                    faces.remove("timer")
                    rig.end("timer")
                    timerDoneAt = rig.now + 6.4
                    present(face("timer-done", "timer", "", "0:00", "Time's up", "Timer", "0:00", Color.WHITE, -1f, 48.0, 60), alert = true)
                }
            } else {
                val label = clock(left)
                timer.trail = label
                timer.value = label
                timer.progress = left.toFloat() / timerSpan.coerceAtLeast(1L)
            }
        }
        if (timerDoneAt > 0 && rig.now >= timerDoneAt) {
            timerDoneAt = 0.0
            faces.remove("timer-done")
            rig.end("timer-done")
            if (sample == "timer") sample = null
        }
        val recording = faces["recording"]
        if (recording != null) {
            val elapsed = if (recordPaused) recordHeld else recordHeld + (nowMs - recordEnds)
            val label = clock(elapsed)
            recording.trail = label
            recording.value = label
        }
        val call = faces["call"]
        if (call != null && call.value != "Ring" && callFrom > 0L) {
            val label = clock(nowMs - callFrom)
            call.trail = label
            call.value = label
        }
        progress(faces["upload"], uploadStarted, 5000L)
        progress(faces["transfer"], transferStarted, 5000L)
        if ((liveMusic || sample == "music" || sample == "split") && nowMs - mediaAt > 1000L) {
            mediaAt = nowMs
            onMedia(IslandMedia.current(context))
        }
    }

    private fun progress(face: Face?, started: Long, duration: Long) {
        if (face == null || started <= 0L) return
        val fraction = ((SystemClock.elapsedRealtime() - started).toFloat() / duration).coerceIn(0f, 1f)
        face.progress = fraction
        val label = if (fraction >= 1f) "Done" else "${(fraction * 100).toInt()}%"
        face.trail = label
        face.value = label
        if (fraction >= 1f && SystemClock.elapsedRealtime() - started > duration + 1200) {
            if (sample == "upload" || sample == "send" || sample == "receive") dismissPreview()
        }
    }

    private fun refreshTimer() {
        val face = faces["timer"] ?: return
        val label = clock(if (timerPaused) timerLeft else (timerEnds - SystemClock.elapsedRealtime()).coerceAtLeast(0))
        face.trail = label
        face.value = label
        face.detail = if (timerPaused) "Paused" else "5 min timer"
        invalidate()
    }

    private fun armTimer() {
        timerPaused = false
        timerSpan = 5 * 60 * 1000L
        timerLeft = timerSpan
        timerEnds = SystemClock.elapsedRealtime() + timerLeft
        timerDoneAt = 0.0
        present(face("timer", "timer", "", "5:00", "Timer", "5 min timer", "5:00", 0xFFFF9F0A.toInt(), 1f, 50.0, 50))
    }

    private fun showTrack(track: IslandMedia.Track) {
        val state = if (track.playing) "Playing" else "Paused"
        val artist = track.artist.ifBlank { state }
        mediaTrack = track
        val live = IslandMedia.livePosition(track)
        mediaPosition = live
        mediaDuration = track.duration
        val fraction = if (track.duration > 0L) live.toFloat() / track.duration else 0f
        val existing = faces["now-playing"]
        if (existing != null && existing.title == track.title && existing.detail == artist && existing.value == state) {
            existing.progress = fraction
            return
        }
        present(face("now-playing", "note", "", "", track.title, artist, state, IslandModes.coverAccent(track.title), fraction, 144.0, 40))
    }

    private fun showBattery() {
        val battery = IslandMonitors.battery(context)
        val level = (battery["level"] as? Number)?.toInt() ?: 0
        val scale = (battery["scale"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 100
        val percent = (level * 100 / scale).coerceIn(0, 100)
        val charging = battery["charging"] == true
        val title = when {
            charging && percent >= 100 -> "Charged"
            charging -> "Charging"
            else -> "Battery"
        }
        val detail = when {
            charging -> "Plugged in"
            percent < 20 -> "Low battery"
            else -> "Not charging"
        }
        val accent = when {
            charging -> 0xFF53FA6D.toInt()
            percent < 20 -> 0xFFFF453A.toInt()
            else -> Color.WHITE
        }
        present(face("battery", "battery", title, "$percent%", title, detail, "$percent%", accent, -1f, 48.0, 10))
    }

    private fun present(face: Face, alert: Boolean = false) {
        val fresh = !faces.containsKey(face.id)
        faces[face.id] = face
        val spec = specOf(face)
        if (fresh) rig.start(spec, alert) else rig.update(spec, alert)
        kick()
    }

    private fun toggle(key: String, show: () -> Unit) {
        if (sample == key) {
            dismissPreview()
            return
        }
        if (liveOwns(key)) return
        dismissPreview()
        sample = key
        show()
    }

    private fun liveOwns(key: String) = when (key) {
        "music" -> liveMusic
        "call" -> liveCall
        "message" -> liveMessage
        "timer" -> liveTimer
        else -> false
    }

    private fun dismissPreview() {
        val keep = buildSet {
            if (liveMusic) add("now-playing")
            if (liveCall) add("call")
            if (liveMessage) add("message")
            if (liveTimer) add("timer")
        }
        if (sample?.startsWith("record") == true) releaseMic()
        sample = null
        for (id in faces.keys.filter { it !in keep }) {
            faces.remove(id)
            rig.end(id)
        }
        if (!liveTimer) timerDoneAt = 0.0
        if ("upload" !in keep) uploadStarted = 0L
        if ("transfer" !in keep) transferStarted = 0L
        kick()
    }

    private fun releaseMic() {
        if (!micOn) return
        micOn = false
        IslandMonitors.stopMic()
    }

    private fun specOf(face: Face): Spec {
        val (leadW, trailW) = when (face.id) {
            "timer", "timer-done" -> 22.0 to 48.0
            "now-playing" -> 22.0 to 24.0
            "call" -> if (face.value == "Ring") 22.0 to 22.0 else 66.0 to 66.0
            "battery" -> 72.0 to 72.0
            "eta" -> 22.0 to 56.0
            "recording" -> if (face.title == "Voice memo") 36.0 to 48.0 else 22.0 to 48.0
            "upload" -> 24.0 to 22.0
            "transfer", "message" -> 22.0 to 22.0
            "calendar", "order-a", "order-b" -> 22.0 to 52.0
            else -> {
                val lead = when {
                    face.lead.isNotEmpty() -> compactPaint.measureText(face.lead).toDouble().coerceAtMost(72.0)
                    face.glyph.isNotEmpty() -> 22.0
                    else -> 0.0
                }
                val trail = if (face.trail.isEmpty()) 0.0 else compactPaint.measureText(face.trail).toDouble().coerceAtMost(72.0)
                lead to trail
            }
        }
        return Spec(face.id, face.priority, 0, face.group, leadW, trailW, face.minimal, face.body)
    }

    private fun reveal(): Float {
        val t = rig.now - revealAt
        if (t < 0.16) return 0f
        return ((t - 0.16) / 0.22).toFloat().coerceIn(0f, 1f)
    }

    private var revealAt = 0.0
    private var revealKind = Kind.IDLE

    private fun publish() {
        if (rig.kind != revealKind) {
            revealKind = rig.kind
            revealAt = rig.now
        }
        val union = boundsDp()
        val left = floor(union.left * density).toInt() - 2
        val top = floor(union.top * density).toInt() - 2
        val right = ceil(union.right * density).toInt() + 2
        val bottom = ceil(union.bottom * density).toInt() + 2
        originX = left
        originY = top
        val width = (right - left).coerceAtLeast(1)
        val height = (bottom - top).coerceAtLeast(1)
        if (left == appliedL && top == appliedT && width == appliedW && height == appliedH) return
        appliedL = left
        appliedT = top
        appliedW = width
        appliedH = height
        onWindow?.invoke(left, top, width, height)
    }

    private fun boundsDp(): DRect {
        val pill = scaled(rig.pill)
        val bubble = rig.bubble?.let { scaled(it) }
        if (bubble == null) return pill
        return DRect(min(pill.left, bubble.left), min(pill.top, bubble.top), max(pill.right, bubble.right), max(pill.bottom, bubble.bottom))
    }

    private fun scaled(rect: DRect): DRect {
        val scale = rig.scale.pos
        val w = rect.width * scale
        val h = rect.height * scale
        return DRect(rect.centerX - w / 2, rect.centerY - h / 2, rect.centerX + w / 2, rect.centerY + h / 2)
    }

    private fun ticking(): Boolean = faces.keys.any { it == "timer" || it == "timer-done" || it == "recording" || it == "call" || it == "upload" || it == "transfer" || it == "now-playing" }

    private fun kick() {
        if (posted) return
        posted = true
        choreographer.postFrameCallback(frame)
    }

    private fun face(id: String, glyph: String, lead: String, trail: String, title: String, detail: String, value: String, accent: Int, progress: Float, body: Double, priority: Int, group: String? = null) =
        Face(id, glyph, lead, trail, title, detail, value, accent, progress, body, priority, group)

    private fun clock(ms: Long): String {
        val total = (ms / 1000L).toInt()
        return "%d:%02d".format(total / 60, total % 60)
    }

    private fun text(size: Float, color: Int, weight: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = size
        typeface = Typeface.create(Typeface.create("sans-serif", Typeface.NORMAL), weight, false)
        letterSpacing = -0.2f / size
        fontFeatureSettings = "tnum"
    }

    private fun contains(rect: DRect, x: Double, y: Double) = x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom

    private class Face(
        val id: String,
        val glyph: String,
        var lead: String,
        var trail: String,
        var title: String,
        var detail: String,
        var value: String,
        val accent: Int,
        var progress: Float,
        val body: Double,
        val priority: Int,
        val group: String?,
        val minimal: Double = 22.0,
    )
}
