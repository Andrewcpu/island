package dev.islandcn.island

import android.app.Notification
import android.app.Person
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.text.DateFormat
import java.util.Date
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Read-only snapshot of the notifications the listener can see. Nothing here is published
 * to the island overlay.
 */
object IslandNotificationDump {
    private val main = Handler(Looper.getMainLooper())
    private var announced = false

    fun announce() {
        if (announced) return
        announced = true
        main.postDelayed({
            announced = false
            IslandBus.emit(mapOf("type" to "notifications"))
        }, 150)
    }

    fun snapshot(service: IslandNotificationListener?): Map<String, Any?> {
        if (service == null) {
            return mapOf(
                "ok" to false,
                "reason" to "Notification access is off",
                "notifications" to emptyList<Map<String, Any?>>(),
            )
        }
        val notes = try {
            service.activeNotifications?.toList().orEmpty()
        } catch (error: Exception) {
            return mapOf(
                "ok" to false,
                "reason" to (error.message ?: "Could not read notifications"),
                "notifications" to emptyList<Map<String, Any?>>(),
            )
        }
        val items = notes.sortedByDescending { it.postTime }.map { note ->
            try {
                describe(service, note)
            } catch (error: Exception) {
                mapOf(
                    "package" to note.packageName,
                    "app" to note.packageName,
                    "title" to "",
                    "text" to "",
                    "subtext" to "",
                    "ongoing" to note.isOngoing,
                    "live" to false,
                    "actions" to emptyList<String>(),
                    "extras" to listOf(mapOf("key" to "error", "value" to (error.message ?: "unreadable"))),
                    "key" to note.key,
                )
            }
        }
        return mapOf("ok" to true, "notifications" to items)
    }

    private fun describe(service: IslandNotificationListener, sbn: StatusBarNotification): Map<String, Any?> {
        val notification = sbn.notification
        val extras = notification.extras
        val title = text(extras, Notification.EXTRA_TITLE)
        val body = text(extras, Notification.EXTRA_TEXT)
        val sub = text(extras, Notification.EXTRA_SUB_TEXT)
        val ongoing = sbn.isOngoing
        val live = (notification.flags and Notification.FLAG_PROMOTED_ONGOING) != 0
        val progress = progressOf(extras)
        val chronometer = chronometerOf(notification)
        val actions = notification.actions?.map { it.title?.toString()?.trim().orEmpty().ifEmpty { "(untitled)" } }.orEmpty()
        val large = iconPayload(service, notification.getLargeIcon())
        val picture = pictureOf(service, extras)
        return mapOf(
            "package" to sbn.packageName,
            "app" to appLabel(service, sbn.packageName),
            "title" to title,
            "text" to body,
            "subtext" to sub,
            "ongoing" to ongoing,
            "live" to live,
            "progress" to progress,
            "chronometer" to chronometer,
            "actions" to actions,
            "largeIcon" to large,
            "picture" to picture,
            "channel" to notification.channelId.orEmpty(),
            "category" to notification.category.orEmpty(),
            "id" to sbn.id,
            "tag" to sbn.tag.orEmpty(),
            "key" to sbn.key,
            "posted" to stamp(sbn.postTime),
            "extras" to extraRows(extras),
        )
    }

    private fun progressOf(extras: Bundle): Map<String, Any?>? {
        val max = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val current = extras.getInt(Notification.EXTRA_PROGRESS, 0)
        val indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        if (max <= 0 && current <= 0 && !indeterminate) return null
        val percent = if (max > 0) (current * 100.0 / max).roundToInt().coerceIn(0, 100) else null
        return mapOf(
            "current" to current,
            "max" to max,
            "indeterminate" to indeterminate,
            "percent" to percent,
        )
    }

    private fun chronometerOf(notification: Notification): Map<String, Any?> {
        val extras = notification.extras
        val show = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
        val countdown = extras.getBoolean("android.chronometerCountDown", false)
        val base = notification.`when`
        if (!show && !countdown) return mapOf("on" to false)
        val now = System.currentTimeMillis()
        val delta = if (countdown) base - now else now - base
        val label = if (countdown) "${clock(delta)} remaining" else clock(delta)
        return mapOf(
            "on" to true,
            "countdown" to countdown,
            "base" to if (base > 0L) stamp(base) else "",
            "text" to label,
        )
    }

