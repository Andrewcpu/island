package dev.islandcn.island

import android.graphics.Path
import android.graphics.RectF
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Motion copied from islandcn `lib/island/motion.ts` and `lib/island/spring.ts`
 * (response / damping fraction, via-idle morph, press swell). Geometry matches
 * `lib/island/metrics.ts`: the capsule wraps the cutout and content sits in the ears.
 */

internal data class Spring(val response: Double, val damping: Double)

internal data class SpringSample(val x: Double, val v: Double)

internal fun solveSpring(config: Spring, x0: Double, v0: Double, t: Double): SpringSample {
    val response = max(config.response, 1e-4)
    val zeta = max(config.damping, 0.0)
    val w0 = (2 * PI) / response
    if (t <= 0) return SpringSample(x0, v0)
    if (zeta < 1) {
        val wd = w0 * sqrt(1 - zeta * zeta)
        val decay = exp(-zeta * w0 * t)
        val b = (v0 + zeta * w0 * x0) / wd
        val cosT = cos(wd * t)
        val sinT = sin(wd * t)
        val x = decay * (x0 * cosT + b * sinT)
        val v = decay * (-zeta * w0 * (x0 * cosT + b * sinT) + (-x0 * wd * sinT + b * wd * cosT))
        return SpringSample(x, v)
    }
    if (zeta == 1.0) {
        val decay = exp(-w0 * t)
        val b = v0 + w0 * x0
        val x = decay * (x0 + b * t)
        val v = decay * (b - w0 * (x0 + b * t))
        return SpringSample(x, v)
    }
    val root = sqrt(zeta * zeta - 1)
    val r1 = -w0 * (zeta - root)
    val r2 = -w0 * (zeta + root)
    val c2 = (v0 - r1 * x0) / (r2 - r1)
    val c1 = x0 - c2
    val e1 = exp(r1 * t)
    val e2 = exp(r2 * t)
    return SpringSample(c1 * e1 + c2 * e2, c1 * r1 * e1 + c2 * r2 * e2)
}

internal class MotionSpec(val spring: Spring?, val tween: Double) {
    val isTween: Boolean get() = spring == null
    companion object {
        fun spring(response: Double, damping: Double) = MotionSpec(Spring(response, damping), 0.0)
        fun tween(duration: Double) = MotionSpec(null, duration)
    }
}

private class Queued(val at: Double, val goal: Double, val motion: MotionSpec)

internal class SpringTrack(value: Double) {
    var pos = value
    var vel = 0.0
    var goal = value
    private var anchorT = 0.0
    private var anchorX = value
    private var anchorV = 0.0
    private var motion = MotionSpec.spring(0.5, 1.0)
    private var queued: Queued? = null

    val busy: Boolean get() = queued != null || abs(pos - goal) > 0.04 || abs(vel) > 0.04

    private fun catchUp(now: Double) {
        val pending = queued ?: return
        if (now >= pending.at) applyQueued(pending.at)
    }

    private fun eval(t: Double): SpringSample {
        val elapsed = max(0.0, t - anchorT)
        if (motion.isTween) {
            val d = motion.tween
            if (d <= 1e-4) return SpringSample(goal, 0.0)
            val u = (elapsed / d).coerceIn(0.0, 1.0)
            val eased = 1 - (1 - u).pow(3)
            val value = anchorX + (goal - anchorX) * eased
            val speed = if (u >= 1) 0.0 else (goal - anchorX) * 3 * (1 - u).pow(2) / d
            return SpringSample(value, speed)
        }
        val sample = solveSpring(motion.spring!!, anchorX - goal, anchorV, elapsed)
        return SpringSample(goal + sample.x, sample.v)
    }

    private fun applyQueued(at: Double) {
        val pending = queued ?: return
        val sample = eval(at)
        anchorT = at
        anchorX = sample.x
        anchorV = sample.v
        goal = pending.goal
        motion = pending.motion
        queued = null
        pos = sample.x
        vel = sample.v
    }

    fun aim(target: Double, next: MotionSpec, now: Double, via: Double? = null, viaDelay: Double = 0.0, viaMotion: MotionSpec? = null, hold: Double = 0.0) {
        catchUp(now)
        val sample = eval(now)
        anchorT = now
        anchorX = sample.x
        anchorV = sample.v
        pos = sample.x
        vel = sample.v
        if (hold > 0) {
            goal = sample.x
            anchorV = 0.0
            vel = 0.0
            motion = MotionSpec.tween(0.0)
            queued = Queued(now + hold, target, next)
            return
        }
        if (via != null && viaDelay > 0) {
            goal = via
            motion = viaMotion ?: next
            queued = Queued(now + viaDelay, target, next)
            return
        }
        goal = target
        motion = next
        queued = null
    }

    /** Plant the track on [value] with no velocity, so the next aim starts there. */
    fun snap(value: Double) {
        queued = null
        goal = value
        pos = value
        vel = 0.0
        anchorT = 0.0
        anchorX = value
        anchorV = 0.0
        motion = MotionSpec.spring(0.5, 1.0)
    }

    fun step(now: Double) {
        catchUp(now)
        val sample = eval(now)
        pos = sample.x
        vel = sample.v
    }
}

internal enum class Kind { IDLE, COMPACT, MINIMAL, EXPANDED }

private enum class Transition {
    APPEAR, EXPAND, PRESS_EXPAND, APPEAR_EXPAND, SWAP, COLLAPSE, ALERT_COLLAPSE, COLLAPSE_SPLIT, SPLIT, UNSPLIT, DISMISS, RESIZE,
}

