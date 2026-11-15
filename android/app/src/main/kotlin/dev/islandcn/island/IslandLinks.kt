package dev.islandcn.island

import android.app.Notification
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings

/**
 * What a tap on the expanded card opens. Each activity keeps the notification content
 * intent it came from. The battery island opens battery settings. A Like or Unlike
 * action on a media notification is separate, and only exists when the notification has one.
 */
internal object IslandLinks {
    private val faces = HashMap<String, PendingIntent>()

    @Volatile private var likeIntent: PendingIntent? = null

    /** True when the action is Unlike (already liked), false when it is Like, null when absent. */
    @Volatile var liked: Boolean? = null
        private set

    fun set(id: String, intent: PendingIntent?) {
        if (intent == null) faces.remove(id) else faces[id] = intent
    }

    fun clear(id: String) {
        faces.remove(id)
    }

    fun bindMedia(notification: Notification?) {
        if (notification == null) {
            clear("now-playing")
            likeIntent = null
            liked = null
            return
        }
        set("now-playing", notification.contentIntent)
        val action = notification.actions?.firstOrNull { likeWord(it.title) != null }
        likeIntent = action?.actionIntent
        liked = when (likeWord(action?.title)) {
            "unlike" -> true
            "like" -> false
            else -> null
        }
    }

    fun clearMedia() {
        clear("now-playing")
        likeIntent = null
        liked = null
    }

    fun like() {
        val pending = likeIntent ?: return
        try {
            pending.send()
        } catch (_: PendingIntent.CanceledException) {
        }
    }

    /** Open the app behind [id]. Battery opens the system battery screen. */
    fun open(context: android.content.Context, id: String): Boolean {
        if (id == "battery") return openBattery(context)
        val pending = faces[id] ?: return false
        return try {
            pending.send()
            true
        } catch (_: PendingIntent.CanceledException) {
            false
        }
    }

    private fun openBattery(context: android.content.Context): Boolean {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK
        val primary = Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(flags)
        val fallback = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(flags)
        return try {
            context.startActivity(primary)
            true
        } catch (_: ActivityNotFoundException) {
            try {
                context.startActivity(fallback)
                true
            } catch (_: ActivityNotFoundException) {
                false
            }
        }
    }

    /** The action title is Like or Unlike, not a Spotify-only name. */
    private fun likeWord(title: CharSequence?): String? {
        val word = title?.toString()?.trim()?.substringBefore(' ')?.lowercase()?.trim { it == '.' || it == '!' || it == '…' }
            ?: return null
        return if (word == "like" || word == "unlike") word else null
    }
}
