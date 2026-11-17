package dev.islandcn.island

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.Choreographer
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import kotlin.math.abs
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

    /** Display width in px, from the same metrics as the camera. */
    internal var screenPx: Int = 0

    /** Camera center in px. */
    internal var cameraPx: Int = 0

    /** The window is symmetric about the camera, so its left is derived from its width. */
    internal var windowCentered: Boolean = true

    private val density = resources.displayMetrics.density
    private val rig = IslandRig()
    private val faces = linkedMapOf<String, Face>()
    private var sample: String? = null
    private var originX = 0
    private var originY = 0
    private val screenLoc = IntArray(2)
    /** Last real window edge. A 0,0 layout pass must not replace it. */
    private var placedX = Float.NaN
    private var placedY = Float.NaN
    private var appliedL = Int.MIN_VALUE
    private var appliedT = Int.MIN_VALUE
    private var appliedW = Int.MIN_VALUE
    private var appliedH = Int.MIN_VALUE
    private var posted = false
    private var wakeUntil = 0L
    private var wakeGen = 0
    private var screenLit = true
    private var keptAmbient: Int? = null
    private var keptSpot: Int? = null
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
    private var liveProgress = false
    private var liveRide = false
    private var liveStatus = false
    private var liveBattery = false
    private var liveLowBattery = false
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
    private val mask = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    }
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
        syncLock()
        if (wakeUntil > 0L && SystemClock.elapsedRealtime() >= wakeUntil) {
            val gen = wakeGen
            wakeUntil = 0L
            if (rig.wakeCurrent(gen)) rig.collapse()
        }
        publish()
        invalidate()
        if (rig.animating || ticking() || wakeUntil > SystemClock.elapsedRealtime()) kick()
    }

    init {
        isClickable = true
        isFocusable = false
        isFocusableInTouchMode = false
        rig.onPressed = { id -> onTap(id) }
        rig.canExpand = { id -> id != "lock" || !lockAuthenticated }
        IslandModes.install(context)
    }

    fun windowFrame(): IntArray {
        publish()
        return intArrayOf(appliedL, appliedT, appliedW.coerceAtLeast(1), appliedH.coerceAtLeast(1))
    }

    /**
     * Screen off, AOD, and the screensaver draw a flat pill: no keyline, shadow, or halo.
     * A pill that was already bordered stays as it is.
     * Gaining the border is the wake: expand a visible activity for about two seconds.
     */
    fun setScreenLit(on: Boolean) {
        if (screenLit == on) return
        screenLit = on
        elevation = 0f
        translationZ = 0f
        stateListAnimator = null
        if (Build.VERSION.SDK_INT >= 28) {
            if (!on) {
                if (keptAmbient == null) {
                    keptAmbient = outlineAmbientShadowColor
                    keptSpot = outlineSpotShadowColor
                }
                outlineAmbientShadowColor = Color.TRANSPARENT
                outlineSpotShadowColor = Color.TRANSPARENT
            } else {
                keptAmbient?.let { outlineAmbientShadowColor = it }
                keptSpot?.let { outlineSpotShadowColor = it }
            }
        }
        if (Build.VERSION.SDK_INT >= 31) setRenderEffect(null)
        invalidate()
        kick()
        if (on) onScreenWake()
    }

    /** The border just came back. Open the current activity for about two seconds. */
    fun onScreenWake() {
        if (clock0 == 0L) clock0 = SystemClock.uptimeMillis()
        val t = (SystemClock.uptimeMillis() - clock0) / 1000.0
        val gen = rig.beginWake(t) ?: return
        wakeGen = gen
        wakeUntil = SystemClock.elapsedRealtime() + 2000L
        kick()
    }

    fun bindCutout() {
        val window = context.getSystemService(WindowManager::class.java) ?: return
        val metrics = window.currentWindowMetrics
        val bounds = metrics.bounds
        screenPx = bounds.width()
        val cutouts = metrics.windowInsets.displayCutout?.boundingRects.orEmpty().map { rect ->
            DRect(rect.left / density.toDouble(), rect.top / density.toDouble(), rect.right / density.toDouble(), rect.bottom / density.toDouble())
        }
        rig.bindHole(bounds.width() / density.toDouble(), bounds.height() / density.toDouble(), cutouts)
        cameraPx = Math.round(rig.centerX * density).toInt()
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

    fun onLiveCall(state: String, label: String, icon: android.graphics.Bitmap? = null) {
        if (icon != null) callIcon = icon
        when (state) {
            "ringing" -> showLiveCall(ringing = true, label = label)
            "offhook" -> showLiveCall(ringing = false, label = label)
            else -> {
                val missedName = callerLabel
                val wasRinging = faces["call"]?.value == "Ring"
                liveCall = false
                callerLabel = ""
                callFrom = 0L
                if (sample == "call") return
                if (wasRinging && missedName.isNotBlank()) {
                    showMissed(missedName, callIcon)
                    return
                }
                if (faces["call"]?.value == "Missed") return
                if (faces.remove("call") != null) {
                    rig.end("call")
                    kick()
                }
            }
        }
    }

    fun onMissedCall(name: String, icon: android.graphics.Bitmap? = null) {
        if (liveCall) return
        showMissed(name, icon)
    }

    fun onMissedCallCleared() {
        if (liveCall || sample == "call") return
        val face = faces["call"] ?: return
        if (face.value != "Missed") return
        faces.remove("call")
        rig.end("call")
        kick()
    }

    private fun showMissed(name: String, icon: android.graphics.Bitmap? = null) {
        val title = name.trim().ifBlank { "Unknown" }
        val existing = faces["call"]
        if (existing != null && existing.value == "Missed" && existing.title == title) {
            existing.icon = icon ?: existing.icon
            return
        }
        present(face("call", "missed", "", "", title, "Missed call", "Missed", Color.WHITE, -1f, 80.0, 90).also { it.icon = icon }, alert = existing == null)
    }

    fun onLiveMessage(sender: String, body: String, icon: android.graphics.Bitmap? = null) {
        val title = sender.trim()
        val text = body.trim()
        if (title.isEmpty() && text.isEmpty()) return
        liveMessage = true
        if (sample == "message") sample = null
        val existing = faces["message"]
        if (existing != null && existing.title == title && existing.detail == text) {
            existing.icon = icon ?: existing.icon
            return
        }
        present(face("message", "message", "", "", title, text, "", Color.WHITE, -1f, IslandModes.messageBody(text), 80).also { it.icon = icon }, alert = existing == null)
    }

    fun onLiveMessageCleared() {
        liveMessage = false
        if (sample == "message") return
        if (faces.remove("message") != null) {
            rig.end("message")
            kick()
        }
    }

    fun onLiveTimer(remainingMs: Long, paused: Boolean, label: String, icon: android.graphics.Bitmap? = null) {
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
            existing.icon = icon ?: existing.icon
            kick()
            return
        }
        present(face("timer", "timer", "", text, caption, if (paused) "Paused" else caption, text, 0xFFFF9F0A.toInt(), left.toFloat() / timerSpan.coerceAtLeast(1L), 50.0, 50).also { it.icon = icon })
    }

    fun onLiveProgress(title: String, detail: String, fraction: Float, label: String, icon: android.graphics.Bitmap? = null) {
        liveProgress = true
        if (sample == "upload") return
        val name = title.trim().ifBlank { "Progress" }
        val text = detail.trim()
        val existing = faces["upload"]
        if (existing != null && existing.title == name && existing.detail == text && existing.value == label && existing.progress == fraction) {
            existing.icon = icon ?: existing.icon
            return
        }
        present(face("upload", "upload", "", label, name, text, label, 0xFF0A84FF.toInt(), fraction.coerceIn(0f, 1f), 48.0, 30).also { it.icon = icon })
    }

    fun onLiveProgressCleared() {
        liveProgress = false
        if (sample == "upload") return
        if (faces.remove("upload") != null) {
            rig.end("upload")
            kick()
        }
    }

    fun onLiveRide(title: String, detail: String, value: String, delivery: Boolean, icon: android.graphics.Bitmap? = null) {
        liveRide = true
        if (sample == "delivery" || sample == "ride") return
        val name = title.trim().ifBlank { if (delivery) "Delivery" else "On the way" }
        val text = detail.trim()
        val whenText = value.trim().ifBlank { text }.ifBlank { name }
        val accent = if (delivery) 0xFFFFD60A.toInt() else 0xFF32D74B.toInt()
        val glyph = if (delivery) "box" else "car"
        val existing = faces["eta"]
        if (existing != null && existing.title == name && existing.detail == text && existing.value == whenText && existing.glyph == glyph) {
            existing.icon = icon ?: existing.icon
            return
        }
        present(face("eta", glyph, "", whenText, name, text, whenText, accent, -1f, 132.0, 25).also { it.icon = icon })
    }

    fun onLiveRideCleared() {
        liveRide = false
        if (sample == "delivery" || sample == "ride") return
        if (faces.remove("eta") != null) {
            rig.end("eta")
            kick()
        }
    }

    fun onLiveStatus(title: String, detail: String, icon: android.graphics.Bitmap? = null) {
        liveStatus = true
        val label = shortLabel(title, detail)
        if (label.isEmpty()) return
        val subtitle = shortSubtitle(detail, label)
        val existing = faces["live"]
        if (existing != null && existing.title == label && existing.detail == subtitle && existing.trail.isEmpty()) {
            existing.icon = icon ?: existing.icon
            return
        }
        present(face("live", "screen", "", "", label, subtitle, "", Color.WHITE, -1f, 64.0, 15).also { it.icon = icon })
    }

    fun onLiveStatusCleared() {
        liveStatus = false
        if (faces.remove("live") != null) {
            rig.end("live")
            kick()
        }
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

    private var callIcon: android.graphics.Bitmap? = null

    private fun showLiveCall(ringing: Boolean, label: String) {
        liveCall = true
        if (sample == "call") sample = null
        val incoming = label.trim()
        if (incoming.isNotEmpty()) callerLabel = incoming
        val title = callerLabel.ifBlank { "Incoming call" }
        val existing = faces["call"]
        if (ringing) {
            callFrom = 0L
            if (existing != null && existing.value == "Ring" && existing.title == title) {
                existing.icon = callIcon ?: existing.icon
                return
            }
            if (existing != null && existing.value == "Ring") {
                existing.title = title
                existing.icon = callIcon ?: existing.icon
                kick()
                return
            }
            present(face("call", "phone", "", "Ring", title, "Audio call", "Ring", 0xFF31D259.toInt(), -1f, 80.0, 100).also { it.icon = callIcon }, alert = true)
            return
        }
        if (existing != null && existing.value != "Ring") {
            if (incoming.isNotEmpty()) existing.title = title
            existing.icon = callIcon ?: existing.icon
            kick()
            return
        }
        if (callFrom == 0L) callFrom = SystemClock.elapsedRealtime()
        val elapsed = clock(SystemClock.elapsedRealtime() - callFrom)
        present(face("call", "phone", "", elapsed, title, "On the call", elapsed, 0xFF76FB73.toInt(), -1f, 80.0, 100).also { it.icon = callIcon })
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
                present(face("call", "phone", "", "Ring", caller, "Audio call", "Ring", 0xFF31D259.toInt(), -1f, 80.0, 100), alert = true)
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

    private var lockAuthenticated = false
    private var lockUnlockAt = Double.NaN
    private var lockUnlockPending = false

    /**
     * Keyguard showing. [deviceLocked] is false once authentication has succeeded
     * and the lock screen is still up. That edge swaps the icon and, if Face ID
     * is open, plays the shackle while the card collapses.
     */
    fun onKeyguard(showing: Boolean, deviceLocked: Boolean) {
        if (!showing) {
            lockAuthenticated = false
            lockUnlockAt = Double.NaN
            lockUnlockPending = false
            if (faces.remove("lock") == null) return
            rig.end("lock")
            kick()
            return
        }
        val authenticated = !deviceLocked
        val existing = faces["lock"]
        if (existing == null) {
            lockAuthenticated = authenticated
            lockUnlockAt = Double.NaN
            lockUnlockPending = false
            present(face("lock", "lock", "", "", "Locked", "", "", Color.WHITE, if (authenticated) 1f else -1f, 90.0, 200))
            return
        }
        if (authenticated == lockAuthenticated) return
        lockAuthenticated = authenticated
        if (!authenticated) {
            lockUnlockAt = Double.NaN
            lockUnlockPending = false
            existing.progress = -1f
            kick()
            return
        }
        // Start the shackle on the next frame, after the clock catches up.
        lockUnlockPending = true
        lockUnlockAt = Double.NaN
        existing.progress = 0f
        if (rig.expanded && rig.shownId() == "lock") rig.collapse()
        kick()
    }

    /** Shackle opens over a short beat, then stays open until the lock screen leaves. */
    private fun syncLock() {
        val face = faces["lock"] ?: return
        if (!lockAuthenticated) {
            face.progress = -1f
            return
        }
        if (lockUnlockPending) {
            lockUnlockPending = false
            lockUnlockAt = rig.now
        }
        if (lockUnlockAt.isNaN()) {
            face.progress = 1f
            return
        }
        val t = ((rig.now - lockUnlockAt) / 0.55).toFloat()
        if (t >= 1f) {
            lockUnlockAt = Double.NaN
            face.progress = 1f
            return
        }
        val u = t.coerceIn(0f, 1f)
        face.progress = u * u * (3f - 2f * u)
    }

    fun onTorch(level: Float, on: Boolean) {
        if (torchDrag) return
        if (!on) {
            if (faces.remove("torch") == null) return
            rig.end("torch")
            kick()
            return
        }
        val existing = faces["torch"]
        if (existing != null) {
            existing.progress = level
            kick()
            return
        }
        present(face("torch", "torch", "", "", "Flashlight", "", "", 0xFFFFD60A.toInt(), level, 80.0, 36))
    }

    private var torchDrag = false

    private fun dragTorch(x: Double) {
        val track = hits.slider
        if (track.width < 1.0) return
        val fraction = ((x - track.left) / track.width).toFloat().coerceIn(0f, 1f)
        faces["torch"]?.progress = fraction
        IslandMonitors.setTorchFraction(fraction)
        kick()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val origin = placeOrigin()
        val x = (event.x + origin[0]) / density.toDouble()
        val y = (event.y + origin[1]) / density.toDouble()
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (rig.look() == Kind.EXPANDED && rig.shownId() == "torch" && hit(hits.slider, x, y)) {
                    torchDrag = true
                    dragTorch(x)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (torchDrag) {
                    dragTorch(x)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (torchDrag) {
                    torchDrag = false
                    if (event.actionMasked == MotionEvent.ACTION_UP) dragTorch(x)
                    return true
                }
            }
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val bubble = rig.bubble
                val onBubble = bubble != null && contains(bubble, x, y)
                val onPill = contains(rig.pill, x, y)
                if (!onBubble && !onPill) {
                    if (rig.expanded) {
                        rig.collapse()
                        kick()
                    }
                    return true
                }
                val activity = if (onBubble) rig.secondary?.id else rig.primary?.id
                // The settled unlock pill has no expanded state. Another activity still opens.
                if (!rig.expanded && activity == "lock" && !rig.canExpand("lock")) {
                    return true
                }
                rig.pointerDown(if (onBubble) "bubble" else "pill")
                kick()
                return true
            }
            MotionEvent.ACTION_UP -> {
                pressedButton = when {
                    !rig.expanded -> null
                    hit(hits.decline, x, y) -> "decline"
                    hit(hits.accept, x, y) -> "accept"
                    hit(hits.pause, x, y) -> "pause"
                    hit(hits.cancel, x, y) -> "cancel"
                    hit(hits.stop, x, y) -> "stop"
                    hit(hits.like, x, y) -> "like"
                    hit(hits.previous, x, y) -> "previous"
                    hit(hits.next, x, y) -> "next"
                    hit(hits.toggle, x, y) -> "toggle"
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
        // Screen edge of this window, not the compact left publish just computed.
        // On collapse settle the width is already the compact one while the surface
        // is still the expanded window; predicting left from that width slides the pill.
        val origin = placeOrigin()
        canvas.translate(-origin[0], -origin[1])
        canvas.scale(density, density)
        val expand = ((pill.height - 36.85) / (84.0 - 36.85)).coerceIn(0.0, 1.0)
        black.isAntiAlias = screenLit
        canvas.drawPath(continuousRect(rig.sensor, rig.sensor.height / 2, 0.72), black)
        val shell = continuousRect(pill, min(rig.radius.pos, min(pill.width, pill.height) / 2), 0.72)
        // The other half stays until it has slid under the main bar and the two are one shape.
        // Painting it first is what puts that lobe behind the bar instead of across its face.
        val bubble = rig.bubble
        val bubblePath = bubble?.let { continuousRect(it, it.height / 2, 0.72) }
        if (bubble != null && bubblePath != null) {
            around(canvas, bubble, rig.bubbleScale.pos.toFloat()) {
                canvas.drawPath(bubblePath, black)
            }
        }
        val neckGap = if (rig.tightNeck()) 0.01 else 5.0
        if (bubble != null) canvas.drawPath(bridgeNeck(pill, bubble, neckGap), black)
        val shown = rig.look()
        if (screenLit && rig.showKeyline && shown != Kind.IDLE && bubble != null && bubblePath != null) {
            val extra = ((pill.width - rig.sensor.width) / 6.0).coerceIn(0.0, 1.0)
            keyline.alpha = (0.16 * extra * 255).toInt()
            if (keyline.alpha > 2) {
                around(canvas, bubble, rig.bubbleScale.pos.toFloat()) { canvas.drawPath(bubblePath, keyline) }
            }
        }
        drawBubbleContent(canvas, bubble)
        around(canvas, pill, rig.pillScale.pos.toFloat()) {
            if (screenLit && expand > 0.02) {
                shadow.alpha = (90 * expand).toInt()
                canvas.save()
                canvas.translate(0f, (8.0 * expand).toFloat())
                canvas.drawPath(shell, shadow)
                canvas.restore()
            }
            canvas.drawPath(shell, black)
        }
        if (screenLit && rig.showKeyline && shown != Kind.IDLE) {
            val extra = ((pill.width - rig.sensor.width) / 6.0).coerceIn(0.0, 1.0)
            keyline.alpha = (0.16 * extra * (if (shown == Kind.EXPANDED) 0.93 else 1.0) * 255).toInt()
            if (keyline.alpha > 2) {
                around(canvas, pill, rig.pillScale.pos.toFloat()) { canvas.drawPath(shell, keyline) }
            }
        }
        drawContent(canvas, pill)
        canvas.restore()
    }

    /** Keep [draw] inside [path]. clipPath was dropping the opened card's body. */
    private fun drawInside(canvas: Canvas, path: Path, draw: () -> Unit) {
        val bounds = RectF()
        path.computeBounds(bounds, true)
        if (bounds.width() < 1f || bounds.height() < 1f) {
            draw()
            return
        }
        val layer = canvas.saveLayer(bounds, null)
        draw()
        canvas.drawPath(path, mask)
        canvas.restoreToCount(layer)
    }

    private fun around(canvas: Canvas, rect: DRect, scale: Float, draw: () -> Unit) {
        if (scale == 1f) {
            draw()
            return
        }
        canvas.save()
        canvas.translate(rect.centerX.toFloat(), rect.centerY.toFloat())
        canvas.scale(scale, scale)
        canvas.translate(-rect.centerX.toFloat(), -rect.centerY.toFloat())
        draw()
        canvas.restore()
    }

    /** The detached half's icon, drawn before the main bar so a hold carries it underneath. */
    private fun drawBubbleContent(canvas: Canvas, bubble: DRect?) {
        if (bubble == null || rig.look() != Kind.MINIMAL) return
        val second = faces[rig.secondary?.id] ?: return
        val reveal = reveal()
        if (reveal <= 0f) return
        val alpha = (reveal * 255).toInt()
        around(canvas, bubble, rig.bubbleScale.pos.toFloat()) {
            val bubblePlaying = second.id == "now-playing" && (mediaTrack?.playing == true || (mediaTrack == null && second.value == "Playing"))
            drawInside(canvas, continuousRect(bubble, bubble.height / 2, 0.72)) {
                IslandModes.drawBubble(
                    canvas, second.id, second.accent, second.progress, bubble, rig.now, alpha,
                    art = if (second.id == "now-playing") mediaTrack?.art else null,
                    mark = second.glyph,
                    playing = bubblePlaying,
                    icon = second.icon,
                    charge = second.value,
                    charging = second.title == "Charging" || second.title == "Charged",
                )
            }
        }
    }

    private fun drawContent(canvas: Canvas, pill: DRect) {
        val look = rig.look()
        val faceId = when (look) {
            Kind.EXPANDED -> rig.shownId()
            Kind.COMPACT -> if (rig.expanded) rig.shownId() else rig.primary?.id
            else -> rig.primary?.id
        }
        val face = faces[faceId] ?: return
        if (look == Kind.IDLE) return
        val reveal = reveal()
        if (reveal <= 0f) return
        // Absorb keeps the expanded row at 0 until the card has started opening, then fades it in.
        val presence = if (look == Kind.EXPANDED) rig.enterAlpha() else 1f
        val alpha = (reveal * presence * 255).toInt()
        if (alpha <= 0) return
        around(canvas, pill, rig.pillScale.pos.toFloat()) {
            // clipPath on the opened card was discarding this draw after the window
            // stayed centered. Mask with the same path the shell fills, so the body paints.
            drawInside(canvas, continuousRect(pill, min(rig.radius.pos, min(pill.width, pill.height) / 2), 0.72)) {
                paintFace(canvas, face, look, pill, alpha, clip = false, slots = rig.slotRects())
            }
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
            "previous" -> {
                IslandMonitors.mediaAction("previous")
                return
            }
            "next" -> {
                IslandMonitors.mediaAction("next")
                return
            }
            "like" -> {
                IslandLinks.like()
                return
            }
        }
        if (rig.look() == Kind.EXPANDED) {
            val openId = rig.shownId() ?: id
            if (IslandLinks.open(context, openId)) {
                rig.collapse()
                kick()
            }
            return
        }
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
        val previous = mediaTrack
        // A session that repeats the same position with a fresh timestamp would rewind the bar.
        // Keep the clock that is already ahead unless this is a seek or a new track.
        val adopted = if (
            previous != null &&
            track.playing &&
            previous.playing &&
            previous.title == track.title &&
            previous.duration > 0L &&
            track.position >= previous.position - 500L &&
            IslandMedia.livePosition(track) + 800L < IslandMedia.livePosition(previous)
        ) {
            previous.copy(
                liked = track.liked,
                art = track.art ?: previous.art,
                duration = if (track.duration > 0L) track.duration else previous.duration,
            )
        } else {
            track
        }
        mediaTrack = adopted
        val live = IslandMedia.livePosition(adopted)
        mediaPosition = live
        mediaDuration = adopted.duration
        val fraction = if (track.duration > 0L) live.toFloat() / track.duration else 0f
        val existing = faces["now-playing"]
        if (existing != null && existing.title == track.title && existing.detail == artist && existing.value == state) {
            existing.progress = fraction
            return
        }
        present(face("now-playing", "note", "", "", track.title, artist, state, IslandModes.coverAccent(track.title), fraction, 144.0, 40))
    }

    fun onLiveLowBattery(percent: Int, message: String) {
        liveLowBattery = true
        val level = if (percent in 0..100) percent else {
            val battery = IslandMonitors.battery(context)
            val raw = (battery["level"] as? Number)?.toInt() ?: 0
            val scale = (battery["scale"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 100
            (raw * 100 / scale).coerceIn(0, 100)
        }
        val text = message.trim().ifBlank { "Battery is low" }
        val label = "$level%"
        val existing = faces["low-battery"]
        if (existing != null && existing.value == label && existing.detail == text) return
        present(face("low-battery", "battery", label, "", "Battery", text, label, 0xFFFF453A.toInt(), level / 100f, 78.0, 32))
    }

    fun onLiveLowBatteryCleared() {
        liveLowBattery = false
        if (faces.remove("low-battery") != null) {
            rig.end("low-battery")
            kick()
        }
    }

    fun onLiveBattery(percent: Int, charging: Boolean) {
        liveBattery = true
        if (sample == "battery") sample = null
        val next = batteryFace(percent.coerceIn(0, 100), charging)
        val existing = faces["battery"]
        if (existing != null) {
            if (existing.title == next.title && existing.detail == next.detail && existing.value == next.value) return
            existing.lead = next.lead
            existing.trail = next.trail
            existing.title = next.title
            existing.detail = next.detail
            existing.value = next.value
            rig.update(specOf(existing))
            kick()
            return
        }
        present(next)
    }

    fun onLiveBatteryCleared() {
        liveBattery = false
        if (sample == "battery") return
        if (faces.remove("battery") != null) {
            rig.end("battery")
            kick()
        }
    }

    private fun showBattery() {
        val battery = IslandMonitors.battery(context)
        val level = (battery["level"] as? Number)?.toInt() ?: 0
        val scale = (battery["scale"] as? Number)?.toInt()?.coerceAtLeast(1) ?: 100
        val percent = (level * 100 / scale).coerceIn(0, 100)
        present(batteryFace(percent, battery["charging"] == true || battery["plugged"] == true))
    }

    private fun batteryFace(percent: Int, charging: Boolean): Face {
        val title = when {
            charging && percent >= 100 -> "Charged"
            charging -> "Charging"
            else -> "Battery"
        }
        val detail = when {
            charging && percent >= 100 -> "Fully charged"
            charging -> "Plugged in"
            percent < 20 -> "Low battery"
            else -> "Not charging"
        }
        val accent = when {
            charging -> 0xFF53FA6D.toInt()
            percent < 20 -> 0xFFFF453A.toInt()
            else -> Color.WHITE
        }
        return face("battery", "battery", title, "$percent%", title, detail, "$percent%", accent, -1f, 48.0, 10)
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
        "upload" -> liveProgress
        "delivery", "ride" -> liveRide
        "battery" -> liveBattery
        "low-battery" -> liveLowBattery
        else -> false
    }

    private fun dismissPreview() {
        val keep = buildSet {
            if (liveMusic) add("now-playing")
            if (liveCall) add("call")
            if (liveMessage) add("message")
            if (liveTimer) add("timer")
            if (liveProgress) add("upload")
            if (liveRide) add("eta")
            if (liveStatus) add("live")
            if (liveBattery) add("battery")
            if (liveLowBattery) add("low-battery")
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
        val preset = when (face.id) {
            "timer", "timer-done" -> 22.0 to 48.0
            "now-playing" -> 22.0 to 24.0
            "call" -> when (face.value) {
                "Missed" -> 22.0 to 0.0
                "Ring" -> 22.0 to 22.0
                else -> 66.0 to 66.0
            }
            "lock", "torch" -> 22.0 to 0.0
            "battery", "low-battery" -> 72.0 to 72.0
            "eta" -> 22.0 to 56.0
            "recording" -> if (face.title == "Voice memo") 36.0 to 48.0 else 22.0 to 48.0
            "upload" -> 24.0 to 22.0
            "transfer", "message" -> 22.0 to 22.0
            "calendar", "order-a", "order-b" -> 22.0 to 52.0
            "live" -> 0.0 to 0.0
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
        val caption = IslandModes.compactCaption(face.title, face.detail, face.value, face.trail)
        // An icon takes the leading ear and the short label sits in the trailing one. Glyph-only
        // ears keep their preset box. A missed call, the lock, and the torch are an icon alone.
        val iconOnly = face.id == "lock" || face.id == "torch" || (face.id == "call" && face.value == "Missed")
        val captionInTrail = !iconOnly && face.icon != null && face.id != "live" && face.id != "now-playing" && face.id != "battery" && face.id != "upload" && face.id != "transfer"
        val trailW = if (captionInTrail && caption.isNotEmpty()) max(preset.second, IslandModes.compactTextWidth(caption)) else preset.second
        val chargeText = if (face.id == "battery" || face.id == "low-battery") IslandModes.percentWidth(face.value) else 0.0
        val widgetTrail = face.id == "now-playing" || face.id == "upload" || face.id == "transfer" || face.id == "battery" || face.id == "low-battery" || face.id == "live" || face.id == "call"
        val splitTrail = if (!widgetTrail && face.trail.isNotEmpty() && trailW > 0) min(trailW, IslandModes.compactTextWidth(face.trail)) else 0.0
        return Spec(face.id, face.priority, 0, face.group, preset.first, trailW, face.minimal, face.body, chargeText, splitTrail)
    }

    private fun reveal(): Float {
        val t = rig.now - revealAt
        if (t < 0.16) return 0f
        return ((t - 0.16) / 0.22).toFloat().coerceIn(0f, 1f)
    }

    private var revealAt = 0.0
    private var revealKind = Kind.IDLE

    /** Held for a whole spring so the window does not follow subpixel edges. */
    private var latch: DRect? = null

    /**
     * Where this window actually is. Collapse settle publishes the compact rect
     * (live: left 495, top 22, 354×138) while the surface is still the expanded
     * one (left 68, top 6, 1208×386). Deriving the origin from the new width
     * draws the pill 427px to the left for that frame.
     */
    private fun placeOrigin(): FloatArray {
        if (isAttachedToWindow) {
            getLocationOnScreen(screenLoc)
            val sx = screenLoc[0].toFloat()
            val sy = screenLoc[1].toFloat()
            if (!(sx == 0f && sy == 0f && (originX > 2 || originY > 2))) {
                placedX = sx
                placedY = sy
                return floatArrayOf(sx, sy)
            }
        }
        if (!placedX.isNaN()) return floatArrayOf(placedX, placedY)
        return floatArrayOf(originX.toFloat(), originY.toFloat())
    }

    private fun publish() {
        if (rig.kind != revealKind) {
            val absorb = rig.absorbLive
            revealKind = rig.kind
            if (!absorb) revealAt = rig.now
        }
        // The window is the settled pill. It changes when the target changes, not
        // on every spring sample, and a running spring keeps the larger of the two
        // so the drawing is never clipped and the center does not step.
        val target = rig.windowBounds()
        val cover = if (rig.animating) {
            val held = latch
            if (held == null) latch = union(target, drawnBounds())
            else if (!encloses(held, target)) latch = union(held, target)
            latch ?: target
        } else {
            latch = null
            target
        }
        val top = floor(cover.top * density).toInt()
        val bottom = ceil(cover.bottom * density).toInt()
        val height = (bottom - top).coerceAtLeast(1)
        val left: Int
        val width: Int
        val leftExtent = rig.centerX - cover.left
        val rightExtent = cover.right - rig.centerX
        // A longer left ear (lock, torch, missed call) used to publish an exact
        // rect whose x had to slide left of the camera. On the hugged capsule that
        // slide leaves the window's left edge on the camera, so the left ear is
        // cut flat and only the right semicircle remains. Center on the camera and
        // cover the longer ear on both sides. The drawn pill stays the tight one.
        val symmetric = abs(rightExtent - leftExtent) <= 1.0 || leftExtent > rightExtent + 1.0
        if (!symmetric) {
            left = floor(cover.left * density).toInt()
            val right = ceil(cover.right * density).toInt()
            width = (right - left).coerceAtLeast(1)
        } else {
            val camX = Math.round(rig.centerX * density).toInt()
            val halfW = ceil(max(camX - cover.left * density, cover.right * density - camX)).toInt().coerceAtLeast(1)
            left = camX - halfW
            width = halfW * 2
        }
        cameraPx = Math.round(rig.centerX * density).toInt()
        windowCentered = symmetric
        originX = left
        originY = top
        if (left == appliedL && top == appliedT && width == appliedW && height == appliedH) return
        appliedL = left
        appliedT = top
        appliedW = width
        appliedH = height
        onWindow?.invoke(left, top, width, height)
    }

    private fun drawnBounds(): DRect {
        val pill = scaled(rig.pill, max(1.0, rig.pillScale.pos))
        val bubble = rig.bubble?.let { scaled(it, max(1.0, rig.bubbleScale.pos)) }
        val box = if (bubble == null) pill else union(pill, bubble)
        val shadow = if (rig.kind == Kind.EXPANDED) 8.0 else 0.0
        return DRect(box.left - 2.0, box.top - 2.0, box.right + 2.0, box.bottom + 2.0 + shadow)
    }

    private fun union(a: DRect, b: DRect) = DRect(min(a.left, b.left), min(a.top, b.top), max(a.right, b.right), max(a.bottom, b.bottom))

    private fun encloses(outer: DRect, inner: DRect) =
        outer.left <= inner.left + 0.05 && outer.top <= inner.top + 0.05 && outer.right >= inner.right - 0.05 && outer.bottom >= inner.bottom - 0.05

    private fun boundsDp(): DRect {
        val pill = scaled(rig.pill, rig.pillScale.pos)
        val bubble = rig.bubble?.let { scaled(it, rig.bubbleScale.pos) }
        if (bubble == null) return pill
        return DRect(min(pill.left, bubble.left), min(pill.top, bubble.top), max(pill.right, bubble.right), max(pill.bottom, bubble.bottom))
    }

    private fun scaled(rect: DRect, scale: Double): DRect {
        val w = rect.width * scale
        val h = rect.height * scale
        return DRect(rect.centerX - w / 2, rect.centerY - h / 2, rect.centerX + w / 2, rect.centerY + h / 2)
    }

    private fun paintFace(
        canvas: Canvas,
        face: Face,
        look: Kind,
        pill: DRect,
        alpha: Int,
        clip: Boolean,
        elapsed: String? = null,
        remaining: String? = null,
        progress: Float? = null,
        playing: Boolean? = null,
        slots: Triple<DRect?, DRect?, DRect?>? = null,
    ) {
        val track = if (face.id == "now-playing") mediaTrack else null
        val live = if (track != null) IslandMedia.livePosition(track) else mediaPosition
        val duration = track?.duration ?: mediaDuration
        val shownElapsed = elapsed ?: if (track != null) clock(live) else ""
        val shownRemaining = remaining ?: if (track != null && duration > 0L) "−${clock((duration - live).coerceAtLeast(0))}" else ""
        val shownProgress = progress ?: if (track != null && duration > 0L) (live.toFloat() / duration).coerceIn(0f, 1f) else face.progress
        val musicPlaying = playing ?: (face.id == "now-playing" && (track?.playing == true || (track == null && face.value == "Playing")))
        val ears = if (look == Kind.EXPANDED) slots else slots ?: rig.slotRects()
        val draw = {
            IslandModes.draw(
                canvas, face.id, face.title, face.detail, face.value, face.trail, face.accent, shownProgress,
                face.glyph, shownElapsed, shownRemaining, look, pill, rig.hole, ears?.first, ears?.second, rig.now, hits, alpha,
                art = track?.art,
                playing = musicPlaying,
                icon = face.icon,
                liked = track?.liked,
            )
        }
        if (!clip) {
            draw()
            return
        }
        drawInside(canvas, continuousRect(pill, min(rig.radius.pos, min(pill.width, pill.height) / 2), 0.72), draw)
    }

    private fun ticking(): Boolean {
        if (faces.keys.any { it == "timer" || it == "timer-done" || it == "recording" || it == "call" || it == "upload" || it == "transfer" || it == "now-playing" || it == "live" }) return true
        val lock = faces["lock"] ?: return false
        if (lockUnlockPending || (lock.progress >= 0f && lock.progress < 1f)) return true
        return lock.progress < 0f && rig.look() == Kind.EXPANDED
    }

    private fun kick() {
        if (posted) return
        posted = true
        choreographer.postFrameCallback(frame)
    }

    private fun face(id: String, glyph: String, lead: String, trail: String, title: String, detail: String, value: String, accent: Int, progress: Float, body: Double, priority: Int, group: String? = null) =
        Face(id, glyph, lead, trail, title, detail, value, accent, progress, body, priority, group)

    /** A few words for the expanded island. The compact pill does not use this. */
    private fun shortLabel(title: String, detail: String): String {
        val source = title.trim().lineSequence().firstOrNull().orEmpty().ifBlank { detail.trim() }
        val clean = source.replace(Regex("\\s+"), " ").trim()
        if (clean.length <= 18) return clean
        val cut = clean.take(18)
        val space = cut.lastIndexOf(' ')
        return if (space >= 6) cut.substring(0, space) else cut
    }

    private fun shortSubtitle(detail: String, label: String): String {
        val clean = detail.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty() || clean.equals(label, ignoreCase = true)) return ""
        if (clean.length <= 28) return clean
        val cut = clean.take(28)
        val space = cut.lastIndexOf(' ')
        return if (space >= 8) cut.substring(0, space) else cut
    }

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

    private fun hit(rect: DRect, x: Double, y: Double) = rect.width > 1.0 && rect.height > 1.0 && contains(rect, x, y)

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
        var icon: android.graphics.Bitmap? = null,
    )
}