internal data class Spec(
    val id: String,
    val priority: Int,
    val order: Int,
    val group: String?,
    val leading: Double,
    val trailing: Double,
    val minimal: Double,
    val expandedBody: Double,
)

internal data class DRect(val left: Double, val top: Double, val right: Double, val bottom: Double) {
    val width: Double get() = right - left
    val height: Double get() = bottom - top
    val centerX: Double get() = (left + right) / 2
    val centerY: Double get() = (top + bottom) / 2
    fun overlaps(other: DRect): Boolean = left < other.right && right > other.left && top < other.bottom && bottom > other.top
    fun inflate(n: Double) = DRect(left - n, top - n, right + n, bottom + n)
}

internal class IslandRig {
    var onPressed: ((String) -> Unit)? = null
    var kind = Kind.IDLE
        private set
    var showKeyline = true
    var now = 0.0
        private set
    var splitPair = false
        private set
    private var reduced = false
    private val byId = linkedMapOf<String, Spec>()
    private val groupShown = linkedMapOf<String, String>()
    private var order = 0
    private var expandedId: String? = null
    private var alert = false
    private val sched = mutableListOf<Pair<Double, () -> Unit>>()

    private data class Press(val target: String, val t0: Double, val activityId: String?, var decided: Boolean = false, var joined: Boolean = false, var up: Boolean = false, var expanded: Boolean = false, var returned: Boolean = false)
    private var press: Press? = null

    var hole = DRect(0.0, 0.0, 0.0, 0.0)
        private set
    var sensor = DRect(0.0, 0.0, 0.0, 0.0)
        private set
    var centerX = 201.0
        private set
    private var screenWidth = 402.0
    private var topInset = 13.91
    private var idleWidth = 94.85
    private var idleHeight = 36.84
    private var sensorWidth = 94.85
    private var compactHeight = 36.85
    private var expandedTop = 13.83

    lateinit var left: SpringTrack
        private set
    lateinit var right: SpringTrack
        private set
    lateinit var top: SpringTrack
        private set
    lateinit var height: SpringTrack
        private set
    lateinit var radius: SpringTrack
        private set
    lateinit var bubbleLeft: SpringTrack
        private set
    lateinit var bubbleRight: SpringTrack
        private set
    lateinit var bubbleHeight: SpringTrack
        private set
    lateinit var pillScale: SpringTrack
        private set
    lateinit var bubbleScale: SpringTrack
        private set

    /** Split expand that is absorbing the detached pill (islandcn split-expand-b). */
    var absorbLive = false
        private set
    private var absorbAt = 0.0
    private var bubbleCutAt = -1.0

    val primary: Spec? get() = visible().firstOrNull()
    val secondary: Spec? get() = visible().getOrNull(1)

    /** The activity on screen: the one a hold expanded, otherwise the main pill. */
    fun shownId(): String? {
        val open = expandedId
        if (kind == Kind.EXPANDED && open != null && byId.containsKey(open)) return open
        return visible().firstOrNull()?.id
    }
    val expanded: Boolean get() = kind == Kind.EXPANDED

    /**
     * What to draw while a split expands. The big pill keeps its compact size until the
     * absorb springs have eased it in, so the layout does not snap to the expanded card.
     */
    fun look(): Kind {
        if (kind != Kind.EXPANDED) return kind
        // The detached pill stays the split face until it has joined. No second view at the sensor.
        if (bubbleHeight.pos >= 0.5) return Kind.MINIMAL
        if (height.pos <= 56.0 && !absorbLive) return Kind.COMPACT
        return Kind.EXPANDED
    }
    val animating: Boolean get() = press != null || sched.isNotEmpty() || tracks().any { it.busy }

    val pill: DRect
        get() = DRect(centerX + left.pos, top.pos, centerX + right.pos, top.pos + height.pos)

    val bubble: DRect?
        get() {
            if (bubbleHeight.pos < 1) return null
            val width = bubbleRight.pos - bubbleLeft.pos
            if (width < 1) return null
            return DRect(centerX + bubbleLeft.pos, top.pos, centerX + bubbleRight.pos, top.pos + bubbleHeight.pos)
        }

    init {
        bindHole(402.0, 874.0, emptyList())
    }

    fun bindHole(screenW: Double, screenH: Double, cutouts: List<DRect>) {
        val safeW = if (screenW > 0) screenW else 402.0
        val safeH = if (screenH > 0) screenH else 874.0
        val bounds = if (cutouts.isEmpty()) {
            val diameter = 28.0
            val cx = safeW / 2
            DRect(cx - diameter / 2, 16.0, cx + diameter / 2, 16.0 + diameter)
        } else {
            val topBand = cutouts.filter { it.top <= safeH * 0.3 }.ifEmpty { cutouts }
            var box = topBand.reduce { a, b ->
                val ad = abs(a.centerX - safeW / 2)
                val bd = abs(b.centerX - safeW / 2)
                if (ad <= bd) a else b
            }
            for (rect in topBand) {
                if (rect.overlaps(box.inflate(8.0)) || abs(rect.centerX - box.centerX) < 24) {
                    box = DRect(min(box.left, rect.left), min(box.top, rect.top), max(box.right, rect.right), max(box.bottom, rect.bottom))
                }
            }
            box
        }
        val pad = 4.0
        var width = max(94.85, bounds.width + pad * 2)
        val height = 36.84
        var top = bounds.centerY - height / 2
        if (bounds.height + pad * 2 <= height) {
            if (top > bounds.top - pad) top = bounds.top - pad
            if (top + height < bounds.bottom + pad) top = bounds.bottom + pad - height
        }
        top = max(0.0, top)
        val cx = bounds.centerX
        if (cx - width / 2 > bounds.left - pad || cx + width / 2 < bounds.right + pad) {
            width = max(width, bounds.width + pad * 2)
        }
        // Same center as the cutout box, so the island stays on the camera. The mask is only the lens.
        val diameter = min(bounds.width, bounds.height).coerceAtLeast(1.0)
        hole = DRect(cx - diameter / 2, bounds.centerY - diameter / 2, cx + diameter / 2, bounds.centerY + diameter / 2)
        sensor = DRect(cx - width / 2, top, cx + width / 2, top + height)
        centerX = cx
        screenWidth = safeW
        topInset = sensor.top
        idleWidth = sensor.width
        idleHeight = sensor.height
        sensorWidth = sensor.width
        compactHeight = max(36.85, sensor.height)
        expandedTop = min(13.83, sensor.top)
        if (!::left.isInitialized) seed() else retarget(resize = true)
    }

