package dev.islandcn.island

import android.app.Notification
import android.app.Person
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class IslandNotificationListener : NotificationListenerService() {
    private val sessions = HashMap<MediaSession.Token, MediaController>()
    private val messages = LinkedHashMap<String, Pair<String, String>>()
    private val missed = LinkedHashMap<String, String>()
    private val timers = LinkedHashMap<String, TimerNote>()
    private val progressNotes = LinkedHashMap<String, ProgressNote>()
    private val rides = LinkedHashMap<String, RideNote>()
    private val statuses = LinkedHashMap<String, Pair<String, String>>()
    private val calls = LinkedHashSet<String>()

    override fun onListenerConnected() {
        instance = this
        resync()
    }

    override fun onListenerDisconnected() {
        if (instance == this) instance = null
        sessions.clear()
    }

    fun resync() {
        activeNotifications?.forEach { handlePosted(it) }
        IslandOverlayService.onMedia(this, IslandMedia.current(this))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        handlePosted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        val key = sbn?.key ?: return
        releaseCall(key)
        publishChanged(drop(key))
        val token = mediaToken(sbn.notification)
        if (token != null) {
            sessions.remove(token)?.unregisterCallback(mediaCallback)
            IslandOverlayService.onMedia(this, IslandMedia.current(this))
        }
    }

    private fun handlePosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val notification = sbn.notification
        val key = sbn.key
        val token = mediaToken(notification)
        if (token != null) {
            releaseCall(key)
            publishChanged(drop(key))
            publishMedia(token)
            return
        }
        if (isMissedCall(notification)) {
            releaseCall(key)
            publishChanged(drop(key, keep = "missed"))
            missed[key] = callerName(notification) ?: "Unknown"
            publishMissed()
            return
        }
        if (isCall(notification)) {
            publishChanged(drop(key))
            calls.add(key)
            val type = notification.extras.getInt(Notification.EXTRA_CALL_TYPE, -1)
            val state = if (type == 2) "offhook" else "ringing"
            val name = callerName(notification).orEmpty()
            callerName(notification)?.let { IslandMonitors.noteCaller(this, it, state == "offhook") }
            IslandOverlayService.onCall(this, state, name)
            return
        }
        releaseCall(key)
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (isTimer(sbn)) {
            val remaining = remaining(notification) ?: return
            publishChanged(drop(key, keep = "timer"))
            val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val label = if (title.isEmpty() || durationText(title)) "Timer" else title
            val paused = remaining.second
            timers[key] = TimerNote(
                endsAt = if (paused) 0L else SystemClock.elapsedRealtime() + remaining.first,
                frozen = if (paused) remaining.first else null,
                paused = paused,
                label = label,
            )
            publishTimer()
            return
        }
        val message = liveMessage(notification)
        if (message != null) {
            publishChanged(drop(key, keep = "message"))
            messages[key] = message
            publishMessage()
            return
        }
        val progress = liveProgress(notification)
        if (progress != null) {
            publishChanged(drop(key, keep = "progress"))
            progressNotes[key] = progress
            publishProgress()
            return
        }
        val ride = liveRide(notification)
        if (ride != null) {
            publishChanged(drop(key, keep = "ride"))
            rides[key] = ride
            publishRide()
            return
        }
        if (promoted(notification)) {
            val title = textExtra(notification, Notification.EXTRA_TITLE)
            val detail = textExtra(notification, Notification.EXTRA_TEXT).ifEmpty { textExtra(notification, Notification.EXTRA_SUB_TEXT) }
            if (title.isEmpty() && detail.isEmpty()) {
                publishChanged(drop(key))
                return
            }
            publishChanged(drop(key, keep = "status"))
            statuses[key] = title to detail
            publishStatus()
            return
        }
        publishChanged(drop(key))
    }

    private fun drop(key: String, keep: String? = null): Set<String> {
        val changed = linkedSetOf<String>()
        if (keep != "message" && messages.remove(key) != null) changed.add("message")
        if (keep != "timer" && timers.remove(key) != null) changed.add("timer")
        if (keep != "missed" && missed.remove(key) != null) changed.add("missed")
        if (keep != "progress" && progressNotes.remove(key) != null) changed.add("progress")
        if (keep != "ride" && rides.remove(key) != null) changed.add("ride")
        if (keep != "status" && statuses.remove(key) != null) changed.add("status")
        return changed
    }

    private fun releaseCall(key: String) {
        if (!calls.remove(key)) return
        if (calls.isEmpty() && !IslandMonitors.callActive()) IslandOverlayService.onCall(this, "idle", "")
    }

    private fun publishChanged(changed: Set<String>) {
        if ("message" in changed) publishMessage()
        if ("timer" in changed) publishTimer()
        if ("missed" in changed) publishMissed()
        if ("progress" in changed) publishProgress()
        if ("ride" in changed) publishRide()
        if ("status" in changed) publishStatus()
    }

    private fun publishProgress() {
        val note = progressNotes.values.lastOrNull()
        if (note == null) IslandOverlayService.onProgressCleared()
        else IslandOverlayService.onProgress(this, note.title, note.detail, note.fraction, note.label)
    }

    private fun publishRide() {
        val note = rides.values.lastOrNull()
        if (note == null) IslandOverlayService.onRideCleared()
        else IslandOverlayService.onRide(this, note.title, note.detail, note.value, note.delivery)
    }

    private fun publishStatus() {
        val note = statuses.values.lastOrNull()
        if (note == null) IslandOverlayService.onStatusCleared()
        else IslandOverlayService.onStatus(this, note.first, note.second)
    }

    private fun publishMessage() {
        val latest = messages.values.lastOrNull()
        if (latest == null) IslandOverlayService.onMessageCleared()
        else IslandOverlayService.onMessage(this, latest.first, latest.second)
    }

    private fun publishTimer() {
        val note = timers.values.minByOrNull { if (it.paused) Long.MAX_VALUE - 1 else (it.endsAt - SystemClock.elapsedRealtime()) } ?: run {
            IslandOverlayService.onTimerCleared()
            return
        }
        val left = if (note.paused) note.frozen ?: 0L else (note.endsAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        IslandOverlayService.onTimer(this, left, note.paused, note.label)
    }

    private fun publishMedia(token: MediaSession.Token) {
        val existing = sessions[token]
        if (existing != null) {
            emitMedia(existing)
            return
        }
        val controller = MediaController(this, token)
        sessions[token] = controller
        controller.registerCallback(mediaCallback)
        emitMedia(controller)
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = refreshMedia()

        override fun onMetadataChanged(metadata: MediaMetadata?) = refreshMedia()
    }

    private fun emitMedia(controller: MediaController) {
        controls = controller.transportControls
        refreshMedia()
    }

    private fun refreshMedia() {
        IslandOverlayService.onMedia(this, IslandMedia.current(this))
    }

    private fun callPerson(extras: android.os.Bundle): Person? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelable(Notification.EXTRA_CALL_PERSON, Person::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelable(Notification.EXTRA_CALL_PERSON)
        }
    }

    private fun senderPerson(bundle: android.os.Bundle?): String? {
        if (bundle == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        val person = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            bundle.getParcelable("sender_person", Person::class.java)
        } else {
            @Suppress("DEPRECATION")
            bundle.getParcelable("sender_person") as? Person
        }
        return person?.name?.toString()
    }

    private fun mediaToken(notification: Notification): MediaSession.Token? {
        val extras = notification.extras
        val token = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelable(Notification.EXTRA_MEDIA_SESSION)
        }
        return token as? MediaSession.Token
    }

    private fun publishMissed() {
        val name = missed.values.lastOrNull()
        if (name == null) IslandOverlayService.onMissedCleared()
        else IslandOverlayService.onMissed(this, name)
    }

    private fun isMissedCall(notification: Notification): Boolean {
        if (notification.category == "missed_call") return true
        val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        return "$title $text".contains("missed call", ignoreCase = true)
    }

    private fun isCall(notification: Notification): Boolean {
        if (notification.category == Notification.CATEGORY_CALL) return true
        if (callPerson(notification.extras)?.name != null) return true
        if (notification.extras.containsKey(Notification.EXTRA_CALL_TYPE)) return true
        return false
    }

    private fun live(notification: Notification): Boolean {
        val flags = notification.flags
        if (flags and Notification.FLAG_PROMOTED_ONGOING != 0) return true
        if (flags and Notification.FLAG_ONGOING_EVENT != 0) return true
        return false
    }

    private fun promoted(notification: Notification): Boolean = (notification.flags and Notification.FLAG_PROMOTED_ONGOING) != 0

    private fun textExtra(notification: Notification, key: String): String =
        notification.extras.getCharSequence(key)?.toString()?.trim().orEmpty()

    private fun callerName(notification: Notification): String? {
        val extras = notification.extras
        val name = callPerson(extras)?.name?.toString()?.trim().orEmpty()
        if (name.isNotEmpty()) return name
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        return title.ifEmpty { null }
    }

    private fun isTimer(sbn: StatusBarNotification): Boolean {
        val notification = sbn.notification
        val extras = notification.extras
        if (extras.getBoolean(CHRONOMETER_COUNT_DOWN, false)) return true
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val channel = notification.channelId.orEmpty()
        val blob = "$title $text $channel"
        return clockPackages.contains(sbn.packageName) && blob.contains("timer", ignoreCase = true)
    }

    private fun remaining(notification: Notification): Pair<Long, Boolean>? {
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val paused = "$title $text".contains("paused", ignoreCase = true)
        val parsed = parseClock(text) ?: parseClock(title)
        val countdown = extras.getBoolean(CHRONOMETER_COUNT_DOWN, false)
        if (countdown && notification.`when` > 0L) {
            val wall = notification.`when` - System.currentTimeMillis()
            val elapsed = notification.`when` - SystemClock.elapsedRealtime()
            val left = when {
                wall in -2_000..DAY -> wall.coerceAtLeast(0L)
                elapsed in -2_000..DAY -> elapsed.coerceAtLeast(0L)
                else -> null
            }
            if (left != null && !paused) return left to false
            if (paused && parsed != null) return parsed to true
            if (left != null) return left to paused
        }
        if (parsed != null) return parsed to paused
        return null
    }

    /** MessagingStyle only when the notification itself is live or ongoing. Ordinary SMS stays out. */
    private fun liveMessage(notification: Notification): Pair<String, String>? {
        if (!live(notification)) return null
        val bundles = notification.extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        val messaging = notification.category == Notification.CATEGORY_MESSAGE || !bundles.isNullOrEmpty()
        if (!messaging) return null
        val lines = ArrayList<String>()
        if (bundles != null) {
            for (item in bundles) {
                val bundle = item as? android.os.Bundle ?: continue
                val text = bundle.getCharSequence("text")?.toString()?.trim().orEmpty()
                if (text.isNotEmpty()) lines.add(text)
            }
        }
        if (lines.isEmpty()) {
            val big = textExtra(notification, Notification.EXTRA_BIG_TEXT)
            val text = textExtra(notification, Notification.EXTRA_TEXT)
            val body = big.ifEmpty { text }
            if (body.isNotEmpty()) lines.add(body)
        }
        val body = lines.takeLast(4).joinToString("\n")
        val last = bundles?.lastOrNull() as? android.os.Bundle
        val sender = senderPerson(last)?.trim().orEmpty()
            .ifEmpty { last?.getCharSequence("sender")?.toString()?.trim().orEmpty() }
            .ifEmpty { textExtra(notification, Notification.EXTRA_CONVERSATION_TITLE) }
            .ifEmpty { textExtra(notification, Notification.EXTRA_TITLE) }
        if (sender.isEmpty() && body.isEmpty()) return null
        return sender to body
    }

    private fun liveProgress(notification: Notification): ProgressNote? {
        if (!live(notification)) return null
        val extras = notification.extras
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        val template = extras.getString(Notification.EXTRA_TEMPLATE).orEmpty()
        val progressStyle = template.contains("ProgressStyle")
        if (max <= 0 && !indeterminate && notification.category != Notification.CATEGORY_PROGRESS && !progressStyle) return null
        val current = extras.getInt(Notification.EXTRA_PROGRESS, 0)
        val fraction = if (max > 0) (current.toFloat() / max.toFloat()).coerceIn(0f, 1f) else 0f
        val label = when {
            max <= 0 || indeterminate -> "…"
            fraction >= 1f -> "Done"
            else -> "${(fraction * 100).toInt()}%"
        }
        val title = textExtra(notification, Notification.EXTRA_TITLE).ifEmpty { "Progress" }
        val detail = textExtra(notification, Notification.EXTRA_TEXT).ifEmpty { textExtra(notification, Notification.EXTRA_SUB_TEXT) }
        return ProgressNote(title, detail, fraction, label)
    }

    private fun liveRide(notification: Notification): RideNote? {
        if (!live(notification)) return null
        val category = notification.category
        val navigation = category == Notification.CATEGORY_NAVIGATION || category == Notification.CATEGORY_LOCATION_SHARING
        val transport = category == Notification.CATEGORY_TRANSPORT
        if (!navigation && !transport) return null
        val title = textExtra(notification, Notification.EXTRA_TITLE)
        val detail = textExtra(notification, Notification.EXTRA_TEXT)
        val sub = textExtra(notification, Notification.EXTRA_SUB_TEXT)
        val blob = "$title $detail $sub"
        val delivery = transport && blob.contains("deliver", ignoreCase = true)
        val value = sub.ifEmpty { detail }.ifEmpty { title }
        val name = title.ifEmpty { if (delivery) "Delivery" else "On the way" }
        return RideNote(name, detail.ifEmpty { sub }, value, delivery)
    }

    private fun parseClock(text: String): Long? {
        val match = CLOCK.find(text) ?: return null
        val first = match.groupValues[1].toLongOrNull() ?: return null
        val second = match.groupValues[2].toLongOrNull() ?: return null
        val third = match.groupValues[3].toLongOrNull()
        val ms = if (third == null) (first * 60 + second) * 1000 else (first * 3600 + second * 60 + third) * 1000
        return ms.coerceAtLeast(0L)
    }

    private fun durationText(text: String): Boolean = CLOCK.containsMatchIn(text) && text.none { it.isLetter() }

    private data class TimerNote(val endsAt: Long, val frozen: Long?, val paused: Boolean, val label: String)
    private data class ProgressNote(val title: String, val detail: String, val fraction: Float, val label: String)
    private data class RideNote(val title: String, val detail: String, val value: String, val delivery: Boolean)

    companion object {
        private const val CHRONOMETER_COUNT_DOWN = "android.chronometerCountDown"
        private const val DAY = 24L * 60L * 60L * 1000L
        private val CLOCK = Regex("""(\d+):(\d{2})(?::(\d{2}))?""")
        private val clockPackages = setOf(
            "com.google.android.deskclock",
            "com.android.deskclock",
            "com.sec.android.app.clockpackage",
            "com.samsung.android.app.clockpackage",
            "com.oneplus.deskclock",
            "com.coloros.alarmclock",
            "com.oplus.alarmclock",
            "com.hihonor.deskclock",
            "com.huawei.deskclock",
            "com.android.alarmclock",
        )

        var controls: MediaController.TransportControls? = null
        @Volatile var instance: IslandNotificationListener? = null
    }
}
