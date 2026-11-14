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
    private val timers = LinkedHashMap<String, TimerNote>()

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
        val removedMessage = messages.remove(key) != null
        val removedTimer = timers.remove(key) != null
        if (removedMessage) publishMessage()
        if (removedTimer) publishTimer()
        val token = mediaToken(sbn.notification)
        if (token != null) {
            sessions.remove(token)?.unregisterCallback(mediaCallback)
            IslandOverlayService.onMedia(this, IslandMedia.current(this))
        }
    }

    private fun handlePosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val notification = sbn.notification
        val token = mediaToken(notification)
        if (token != null) {
            publishMedia(token)
            return
        }
        if (isCall(notification)) {
            callerName(notification)?.let { IslandMonitors.noteCaller(this, it, (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) }
            return
        }
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (isTimer(sbn)) {
            val remaining = remaining(notification) ?: return
            val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
            val label = if (title.isEmpty() || durationText(title)) "Timer" else title
            val paused = remaining.second
            timers[sbn.key] = TimerNote(
                endsAt = if (paused) 0L else SystemClock.elapsedRealtime() + remaining.first,
                frozen = if (paused) remaining.first else null,
                paused = paused,
                label = label,
            )
            publishTimer()
            return
        }
        val message = messageOf(notification) ?: return
        if (message.first.isEmpty() && message.second.isEmpty()) return
        messages.remove(sbn.key)
        messages[sbn.key] = message
        publishMessage()
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

    private fun isCall(notification: Notification): Boolean {
        if (notification.category == Notification.CATEGORY_CALL) return true
        if (callPerson(notification.extras)?.name != null) return true
        return false
    }

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

    private fun messageOf(notification: Notification): Pair<String, String>? {
        val messages = notification.extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (messages != null && messages.isNotEmpty()) {
            val bundle = messages.lastOrNull() as? android.os.Bundle
            val body = bundle?.getCharSequence("text")?.toString()?.trim().orEmpty()
            val person = senderPerson(bundle)
            val sender = person?.trim().orEmpty()
                .ifEmpty { bundle?.getCharSequence("sender")?.toString()?.trim().orEmpty() }
                .ifEmpty { notification.extras.getCharSequence("android.conversationTitle")?.toString()?.trim().orEmpty() }
                .ifEmpty { notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty() }
            if (sender.isEmpty() && body.isEmpty()) return null
            return sender to body
        }
        if (notification.category != Notification.CATEGORY_MESSAGE) return null
        val sender = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val body = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (sender.isEmpty() && body.isEmpty()) return null
        return sender to body
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