    fun setReduced(value: Boolean) {
        reduced = value
        retarget(resize = true)
    }

    fun setKeyline(value: Boolean) {
        showKeyline = value
    }

    fun start(spec: Spec, alert: Boolean = false) {
        val existing = byId[spec.id]
        val next = spec.copy(order = existing?.order ?: order++)
        byId[next.id] = next
        val group = next.group
        if (group != null) groupShown.putIfAbsent(group, next.id)
        if (alert) beginAlert(next.id, fromIdle = existing == null && visible().size <= 1 && expandedId == null)
        retarget()
    }

    fun update(spec: Spec, alert: Boolean = false) {
        val existing = byId[spec.id]
        if (existing == null) {
            start(spec, alert)
            return
        }
        byId[spec.id] = spec.copy(order = existing.order)
        if (alert) beginAlert(spec.id, fromIdle = false)
        retarget(resize = !alert)
    }

    fun end(id: String) {
        val existing = byId.remove(id) ?: return
        val group = existing.group
        if (group != null && groupShown[group] == id) {
            val oldest = byId.values.filter { it.group == group }.minByOrNull { it.order }
            if (oldest == null) groupShown.remove(group) else groupShown[group] = oldest.id
        }
        if (expandedId == id) {
            expandedId = null
            alert = false
        }
        retarget()
    }

    fun endAll() {
        byId.clear()
        groupShown.clear()
        expandedId = null
        alert = false
        retarget()
    }

    fun collapse() {
        if (expandedId == null && kind != Kind.EXPANDED) return
        val wasAlert = alert
        expandedId = null
        alert = false
        retarget(alertReturn = wasAlert)
    }

    fun pointerDown(target: String) {
        val activityId = if (target == "bubble") secondary?.id else primary?.id
        press = Press(target, now, activityId)
        // The bubble swells about its own centre. The attached pill stays put (split-expand-b).
        if (target == "bubble") bubbleScale.aim(1.0622, motion(Spring(0.509, 0.05)), now)
        else pillScale.aim(1.187, motion(Spring(0.803, 0.641)), now)
    }

    /** No neck while the bubble is only swelling. The two join when the expand absorbs it. */
    fun tightNeck(): Boolean = press?.target == "bubble" && press?.expanded != true && bubbleHeight.pos >= 0.5

    fun bubbleContentGone(): Boolean = bubbleCutAt >= 0.0 && now >= bubbleCutAt

    fun exitAlpha(): Float {
        if (!absorbLive) return 1f
        val presence = solveSpring(Spring(0.33, 1.0), 1.0, 0.0, (now - absorbAt).coerceAtLeast(0.0)).x.coerceIn(0.0, 1.0)
        return presence.pow(1.5).toFloat()
    }

    fun enterAlpha(): Float {
        if (!absorbLive) return 1f
        val t = now - (absorbAt + 0.239)
        if (t <= 0.0) return 0f
        return (1.0 + solveSpring(Spring(0.38, 1.0), -1.0, 0.0, t).x).coerceIn(0.0, 1.0).toFloat()
    }

    /** Expanded card at rest. Incoming text is laid out here so it never jumps at the end. */
    fun restingExpanded(): DRect {
        val shape = shape(Kind.EXPANDED, measure())
        return DRect(centerX + shape.left, shape.top, centerX + shape.right, shape.top + shape.height)
    }

    fun pointerUp() {
        val current = press ?: return
        val held = now - current.t0
        if (!current.decided && held < 0.1) {
            bubbleCutAt = -1.0
            val id = current.activityId
            if (id != null) onPressed?.invoke(id)
            val idle = kind == Kind.IDLE || primary == null
            val backAt = current.t0 + (if (idle) 0.2 else max(0.22, held + 0.17))
            releaseScale(max(now, backAt), idle)
            press = null
            return
        }
        current.up = true
    }