    private fun clock(ms: Long): String {
        val sign = if (ms < 0) "-" else ""
        val total = abs(ms) / 1000
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) "$sign$hours:%02d:%02d".format(minutes, seconds) else "$sign%02d:%02d".format(minutes, seconds)
    }

    private fun extraRows(extras: Bundle): List<Map<String, String>> {
        val skip = setOf(
            Notification.EXTRA_TITLE,
            Notification.EXTRA_TITLE_BIG,
            Notification.EXTRA_TEXT,
            Notification.EXTRA_SUB_TEXT,
            Notification.EXTRA_PROGRESS,
            Notification.EXTRA_PROGRESS_MAX,
            Notification.EXTRA_PROGRESS_INDETERMINATE,
            Notification.EXTRA_SHOW_CHRONOMETER,
            "android.chronometerCountDown",
            Notification.EXTRA_LARGE_ICON,
            Notification.EXTRA_LARGE_ICON_BIG,
            Notification.EXTRA_PICTURE,
            "android.pictureIcon",
        )
        val rows = ArrayList<Map<String, String>>()
        for (key in extras.keySet().sorted()) {
            if (key in skip) continue
            val value = stringify(read(extras, key)) ?: continue
            if (value.isEmpty()) continue
            rows.add(mapOf("key" to key, "value" to value.take(1500)))
        }
        return rows
    }

    private fun read(bundle: Bundle, key: String): Any? {
        return try {
            if (Build.VERSION.SDK_INT >= 33) bundle.get(key) else @Suppress("DEPRECATION") bundle.get(key)
        } catch (_: Exception) {
            null
        }
    }

    private fun stringify(value: Any?, depth: Int = 0): String? {
        if (value == null || depth > 3) return null
        return when (value) {
            is CharSequence -> value.toString().trim().ifEmpty { null }
            is Number, is Boolean -> value.toString()
            is Bundle -> {
                val parts = value.keySet().sorted().mapNotNull { key ->
                    stringify(read(value, key), depth + 1)?.let { "$key=$it" }
                }
                parts.joinToString(", ").ifEmpty { null }
            }
            is Array<*> -> value.mapNotNull { stringify(it, depth + 1) }.joinToString(" | ").ifEmpty { null }
            is Iterable<*> -> value.mapNotNull { stringify(it, depth + 1) }.joinToString(" | ").ifEmpty { null }
            is Person -> value.name?.toString()?.trim()?.ifEmpty { null }
            is Bitmap, is Icon -> null
            else -> {
                val text = value.toString().trim()
                if (text.isEmpty() || text.startsWith(value.javaClass.name)) null else text
            }
        }
    }

    private fun pictureOf(service: IslandNotificationListener, extras: Bundle): String? {
        val bitmap = if (Build.VERSION.SDK_INT >= 33) {
            extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelable(Notification.EXTRA_PICTURE)
        }
        if (bitmap != null) return payload(bitmap)
        if (Build.VERSION.SDK_INT >= 31) {
            val icon = extras.getParcelable("android.pictureIcon", Icon::class.java)
            if (icon != null) return iconPayload(service, icon)
        }
        return null
    }

    private fun iconPayload(service: IslandNotificationListener, icon: Icon?): String? {
        if (icon == null) return null
        val drawable = try {
            icon.loadDrawable(service)
        } catch (_: Exception) {
            null
        } ?: return null
        val width = drawable.intrinsicWidth.takeIf { it > 0 } ?: 128
        val height = drawable.intrinsicHeight.takeIf { it > 0 } ?: 128
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        return payload(bitmap)
    }

    private fun payload(source: Bitmap): String? {
        return try {
            val soft = if (source.config == Bitmap.Config.HARDWARE) source.copy(Bitmap.Config.ARGB_8888, false) ?: return null else source
            val largest = max(soft.width, soft.height).coerceAtLeast(1)
            val scale = min(1f, 160f / largest)
            val scaled = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    soft,
                    (soft.width * scale).toInt().coerceAtLeast(1),
                    (soft.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
            } else {
                soft
            }
            val stream = ByteArrayOutputStream()
            val wrote = scaled.compress(Bitmap.CompressFormat.PNG, 90, stream)
            if (scaled !== soft) scaled.recycle()
            if (soft !== source) soft.recycle()
            if (!wrote) null else Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            null
        }
    }

    private fun appLabel(service: IslandNotificationListener, packageName: String): String {
        return try {
            val info = service.packageManager.getApplicationInfo(packageName, 0)
            service.packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            packageName
        }
    }

    private fun text(extras: Bundle, key: String): String =
        extras.getCharSequence(key)?.toString()?.trim().orEmpty()

    private fun stamp(ms: Long): String {
        if (ms <= 0L) return ""
        return DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM).format(Date(ms))
    }
}
