package dev.islandcn.island

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

/** The live media session. Never substitutes a placeholder title or cover. */
object IslandMedia {
    data class Track(
        val title: String,
        val artist: String,
        val playing: Boolean,
        val position: Long = 0L,
        val duration: Long = 0L,
        val updatedAt: Long = 0L,
        val speed: Float = 1f,
        /** elapsedRealtime when this snapshot was read. Used when the session omits an update time. */
        val sampledAt: Long = 0L,
        /** True when a notification action is Unlike, false when it is Like, null when there is none. */
        val liked: Boolean? = null,
        val art: Bitmap? = null,
    )

    fun current(context: Context): Track? {
        val track = try {
            val fromListener = IslandNotificationListener.instance?.let { sessionsFrom(it) }
            if (fromListener != null) fromListener
            else {
                val manager = context.getSystemService(MediaSessionManager::class.java)
                val component = ComponentName(context, IslandNotificationListener::class.java)
                if (manager == null) null else {
                    val active = manager.getActiveSessions(component)
                    pick(context, active, notesFor(active))
                }
            }
        } catch (_: SecurityException) {
            null
        }
        if (track == null) IslandLinks.clearMedia()
        return track
    }

    fun describe(context: Context, controller: MediaController): Track? {
        val meta = controller.metadata
        val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        if (title.isEmpty()) return null
        val state = controller.playbackState ?: return null
        val playing = state.state == PlaybackState.STATE_PLAYING
        val paused = state.state == PlaybackState.STATE_PAUSED
        if (!playing && !paused) return null
        val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)?.trim().orEmpty()
        val duration = meta?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.coerceAtLeast(0L) ?: 0L
        val reported = state.position
        return Track(
            title = title,
            artist = artist,
            playing = playing,
            position = if (reported == PlaybackState.PLAYBACK_POSITION_UNKNOWN) 0L else reported.coerceAtLeast(0L),
            duration = duration,
            updatedAt = state.lastPositionUpdateTime,
            speed = state.playbackSpeed,
            sampledAt = SystemClock.elapsedRealtime(),
            art = artwork(context, meta, title),
        )
    }

    /**
     * Position at [now], from PlaybackState position, lastPositionUpdateTime, and speed.
     * Playing advances. Paused stays on the reported position. Duration is the metadata duration.
     */
    fun livePosition(track: Track, now: Long = SystemClock.elapsedRealtime(), wallNow: Long = System.currentTimeMillis()): Long {
        val base = track.position.coerceAtLeast(0L)
        fun cap(value: Long) = if (track.duration > 0L) value.coerceIn(0L, track.duration) else value.coerceAtLeast(0L)
        if (!track.playing) return cap(base)
        val speed = if (track.speed == 0f) 1f else track.speed
        val anchor = when {
            track.updatedAt <= 0L -> track.sampledAt
            track.updatedAt > now + 60_000L -> now - (wallNow - track.updatedAt)
            else -> track.updatedAt
        }
        if (anchor <= 0L) return cap(base)
        val delta = ((now - anchor) * speed).toLong()
        return cap(base + delta)
    }

    private fun sessionsFrom(service: android.service.notification.NotificationListenerService): Track? {
        val controllers = mutableListOf<MediaController>()
        val notes = HashMap<android.media.session.MediaSession.Token, android.app.Notification>()
        for (sbn in service.activeNotifications ?: emptyArray()) {
            val token = sessionToken(sbn.notification) ?: continue
            notes[token] = sbn.notification
            controllers.add(MediaController(service, token))
        }
        if (controllers.isEmpty()) {
            return try {
                val manager = service.getSystemService(MediaSessionManager::class.java) ?: return null
                val active = manager.getActiveSessions(ComponentName(service, IslandNotificationListener::class.java))
                pick(service, active, notesFor(active))
            } catch (_: SecurityException) {
                null
            }
        }
        return pick(service, controllers, notes)
    }

    private fun pick(context: Context, controllers: List<MediaController>, notes: Map<android.media.session.MediaSession.Token, android.app.Notification>): Track? {
        val tracks = controllers.mapNotNull { controller ->
            val track = describe(context, controller) ?: return@mapNotNull null
            Triple(track, controller, controller.transportControls)
        }
        val chosen = tracks.firstOrNull { it.first.playing } ?: tracks.firstOrNull() ?: return null
        IslandNotificationListener.controls = chosen.third
        IslandLinks.bindMedia(notes[chosen.second.sessionToken] ?: packageNote(chosen.second.packageName))
        return chosen.first.copy(liked = IslandLinks.liked)
    }

    /** The only media notification from this package, when the session token did not match. */
    private fun packageNote(packageName: String): android.app.Notification? {
        val service = IslandNotificationListener.instance ?: return null
        val matched = service.activeNotifications?.filter { it.packageName == packageName && sessionToken(it.notification) != null }.orEmpty()
        return if (matched.size == 1) matched.first().notification else null
    }

    private fun notesFor(controllers: List<MediaController>): Map<android.media.session.MediaSession.Token, android.app.Notification> {
        val service = IslandNotificationListener.instance ?: return emptyMap()
        val notes = HashMap<android.media.session.MediaSession.Token, android.app.Notification>()
        for (sbn in service.activeNotifications ?: emptyArray()) {
            val token = sessionToken(sbn.notification) ?: continue
            if (controllers.isEmpty() || controllers.any { it.sessionToken == token }) notes[token] = sbn.notification
        }
        return notes
    }

    private fun sessionToken(notification: android.app.Notification): android.media.session.MediaSession.Token? {
        val extras = notification.extras
        val token = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelable(android.app.Notification.EXTRA_MEDIA_SESSION, android.media.session.MediaSession.Token::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelable(android.app.Notification.EXTRA_MEDIA_SESSION)
        }
        return token as? android.media.session.MediaSession.Token
    }

    private fun artwork(context: Context, meta: MediaMetadata?, title: String): Bitmap? {
        if (meta == null) return null
        val opener = IslandNotificationListener.instance ?: context
        val uri = artUri(meta)
        val key = uri ?: bitmapKey(meta, title)
        if (key == null) {
            clearArt()
            return null
        }
        if (key == artKey && artBitmap?.isRecycled == false) return artBitmap
        val immediate = bitmapOf(meta)
        if (immediate != null) {
            remember(key, scale(immediate))
            return artBitmap
        }
        if (uri == null) {
            clearArt()
            return null
        }
        if (failedKey == uri && SystemClock.elapsedRealtime() - failedAt < 8000L) return null
        if (loadingKey != uri) {
            loadingKey = uri
            val app = opener.applicationContext
            Thread {
                val decoded = decodeUri(app, uri)
                Handler(Looper.getMainLooper()).post {
                    if (loadingKey != uri && artKey != uri) return@post
                    loadingKey = null
                    if (decoded == null) {
                        if (artKey == uri) artBitmap = null
                        failedKey = uri
                        failedAt = SystemClock.elapsedRealtime()
                    } else {
                        remember(uri, scale(decoded))
                        failedKey = null
                    }
                    IslandOverlayService.onMedia(app, current(app))
                }
            }.start()
        }
        return if (artKey == key) artBitmap else null
    }

    private fun artUri(meta: MediaMetadata): String? {
        val keys = arrayOf(
            MediaMetadata.METADATA_KEY_ART_URI,
            MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI,
        )
        for (key in keys) {
            val value = meta.getString(key)?.trim().orEmpty()
            if (value.isNotEmpty()) return value
        }
        return null
    }

    private fun bitmapOf(meta: MediaMetadata): Bitmap? {
        val keys = arrayOf(
            MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON,
        )
        for (key in keys) {
            val bitmap = try {
                meta.getBitmap(key)
            } catch (_: Throwable) {
                null
            }
            if (bitmap != null && !bitmap.isRecycled && bitmap.width > 0 && bitmap.height > 0) return bitmap
        }
        return null
    }

    private fun bitmapKey(meta: MediaMetadata, title: String): String? {
        val bitmap = bitmapOf(meta) ?: return null
        val pixel = try {
            bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        } catch (_: Throwable) {
            0
        }
        return "bmp:$title:${bitmap.width}x${bitmap.height}:$pixel"
    }

    private fun decodeUri(context: Context, raw: String): Bitmap? {
        return try {
            val uri = Uri.parse(raw)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            open(context, uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample(bounds.outWidth, bounds.outHeight, 256)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            open(context, uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (_: Throwable) {
            null
        }
    }

    private fun open(context: Context, uri: Uri): java.io.InputStream? {
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> {
                val connection = (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 4000
                    readTimeout = 4000
                    instanceFollowRedirects = true
                }
                connection.inputStream
            }
            else -> context.contentResolver.openInputStream(uri)
        }
    }

    private fun sample(width: Int, height: Int, edge: Int): Int {
        var size = 1
        var largest = max(width, height)
        while (largest / 2 >= edge) {
            size *= 2
            largest /= 2
        }
        return size
    }

    private fun scale(source: Bitmap): Bitmap {
        if (source.isRecycled) return source
        val largest = max(source.width, source.height)
        val sized = if (largest <= 256) source else {
            val factor = 256f / largest
            Bitmap.createScaledBitmap(
                source,
                (source.width * factor).toInt().coerceAtLeast(1),
                (source.height * factor).toInt().coerceAtLeast(1),
                true,
            )
        }
        if (sized.config == Bitmap.Config.HARDWARE || sized === source) {
            return try {
                sized.copy(Bitmap.Config.ARGB_8888, false) ?: sized
            } catch (_: Throwable) {
                sized
            }
        }
        return sized
    }

    private fun remember(key: String, bitmap: Bitmap) {
        artKey = key
        artBitmap = bitmap
    }

    private fun clearArt() {
        artKey = null
        artBitmap = null
    }

    @Volatile private var artBitmap: Bitmap? = null
    @Volatile private var artKey: String? = null
    @Volatile private var loadingKey: String? = null
    @Volatile private var failedKey: String? = null
    @Volatile private var failedAt = 0L
}