    fun sync(time: Double) {
        if (time > now) now = time
        val current = press
        if (current != null) {
            val held = now - current.t0
            val idle = primary == null && expandedId == null
            if (!current.decided && !current.up && held >= 0.1) current.decided = true
            if (current.decided && !current.joined && current.target == "bubble" && bubbleHeight.pos >= 0.5) {
                current.joined = true
                val joinAt = current.t0 + 0.224
                val idleRight = shape(Kind.IDLE, Measure()).right
                val wait = (joinAt - 0.012 - now).coerceAtLeast(0.0)
                bubbleLeft.aim(idleRight - bubbleHeight.pos, motion(Spring(0.25, 1.0)), now, hold = wait)
            }
            if (current.decided && !current.expanded && held >= (if (current.target == "bubble") 0.224 else 0.216)) {
                current.expanded = true
                if (!idle) {
                    releaseScale(now, idle = false)
                    expand(current.activityId)
                }
            }
            if (idle && !current.returned && held >= 0.236) {
                current.returned = true
                releaseScale(now, idle = true)
                if (current.up) press = null
            }
            if (current.up && current.expanded) press = null
        }
        val due = sched.filter { it.first <= now }
        if (due.isNotEmpty()) {
            sched.removeAll(due)
            for (item in due) item.second()
        }
        for (track in tracks()) track.step(now)
        if (absorbLive && bubbleHeight.pos >= 0.5 && now >= absorbAt + 0.222 && bubbleLeft.pos >= left.pos - 1.0 && bubbleRight.pos <= right.pos + 1.0) {
            bubbleHeight.aim(0.0, MotionSpec.tween(0.0), now)
        }
        if (absorbLive && now > absorbAt + 0.7 && enterAlpha() > 0.98f && !left.busy && !right.busy && !height.busy && !radius.busy) {
            absorbLive = false
            bubbleCutAt = -1.0
        }
    }

    fun measureLeading(id: String): Double = byId[id]?.leading ?: 0.0

    private fun expand(id: String?) {
        val targetId = id ?: primary?.id ?: return
        if (!byId.containsKey(targetId)) return
        val activity = byId[targetId] ?: return
        val group = activity.group
        if (group != null && groupShown[group] != targetId) groupShown[group] = targetId
        expandedId = targetId
        alert = false
        retarget(swelling = press?.decided == true)
    }

    private fun seed() {
        val target = shape(Kind.IDLE, Measure())
        left = SpringTrack(target.left)
        right = SpringTrack(target.right)
        top = SpringTrack(target.top)
        height = SpringTrack(target.height)
        radius = SpringTrack(target.radius)
        bubbleLeft = SpringTrack(target.bubbleLeft)
        bubbleRight = SpringTrack(target.bubbleRight)
        bubbleHeight = SpringTrack(target.bubbleHeight)
        pillScale = SpringTrack(1.0)
        bubbleScale = SpringTrack(1.0)
    }

    private fun visible(): List<Spec> {
        return byId.values.filter { spec ->
            val group = spec.group
            group == null || groupShown[group] == spec.id
        }.sortedWith(compareByDescending<Spec> { it.priority }.thenBy { it.order })
    }

    private fun beginAlert(id: String, fromIdle: Boolean) {
        if (!byId.containsKey(id)) return
        expandedId = id
        alert = true
        val hold = if (fromIdle) 5.969 else 5.975
        val token = expandedId
        schedule(now + hold) {
            if (alert && expandedId == token) collapse()
        }
    }

    private data class Measure(
        val leading: Double = 0.0,
        val trailing: Double = 0.0,
        val attached: Double = 0.0,
        val detached: Double = 0.0,
        val splitAttached: Pair<Double, Double>? = null,
        val splitDetached: Pair<Double, Double>? = null,
        val expandedBody: Double = 48.0,
    )

    private fun measure(): Measure {
        val shown = visible()
        if (shown.isEmpty()) return Measure()
        val first = shown.first()
        val focus = (expandedId?.let { byId[it] }) ?: first
        if (shown.size == 1) {
            return Measure(first.leading, first.trailing, first.minimal, 0.0, expandedBody = focus.expandedBody)
        }
        val second = shown[1]
        return Measure(
            first.leading,
            first.trailing,
            first.minimal,
            second.minimal,
            first.leading to first.trailing,
            second.leading to second.trailing,
            focus.expandedBody,
        )
    }

    private data class Shape(
        val left: Double,
        val right: Double,
        val top: Double,
        val height: Double,
        val radius: Double,
        val bubbleLeft: Double,
        val bubbleRight: Double,
        val bubbleHeight: Double,
    )

    private fun shape(kind: Kind, measure: Measure): Shape {
        val sensorHalf = sensorWidth / 2
        return when (kind) {
            Kind.IDLE -> {
                val width = idleWidth
                Shape(-width / 2, width / 2, topInset, idleHeight, idleHeight / 2, width / 2 - idleHeight, width / 2, 0.0)
            }
            Kind.COMPACT -> {
                val sides = sides(measure.leading, measure.trailing)
                val left = -min(sensorHalf + sides.first, 260.0 / 2)
                val right = min(sensorHalf + sides.second, 260.0 / 2)
                Shape(left, right, topInset, compactHeight, compactHeight / 2, right - compactHeight, right, 0.0)
            }
            Kind.MINIMAL -> {
                val split = splitOf(measure)
                if (split != null) {
                    Shape(split[0], split[1], topInset, compactHeight, compactHeight / 2, split[2], split[3], compactHeight)
                } else {
                    val left = if (measure.attached > 0) -(sensorHalf + 8.4 + measure.attached + 7.09) else -sensorHalf
                    val bubbleW = max(36.85, measure.detached + 2 * 6.42)
                    val bubbleLeft = sensorHalf + 7.82
                    Shape(left, sensorHalf, topInset, compactHeight, compactHeight / 2, bubbleLeft, bubbleLeft + bubbleW, compactHeight)
                }
            }
            Kind.EXPANDED -> {
                val half = (min(screenWidth, 402.0) - 2 * 13.83) / 2
                val height = (measure.expandedBody + 18.18 + 18.35).coerceIn(84.0, 180.0)
                Shape(-half, half, expandedTop, height, 47.9, half - compactHeight, half, 0.0)
            }
        }
    }

    private fun sides(leadingSlot: Double, trailingSlot: Double): Pair<Double, Double> {
        fun side(slot: Double, inner: Double) = if (slot > 0) inner + min(slot, 72.0) + 7.18 else 0.0
        val leading = side(leadingSlot, -0.18)
        val trailing = side(trailingSlot, 0.07)
        if (leading <= 0 || trailing <= 0) return leading to trailing
        return max(leading, trailing * 0.7475) to max(trailing, leading * 0.7475)
    }

    private fun splitOf(measure: Measure): DoubleArray? {
        val attached = measure.splitAttached ?: return null
        val detached = measure.splitDetached ?: return null
        fun pairWidth(slots: Pair<Double, Double>, gap: Double) = slots.first + slots.second + if (slots.first > 0 && slots.second > 0) gap else 0.0
        val attachedWidth = 6.59 + pairWidth(attached, 6.15) + 8.18
        val bubbleW = if (detached.second > 0) 62.18 else max(36.85, 6.42 + pairWidth(detached, 6.085) + 6.42)
        val sensorHalf = sensorWidth / 2
        val left = -(sensorHalf + attachedWidth)
        val bubbleLeft = sensorHalf + 7.82
        val bubbleRight = bubbleLeft + bubbleW
        if (bubbleRight - left > 290.0) return null
        return doubleArrayOf(left, sensorHalf, bubbleLeft, bubbleRight)
    }

    private fun desired(): Kind {
        if (expandedId != null && byId.containsKey(expandedId)) return Kind.EXPANDED
        val shown = visible()
        if (shown.isEmpty()) return Kind.IDLE
        if (shown.size == 1) return Kind.COMPACT
        return Kind.MINIMAL
    }

    private fun retarget(resize: Boolean = false, alertReturn: Boolean = false, swelling: Boolean = false) {
        val next = desired()
        val from = kind
        val swap = next == Kind.EXPANDED && from == Kind.MINIMAL
        val transition = if (from == next && resize) Transition.RESIZE else transition(from, next, swap, swelling, alertReturn)
        val measure = measure()
        splitPair = next == Kind.MINIMAL && splitOf(measure) != null
        val target = shape(next, measure)
        val idle = shape(Kind.IDLE, Measure())
        aim(transition, target, idle)
        kind = next
    }

    private fun transition(from: Kind, to: Kind, swap: Boolean, swelling: Boolean, alertReturn: Boolean): Transition {
        if (from == to) return Transition.RESIZE
        if (to == Kind.EXPANDED) {
            if (from == Kind.IDLE) return Transition.APPEAR_EXPAND
            if (from == Kind.MINIMAL || swap) return Transition.SWAP
            return if (swelling) Transition.PRESS_EXPAND else Transition.EXPAND
        }
        if (from == Kind.EXPANDED) {
            if (to == Kind.MINIMAL) return Transition.COLLAPSE_SPLIT
            if (to == Kind.IDLE) return Transition.DISMISS
            return if (alertReturn) Transition.ALERT_COLLAPSE else Transition.COLLAPSE
        }
        if (to == Kind.IDLE) return Transition.DISMISS
        if (from == Kind.IDLE) return Transition.APPEAR
        if (to == Kind.MINIMAL) return Transition.SPLIT
        return Transition.UNSPLIT
    }

    private fun motion(spring: Spring): MotionSpec = if (reduced) MotionSpec.tween(0.2) else MotionSpec(spring, 0.0)

    private fun edgeSpring(transition: Transition) = when (transition) {
        Transition.APPEAR -> Spring(0.483, 0.62)
        Transition.EXPAND -> Spring(0.517, 0.824)
        Transition.PRESS_EXPAND -> Spring(0.387, 0.792)
        Transition.APPEAR_EXPAND -> Spring(0.54, 0.811)
        Transition.SWAP -> Spring(0.498, 0.829)
        Transition.COLLAPSE -> Spring(0.479, 0.894)
        Transition.ALERT_COLLAPSE -> Spring(0.535, 0.84)
        Transition.COLLAPSE_SPLIT -> Spring(0.4, 0.9)
        Transition.SPLIT -> Spring(0.505, 0.815)
        Transition.UNSPLIT -> Spring(0.487, 0.794)
        Transition.DISMISS -> Spring(0.432, 1.019)
        Transition.RESIZE -> Spring(0.5, 0.82)
    }

    private fun bodySpring(transition: Transition) = when (transition) {
        Transition.EXPAND -> Spring(0.497, 0.825)
        Transition.PRESS_EXPAND -> Spring(0.388, 0.791)
        Transition.APPEAR_EXPAND -> Spring(0.523, 0.817)
        Transition.SWAP -> Spring(0.492, 0.832)
        Transition.COLLAPSE -> Spring(0.453, 0.928)
        Transition.ALERT_COLLAPSE -> Spring(0.481, 0.908)
        Transition.COLLAPSE_SPLIT -> Spring(0.481, 0.884)
        Transition.UNSPLIT -> Spring(0.5, 0.74)
        Transition.DISMISS -> Spring(0.442, 0.97)
        else -> edgeSpring(transition)
    }

    private fun cornerSpring(transition: Transition) = when (transition) {
        Transition.EXPAND -> Spring(0.448, 0.87)
        Transition.PRESS_EXPAND -> Spring(0.32, 0.831)
        Transition.APPEAR_EXPAND -> Spring(0.452, 0.865)
        Transition.SWAP -> Spring(0.41, 0.888)
        Transition.COLLAPSE -> Spring(0.449, 0.94)
        Transition.ALERT_COLLAPSE -> Spring(0.47, 0.86)
        Transition.COLLAPSE_SPLIT -> Spring(0.36, 0.865)
        else -> bodySpring(transition)
    }

    private fun cornerDelay(transition: Transition) = when (transition) {
        Transition.EXPAND -> 0.018
        Transition.PRESS_EXPAND -> 0.019
        Transition.APPEAR_EXPAND -> 0.02
        Transition.SWAP -> 0.244
        Transition.COLLAPSE -> 0.01
        Transition.ALERT_COLLAPSE -> 0.009
        else -> 0.0
    }

    private fun via(transition: Transition) = when (transition) {
        Transition.SWAP -> 0.223
        Transition.COLLAPSE -> 0.311
        Transition.ALERT_COLLAPSE -> 0.29
        Transition.SPLIT -> 0.215
        Transition.UNSPLIT -> 0.203
        else -> 0.0
    }

    private fun aim(transition: Transition, target: Shape, idle: Shape) {
        // Long-press from a split absorbs the bubble: it slides under the sensor, the attached
        // pill contracts, then the one shape opens (islandcn absorbExpand / split-expand-b).
        val absorbing = !reduced && transition == Transition.SWAP && bubbleHeight.pos >= 0.5 && target.bubbleHeight < 0.5
        if (transition == Transition.SWAP || transition == Transition.EXPAND || transition == Transition.PRESS_EXPAND || transition == Transition.APPEAR_EXPAND) {
            foldPillScale()
        }
        if (absorbing && !absorbLive) {
            absorbLive = true
            absorbAt = now
        }
        val edge = motion(edgeSpring(transition))
        val body = motion(bodySpring(transition))
        val corner = if (absorbing) motion(Spring(0.49, 0.826)) else motion(cornerSpring(transition))
        val via = if (reduced) 0.0 else if (absorbing) 0.222 else via(transition)
        val retarget = when (transition) {
            Transition.COLLAPSE -> motion(Spring(0.447, 1.0))
            Transition.ALERT_COLLAPSE -> motion(Spring(0.527, 0.837))
            else -> edge
        }
        fun edgeAim(track: SpringTrack, to: Double, idleTo: Double) {
            if (via > 0) track.aim(to, retarget, now, via = idleTo, viaDelay = via, viaMotion = edge)
            else track.aim(to, edge, now)
        }
        edgeAim(left, target.left, idle.left)
        edgeAim(right, target.right, idle.right)
        val bodyHold = if (reduced) 0.0 else if (absorbing) 0.222 else if (transition == Transition.SWAP) 0.223 else 0.0
        top.aim(target.top, body, now, hold = bodyHold)
        height.aim(target.height, body, now, hold = bodyHold)
        val radiusHold = if (reduced) 0.0 else if (absorbing) 0.223 else cornerDelay(transition)
        radius.aim(target.radius, corner, now, hold = radiusHold)
        if (absorbing) {
            val restH = bubbleHeight.pos
            val grown = bubbleScale.pos
            if (grown > 1.001) {
                val mid = (bubbleLeft.pos + bubbleRight.pos) / 2.0
                val half = (bubbleRight.pos - bubbleLeft.pos) / 2.0
                bubbleLeft.snap(mid - half * grown)
                bubbleRight.snap(mid + half * grown)
                bubbleHeight.snap(restH * grown)
                bubbleScale.snap(1.0)
                bubbleHeight.aim(restH, motion(Spring(0.25, 1.0)), now)
            }
            bubbleLeft.aim(idle.right - restH, motion(Spring(0.25, 1.0)), now)
            bubbleRight.aim(idle.right, motion(Spring(0.42, 1.0)), now)
            // The lobe stays drawn until it sits inside the pill (islandcn's step at the turn).
            schedule(now + 0.222) {
                if (kind != Kind.EXPANDED) return@schedule
                val inside = bubbleLeft.pos >= left.pos - 1.0 && bubbleRight.pos <= right.pos + 1.0
                if (inside) bubbleHeight.aim(0.0, MotionSpec.tween(0.0), now)
            }
        } else if (transition == Transition.SPLIT) {
            bubbleLeft.aim(target.bubbleLeft, motion(Spring(0.376, 0.811)), now, hold = if (reduced) 0.0 else 0.345)
            if (via > 0) bubbleRight.aim(target.bubbleRight, edge, now, via = idle.bubbleRight, viaDelay = via, viaMotion = edge)
            else bubbleRight.aim(target.bubbleRight, edge, now)
            bubbleHeight.aim(target.bubbleHeight, body, now)
        } else if (via > 0 && transition == Transition.UNSPLIT) {
            bubbleLeft.aim(target.bubbleLeft, edge, now, via = idle.bubbleLeft, viaDelay = via, viaMotion = edge)
            bubbleRight.aim(target.bubbleRight, edge, now, via = idle.bubbleRight, viaDelay = via, viaMotion = edge)
            bubbleHeight.aim(target.bubbleHeight, body, now)
        } else {
            bubbleLeft.aim(target.bubbleLeft, edge, now)
            bubbleRight.aim(target.bubbleRight, edge, now)
            bubbleHeight.aim(target.bubbleHeight, body, now)
        }
        if (transition == Transition.COLLAPSE || transition == Transition.ALERT_COLLAPSE) scheduleTail(transition, target)
    }

    private fun scheduleTail(transition: Transition, target: Shape) {
        val widthBy = if (transition == Transition.COLLAPSE) 0.025 else 0.017
        val widthAt = if (transition == Transition.COLLAPSE) 0.396 else 0.366
        val widthSettle = if (transition == Transition.COLLAPSE) 0.608 else 0.652
        val widthSettleSpring = if (transition == Transition.COLLAPSE) Spring(0.409, 1.0) else Spring(0.351, 1.0)
        val widthLeanSpring = if (transition == Transition.COLLAPSE) Spring(0.3, 0.866) else Spring(0.357, 0.704)
        val heightBy = if (transition == Transition.COLLAPSE) 0.019 else 0.02
        val heightAt = if (transition == Transition.COLLAPSE) 0.242 else 0.248
        val heightSettle = if (transition == Transition.COLLAPSE) 0.56 else 0.548
        val heightLeanSpring = if (transition == Transition.COLLAPSE) Spring(0.287, 0.724) else Spring(0.318, 0.718)
        val heightSettleSpring = if (transition == Transition.COLLAPSE) Spring(0.372, 1.0) else Spring(0.396, 1.0)
        val curW = right.pos - left.pos
        val toW = target.right - target.left
        val leanW = min(widthBy * abs(toW - curW), 8.0) / 2
        val wider = if (toW >= curW) 1.0 else -1.0
        schedule(now + widthAt) {
            left.aim(target.left - wider * leanW, motion(widthLeanSpring), now)
            right.aim(target.right + wider * leanW, motion(widthLeanSpring), now)
        }
        schedule(now + widthSettle) {
            left.aim(target.left, motion(widthSettleSpring), now)
            right.aim(target.right, motion(widthSettleSpring), now)
        }
        val curH = height.pos
        val leanH = min(heightBy * abs(target.height - curH), 4.0)
        schedule(now + heightAt) {
            height.aim(target.height + leanH, motion(heightLeanSpring), now)
        }
        schedule(now + heightSettle) {
            height.aim(target.height, motion(heightSettleSpring), now)
        }
    }

    private fun foldPillScale() {
        val grown = pillScale.pos
        if (grown < 1.002) return
        val mid = (left.pos + right.pos) / 2.0
        val half = (right.pos - left.pos) * grown / 2.0
        left.snap(mid - half)
        right.snap(mid + half)
        val h = height.pos
        top.snap(top.pos - h * (grown - 1.0) / 2.0)
        height.snap(h * grown)
        radius.snap(radius.pos * grown)
        pillScale.snap(1.0)
    }

    private fun releaseScale(at: Double, idle: Boolean) {
        val spring = if (idle) Spring(0.53, 0.77) else Spring(0.3, 1.5)
        val go = {
            if (abs(pillScale.pos - 1.0) > 0.001 || abs(pillScale.goal - 1.0) > 0.001) pillScale.aim(1.0, motion(spring), now)
            if (abs(bubbleScale.pos - 1.0) > 0.001 || abs(bubbleScale.goal - 1.0) > 0.001) bubbleScale.aim(1.0, motion(spring), now)
        }
        if (at > now + 0.001) schedule(at, go) else go()
    }

    private fun schedule(at: Double, run: () -> Unit) {
        sched.add(at to run)
    }

    private fun tracks() = listOf(left, right, top, height, radius, bubbleLeft, bubbleRight, bubbleHeight, pillScale, bubbleScale)

    fun slotRects(): Triple<DRect?, DRect?, DRect?> {
        val shown = visible()
        val look = look()
        val first = if (look == Kind.COMPACT && kind == Kind.EXPANDED) {
            shown.find { it.id == expandedId } ?: shown.firstOrNull()
        } else {
            shown.firstOrNull()
        } ?: return Triple(null, null, null)
        val targetKind = look
        val shape = shape(targetKind, measure())
        val pill = DRect(centerX + shape.left, shape.top, centerX + shape.right, shape.top + shape.height)
        val glyph = min(22.0, max(8.0, pill.height - 8))
        var leading: DRect? = null
        var trailing: DRect? = null
        if (targetKind == Kind.COMPACT) {
            if (first.leading > 0) {
                val w = min(first.leading, 72.0)
                var left = pill.left + 7.18
                if (left + w > hole.left) left = hole.left - w
                leading = DRect(left, pill.centerY - glyph / 2, left + w, pill.centerY + glyph / 2)
            }
            if (first.trailing > 0) {
                val w = min(first.trailing, 72.0)
                var left = pill.right - 7.18 - w
                if (left < hole.right) left = hole.right
                trailing = DRect(left, pill.centerY - 10, left + w, pill.centerY + 10)
            }
        } else if (targetKind == Kind.MINIMAL && splitPair) {
            if (first.leading > 0) {
                val w = min(first.leading, 36.0)
                leading = DRect(pill.left + 6.59, pill.centerY - 11, pill.left + 6.59 + w, pill.centerY + 11)
            }
            if (first.trailing > 0) {
                val w = min(first.trailing, 48.0)
                val left = sensor.left - 8.18 - w
                trailing = DRect(left, pill.centerY - 10, left + w, pill.centerY + 10)
            }
        } else if (targetKind == Kind.EXPANDED) {
            leading = DRect(pill.left + 18.5, pill.top + 18.18, pill.left + 18.5 + 52, pill.top + 18.18 + 52)
            trailing = DRect(pill.right - 17.81 - 88, pill.top + 18.18, pill.right - 17.81, pill.top + 18.18 + 36)
        }
        return Triple(leading, trailing, bubble)
    }
}

internal fun continuousRect(rect: DRect, radius: Double, smoothing: Double): Path {
    val path = Path()
    val w = max(0.0, rect.width)
    val h = max(0.0, rect.height)
    if (w == 0.0 || h == 0.0) return path
    val r = min(radius, min(w / 2, h / 2)).coerceAtLeast(0.0)
    if (r < 1e-3) {
        path.addRect(rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat(), Path.Direction.CW)
        return path
    }
    val across = ease(r, smoothing, w / 2)
    val down = ease(r, smoothing, h / 2)
    val x = rect.left
    val y = rect.top
    val right = x + w
    val bottom = y + h
    path.moveTo((x + across.p).toFloat(), y.toFloat())
    corner(path, right, y, -1.0, 0.0, 0.0, 1.0, across, down, r)
    corner(path, right, bottom, 0.0, -1.0, -1.0, 0.0, down, across, r)
    corner(path, x, bottom, 1.0, 0.0, 0.0, -1.0, across, down, r)
    corner(path, x, y, 0.0, 1.0, 1.0, 0.0, down, across, r)
    path.close()
    return path
}

private data class Ease(val p: Double, val a: Double, val b: Double, val along: Double, val across: Double)

private fun ease(r: Double, smoothing: Double, room: Double): Ease {
    val s = smoothing.coerceIn(0.0, if (r <= 0) 0.0 else room / r - 1)
    val angle = (PI / 4) * s
    val p = (1 + s) * r
    val along = r * (1 - sin(angle))
    val across = r * (1 - cos(angle))
    val c = r * tan(angle / 2) * cos(angle)
    val b = max(0.0, (p - c - along) / 3)
    return Ease(p, 2 * b, b, along, across)
}

private fun corner(path: Path, kx: Double, ky: Double, ux: Double, uy: Double, vx: Double, vy: Double, inc: Ease, out: Ease, radius: Double) {
    path.lineTo((kx + ux * inc.p).toFloat(), (ky + uy * inc.p).toFloat())
    path.cubicTo(
        (kx + ux * (inc.p - inc.a)).toFloat(),
        (ky + uy * (inc.p - inc.a)).toFloat(),
        (kx + ux * (inc.p - inc.a - inc.b)).toFloat(),
        (ky + uy * (inc.p - inc.a - inc.b)).toFloat(),
        (kx + ux * inc.along + vx * inc.across).toFloat(),
        (ky + uy * inc.along + vy * inc.across).toFloat(),
    )
    val sx = kx + ux * inc.along + vx * inc.across
    val sy = ky + uy * inc.along + vy * inc.across
    val ex = kx + vx * out.along + ux * out.across
    val ey = ky + vy * out.along + uy * out.across
    arcTo(path, sx, sy, ex, ey, radius)
    path.cubicTo(
        (kx + vx * (out.p - out.a - out.b)).toFloat(),
        (ky + vy * (out.p - out.a - out.b)).toFloat(),
        (kx + vx * (out.p - out.a)).toFloat(),
        (ky + vy * (out.p - out.a)).toFloat(),
        (kx + vx * out.p).toFloat(),
        (ky + vy * out.p).toFloat(),
    )
}

private fun arcTo(path: Path, sx: Double, sy: Double, ex: Double, ey: Double, radius: Double) {
    val dx = ex - sx
    val dy = ey - sy
    val dist = hypot(dx, dy)
    if (dist < 0.05 || radius <= 0) {
        path.lineTo(ex.toFloat(), ey.toFloat())
        return
    }
    val r = max(radius, dist / 2)
    val mx = (sx + ex) / 2
    val my = (sy + ey) / 2
    val h = sqrt((r * r - (dist * dist) / 4).coerceAtLeast(0.0))
    val cx = mx + h * (-dy / dist)
    val cy = my + h * (dx / dist)
    val start = atan2(sy - cy, sx - cx)
    var sweep = atan2(ey - cy, ex - cx) - start
    if (sweep < 0) sweep += 2 * PI
    if (sweep > PI) {
        val cx2 = mx - h * (-dy / dist)
        val cy2 = my - h * (dx / dist)
        val start2 = atan2(sy - cy2, sx - cx2)
        var sweep2 = atan2(ey - cy2, ex - cx2) - start2
        if (sweep2 > 0) sweep2 -= 2 * PI
        val oval = RectF((cx2 - r).toFloat(), (cy2 - r).toFloat(), (cx2 + r).toFloat(), (cy2 + r).toFloat())
        path.arcTo(oval, Math.toDegrees(start2).toFloat(), Math.toDegrees(sweep2).toFloat(), false)
        return
    }
    val oval = RectF((cx - r).toFloat(), (cy - r).toFloat(), (cx + r).toFloat(), (cy + r).toFloat())
    path.arcTo(oval, Math.toDegrees(start).toFloat(), Math.toDegrees(sweep).toFloat(), false)
}

internal fun bridgeNeck(pill: DRect, bubble: DRect, maxGap: Double = 5.0): Path {
    val path = Path()
    val gap = bubble.left - pill.right
    if (gap > maxGap || pill.height < 2 || bubble.height < 2) return path
    val t = (1 - (gap / maxGap)).coerceIn(0.0, 1.0)
    val neck = min(pill.height, bubble.height) * t.pow(0.65)
    val mid = (pill.centerY + bubble.centerY) / 2
    val y0 = mid - neck / 2
    val y1 = mid + neck / 2
    val x0 = pill.right
    val x1 = bubble.left
    val pull = max(2.4, abs(x1 - x0) * 0.3 + 2.4)
    path.moveTo(x0.toFloat(), y0.toFloat())
    path.cubicTo((x0 + pull).toFloat(), y0.toFloat(), (x1 - pull).toFloat(), y0.toFloat(), x1.toFloat(), y0.toFloat())
    path.lineTo(x1.toFloat(), y1.toFloat())
    path.cubicTo((x1 - pull).toFloat(), y1.toFloat(), (x0 + pull).toFloat(), y1.toFloat(), x0.toFloat(), y1.toFloat())
    path.close()
    return path
}
